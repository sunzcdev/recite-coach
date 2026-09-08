package com.sunzc.recite

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 模型固定写死,不依赖 AAR 内置映射(防 API 漂移)。
// sherpa-onnx-paraformer-zh-2023-09-14: 实测唯一支持字级时间戳的中文 Paraformer
// (2026-09-07 Oracle VPS 实测: 30 token/30 时间戳,3处4s停顿全检出,文本0误报)
// CI 构建时把 model.int8.onnx + tokens.txt 下载进 assets 根目录
private fun buildConfig() = OfflineRecognizerConfig(
    modelConfig = OfflineModelConfig(
        paraformer = OfflineParaformerModelConfig(model = "model.int8.onnx"),
        tokens = "tokens.txt",
        modelType = "paraformer",
    )
)
private val TEXTBOOK = "床前明月光疑是地上霜举头望明月低头思故乡"

class MainActivity : ComponentActivity() {
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    @Volatile private var recording = false
    private val audioChunks = ArrayList<FloatArray>()  // 录音线程写入，分析时读取

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logcat.init(this)
        setContent { ReciteApp() }
    }

    private fun initRecognizer() {
        if (recognizer != null) return
        val config = buildConfig()
        config.modelConfig.numThreads = 2
        recognizer = OfflineRecognizer(assetManager = assets, config = config)
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Logcat.i("Recite", "permission not granted, requesting")
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        initRecognizer()
        synchronized(audioChunks) { audioChunks.clear() }
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        audioRecord?.startRecording()
        recording = true
        Logcat.i("Recite", "recording started, sr=$sr, bufSize=${minBuf * 4}")
        readerThread = Thread {
            val buf = ShortArray(1600)  // 0.1s per read
            while (recording) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    val f = FloatArray(n) { buf[it] / 32768.0f }
                    synchronized(audioChunks) { audioChunks.add(f) }
                }
            }
        }.also { it.start() }
    }

    private fun stopRecordingAndAnalyze(onReport: (RecitationAnalyzer.Report) -> Unit, onFail: (String) -> Unit) {
        recording = false
        try { readerThread?.join(2000) } catch (_: InterruptedException) {}
        readerThread = null
        val rec = audioRecord ?: return
        try { rec.stop() } catch (_: IllegalStateException) {}
        rec.release()
        audioRecord = null

        val all: FloatArray = synchronized(audioChunks) {
            val total = audioChunks.sumOf { it.size }
            val out = FloatArray(total)
            var off = 0
            for (c in audioChunks) { c.copyInto(out, off); off += c.size }
            out
        }
        if (all.size < 16000) {  // <1s 视为无效
            Logcat.w("Recite", "audio too short: ${all.size} samples (${all.size / 16000.0}s)")
            onFail("录音太短，至少背 1 秒以上")
            return
        }
        val durationS = all.size / 16000.0
        Logcat.i("Recite", "recording stopped, duration=${"%.1f".format(durationS)}s, samples=${all.size}")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val report = withContext(Dispatchers.Default) {
                    val r = recognizer!!
                    val stream = r.createStream()
                    stream.acceptWaveform(all, 16000)
                    r.decode(stream)
                    val result = r.getResult(stream)
                    stream.release()
                    Logcat.i("Recite", "ASR done, tokens=${result.timestamps.size}, text='${result.text}'")
                    RecitationAnalyzer.analyze(TEXTBOOK, result.tokens.toList(), result.timestamps)
                }
                Logcat.i("Recite", "score=${report.score}, diffs=${report.diffs.size}, pauses=${report.pauses.size}, reps=${report.repetitions.size}")
                withContext(Dispatchers.Main) { onReport(report) }
            } catch (e: Exception) {
                Logcat.e("Recite", "analyze failed: ${e.message}")
                withContext(Dispatchers.Main) { onFail("分析失败: ${e.message}") }
            }
        }
    }

    @Composable
    fun ReciteApp() {
        var isRecording by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<RecitationAnalyzer.Report?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        MaterialTheme {
            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("背诵小助手", fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))
                Text("课文《静夜思》", fontSize = 16.sp, color = Color.Gray)
                Text(TEXTBOOK, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        if (!isRecording) {
                            report = null
                            startRecording()
                            isRecording = true
                        } else {
                            busy = true
                            stopRecordingAndAnalyze(
                                onReport = { r -> report = r; busy = false },
                                onFail = { msg -> error = msg; busy = false }
                            )
                            isRecording = false
                        }
                    },
                    enabled = !busy
                ) {
                    Text(when { busy -> "分析中…" ; isRecording -> "■ 停止并评分" ; else -> "● 开始背诵" })
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.Red, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
                report?.let { ReportView(it) }
            }
        }
    }

    @Composable
    fun ReportView(r: RecitationAnalyzer.Report) {
        Column(Modifier.fillMaxWidth()) {
            Text("得分: ${r.score}", fontSize = 40.sp,
                color = when { r.score >= 85 -> Color(0xFF2E7D32); r.score >= 60 -> Color(0xFFF9A825); else -> Color(0xFFC62828) })
            Spacer(Modifier.height(8.dp))
            // 原文热力: 红=错字 灰=漏字 黄=多字
            Text(buildAnnotatedString {
                for (d in r.diffs) {
                    when (d.type) {
                        RecitationAnalyzer.OpType.MATCH -> append(d.refChar)
                        RecitationAnalyzer.OpType.WRONG -> withStyle(SpanStyle(background = Color(0xFFFFCDD2))) { append(d.refChar) }
                        RecitationAnalyzer.OpType.MISSING -> withStyle(SpanStyle(background = Color(0xFFB0BEC5))) { append(d.refChar) }
                        RecitationAnalyzer.OpType.EXTRA -> withStyle(SpanStyle(background = Color(0xFFFFF9C4))) { append(d.hypChar) }
                    }
                }
            }, fontSize = 22.sp, lineHeight = 34.sp)
            if (r.pauses.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("⏸ 停顿:", fontSize = 16.sp, color = Color(0xFFE65100))
                for (p in r.pauses) Text("   「${p.beforeChar}」→「${p.afterChar}」停了 ${"%.1f".format(p.gapS)} 秒", fontSize = 14.sp)
            }
            if (r.repetitions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("🔁 重复回读:", fontSize = 16.sp, color = Color(0xFF1565C0))
                for (rep in r.repetitions) Text("   「${rep.text}」重复 ${rep.count} 遍", fontSize = 14.sp)
            }
            if (r.targets.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("🎯 下一遍重点:", fontSize = 18.sp)
                for (t in r.targets) Text("   • $t", fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("识别结果: ${r.recogText}", fontSize = 12.sp, color = Color.LightGray)
        }
    }
}
