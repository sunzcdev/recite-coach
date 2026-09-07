package com.sunzc.recite

/**
 * 背诵诊断算法（纯函数，无 Android 依赖，可在 JVM 单测）。
 *
 * 数据语义（2026-09-07 VPS 实测 sherpa-onnx-paraformer-zh-2023-09-14）:
 * - timestamps[i] = 第 i 个 token 的【起始时刻】(秒)，无结束时刻
 * - 停顿判定必须用 gap = ts[i+1] - ts[i]
 * - 实测正常字间 gap 0.18~0.42s（含逗号），4s 静音实测 gap ≈ 4.9s
 * - 阈值 1.8s 安全边界充足
 */
object RecitationAnalyzer {

    const val PAUSE_THRESHOLD_S = 1.8f
    const val MIN_REPEAT_UNIT = 2      // 最短重复单元（字数）
    const val MAX_REPEAT_UNIT = 12

    data class Pause(val beforeIdx: Int, val gapS: Float, val beforeChar: String, val afterChar: String)
    data class Repetition(val text: String, val count: Int, val startIdx: Int)
    data class DiffOp(val type: OpType, val refChar: String, val hypChar: String, val refPos: Int)
    enum class OpType { MATCH, WRONG, MISSING, EXTRA }

    data class Report(
        val score: Int,
        val pauses: List<Pause>,
        val repetitions: List<Repetition>,
        val diffs: List<DiffOp>,
        val targets: List<String>,   // 靶向复习建议
        val recogText: String,
    )

    fun analyze(reference: String, tokens: List<String>, timestamps: FloatArray): Report {
        // 1. 停顿检测: gap = ts[i+1] - ts[i]
        val pauses = mutableListOf<Pause>()
        for (i in 0 until tokens.size - 1) {
            val gap = timestamps[i + 1] - timestamps[i]
            if (gap > PAUSE_THRESHOLD_S) {
                pauses.add(Pause(i, gap, tokens[i], tokens[i + 1]))
            }
        }

        // 2. 重复检测: 相邻重复子串（贪心取最长单元，计数连续重复）
        val repetitions = mutableListOf<Repetition>()
        var i = 0
        // 剔除重复段后的 token 序列,用于错漏字比对(避免双重惩罚)
        val dedupTokens = mutableListOf<String>()
        while (i < tokens.size) {
            var bestLen = 0
            var bestCount = 0
            for (L in MIN_REPEAT_UNIT..MAX_REPEAT_UNIT) {
                if (i + 2 * L > tokens.size) break
                val unit = tokens.subList(i, i + L)
                var count = 1
                var j = i + L
                while (j + L <= tokens.size && tokens.subList(j, j + L) == unit) {
                    count++
                    j += L
                }
                if (count >= 2 && L * count > bestLen * bestCount) {
                    bestLen = L
                    bestCount = count
                }
            }
            if (bestCount >= 2) {
                repetitions.add(Repetition(tokens.subList(i, i + bestLen * bestCount).joinToString(""), bestCount, i))
                // 只保留第一遍,其余遍数剔除
                dedupTokens.addAll(tokens.subList(i, i + bestLen))
                i += bestLen * bestCount
            } else {
                dedupTokens.add(tokens[i])
                i++
            }
        }

        // 3. 错漏字比对: Levenshtein 对齐（原文去标点 vs 去重后的识别文本）
        val ref = reference.filter { it.code > 0x2E00 }  // 去 ASCII 标点/空白，保留汉字
        val hyp = dedupTokens.joinToString("")
        val diffs = alignDiff(ref, hyp)

        // 4. 评分（扣分制，最低 0）
        var penalty = 0f
        for (d in diffs) {
            penalty += when (d.type) {
                OpType.WRONG, OpType.MISSING, OpType.EXTRA -> 3f
                OpType.MATCH -> 0f
            }
        }
        // 连续大段遗忘: 连续 MISSING >= 10 追加 15
        var runMiss = 0
        for (d in diffs) {
            if (d.type == OpType.MISSING) {
                runMiss++
                if (runMiss == 10) penalty += 15f
            } else runMiss = 0
        }
        for (p in pauses) penalty += 1f + (p.gapS - PAUSE_THRESHOLD_S) * 0.5f
        penalty += repetitions.size * 2f
        val score = (100 - penalty).toInt().coerceIn(0, 100)

        // 5. 靶向建议: 按严重度排序 Top2
        val targets = mutableListOf<Pair<Float, String>>()
        val missRunText = StringBuilder()
        var missLen = 0
        for (d in diffs) {
            if (d.type == OpType.MISSING) { missLen++; missRunText.append(d.refChar) }
            else {
                if (missLen >= 10) targets.add(15f + missLen to "整段遗忘「${missRunText}」——先补背这段")
                missLen = 0; missRunText.clear()
            }
        }
        if (missLen >= 10) targets.add(15f + missLen to "整段遗忘「$missRunText」——先补背这段")
        for (d in diffs) if (d.type == OpType.WRONG) targets.add(3.5f to "错字:「${d.hypChar}」应读「${d.refChar}」")
        for (p in pauses.sortedByDescending { it.gapS }) targets.add(p.gapS * 0.5f to "「${p.beforeChar}${p.afterChar}」处停顿 ${"%.1f".format(p.gapS)}s——练熟连贯")
        for (r in repetitions) targets.add(2.5f to "「${r.text}」重复了 ${r.count} 遍——注意别回读")
        val topTargets = targets.sortedByDescending { it.first }.take(2).map { it.second }

        return Report(score, pauses, repetitions, diffs, topTargets, hyp)
    }

    /** Levenshtein DP 对齐，返回逐字操作序列。 */
    fun alignDiff(ref: String, hyp: String): List<DiffOp> {
        val n = ref.length
        val m = hyp.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            dp[i][j] = if (ref[i - 1] == hyp[j - 1]) dp[i - 1][j - 1]
            else minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1]) + 1
        }
        // 回溯
        val ops = mutableListOf<DiffOp>()
        var i = n; var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && ref[i - 1] == hyp[j - 1] -> {
                    ops.add(DiffOp(OpType.MATCH, ref[i - 1].toString(), hyp[j - 1].toString(), i - 1)); i--; j--
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    ops.add(DiffOp(OpType.WRONG, ref[i - 1].toString(), hyp[j - 1].toString(), i - 1)); i--; j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    ops.add(DiffOp(OpType.MISSING, ref[i - 1].toString(), "", i - 1)); i--
                }
                else -> {
                    ops.add(DiffOp(OpType.EXTRA, "", hyp[j - 1].toString(), i)); j--
                }
            }
        }
        ops.reverse()
        return ops
    }
}
