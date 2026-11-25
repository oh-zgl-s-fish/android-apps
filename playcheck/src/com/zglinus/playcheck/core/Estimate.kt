package com.zglinus.playcheck.core

/**
 * 每日时长预估：取最近 20 个有记录的日子（不足 20 个就用现有的全部），
 * 0.8×中位数 + 0.2×平均数，四舍五入到整分钟。
 * 纯函数，不依赖 android.*，便于单独验算。
 */
object Estimate {

    const val SAMPLES = 20

    data class Result(val ms: Long, val samples: Int)

    /** samples 需按「由新到旧」传入。 */
    fun calc(samples: List<Long>): Result {
        val recent = samples.filter { it > 0 }.take(SAMPLES)
        if (recent.isEmpty()) return Result(0L, 0)
        val vals = recent.sorted()
        val n = vals.size
        val median = if (n % 2 == 1) vals[n / 2] else (vals[n / 2 - 1] + vals[n / 2]) / 2
        val mean = vals.sum() / n
        val raw = median * 0.8 + mean * 0.2
        return Result(Math.round(raw / 60_000.0) * 60_000L, n)
    }

    fun ms(samples: List<Long>): Long = calc(samples).ms
}
