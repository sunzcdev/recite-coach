package com.sunzc.recite

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Logcat 远程日志上报
 * - 内存缓冲 + 定时批量发送（5s 间隔或满 50 条）
 * - 纯 OkHttp-less 实现（用 HttpURLConnection，零新依赖）
 * - 所有网络操作在后台线程，不阻塞 UI
 */
object Logcat {
    private const val TAG = "Logcat"
    private const val ENDPOINT = "http://100.68.80.91:8080/log"
    private const val FLUSH_INTERVAL_S = 5L
    private const val MAX_BATCH = 50
    private const val MAX_QUEUE = 500

    private val queue = ConcurrentLinkedQueue<Entry>()
    private val executor = Executors.newSingleThreadExecutor()
    private var device_id: String = ""
    private var app_version: String = ""
    private var model: String = ""
    private var sdk: Int = 0
    private var initialized = false

    data class Entry(
        val ts: Long,
        val level: String,
        val tag: String,
        val msg: String,
        val raw: String? = null
    )

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        device_id = generateDeviceId(context)
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            app_version = pi.versionName ?: "unknown"
        } catch (_: Exception) { app_version = "unknown" }
        model = Build.MODEL
        sdk = Build.VERSION.SDK_INT
        initialized = true
        // 定时 flush
        executor.execute {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_S * 1000)
                    flush()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }
        i("Logcat", "init device_id=$device_id app=$app_version model=$model sdk=$sdk")
    }

    private fun generateDeviceId(context: Context): String {
        val sp = context.getSharedPreferences("logcat", Context.MODE_PRIVATE)
        var id = sp.getString("device_id", null)
        if (id == null) {
            id = "android-" + UUID.randomUUID().toString().substring(0, 8)
            sp.edit().putString("device_id", id).apply()
        }
        return id
    }

    @JvmStatic
    fun d(tag: String, msg: String) = buffer("D", tag, msg)
    @JvmStatic
    fun i(tag: String, msg: String) = buffer("I", tag, msg)
    @JvmStatic
    fun w(tag: String, msg: String) = buffer("W", tag, msg)
    @JvmStatic
    fun e(tag: String, msg: String) = buffer("E", tag, msg)

    private fun buffer(level: String, tag: String, msg: String) {
        if (!initialized) {
            Log.println(when(level){"D"->Log.DEBUG;"W"->Log.WARN;"E"->Log.ERROR;else->Log.INFO}, tag, msg)
            return
        }
        queue.offer(Entry(System.currentTimeMillis(), level, tag, msg))
        // 超限时丢最旧
        while (queue.size > MAX_QUEUE) queue.poll()
        // 满批次立即发
        if (queue.size >= MAX_BATCH) executor.execute { flush() }
        // 同时输出到 logcat（本地调试）
        Log.println(when(level){"D"->Log.DEBUG;"W"->Log.WARN;"E"->Log.ERROR;else->Log.INFO}, tag, msg)
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val batch = JSONArray()
        val entries = mutableListOf<Entry>()
        while (entries.size < MAX_BATCH) {
            val e = queue.poll() ?: break
            entries.add(e)
            val o = JSONObject()
            o.put("ts", e.ts)
            o.put("level", e.level)
            o.put("tag", e.tag)
            o.put("msg", e.msg)
            if (e.raw != null) o.put("raw", e.raw)
            batch.put(o)
        }
        if (entries.isEmpty()) return
        val payload = JSONObject().apply {
            put("device_id", device_id)
            put("app_version", app_version)
            put("model", model)
            put("sdk", sdk)
            put("entries", batch)
        }
        // 最多 3 次重试
        var retry = 3
        while (retry-- > 0) {
            try {
                val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return
            } catch (ex: Exception) {
                if (retry == 0) {
                    Log.w(TAG, "flush failed after 3 retries: ${ex.message}")
                    // 失败时把 entries 放回队列（避免丢日志）
                    for (e in entries.reversed()) queue.offer(e)
                    return
                }
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }
    }
}
