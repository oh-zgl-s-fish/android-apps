package com.zglinus.playcheck.core

/**
 * 纯函数判定：输入证据与规则，输出状态。无 IO、无 Android，可单测。
 * 语义：打开过 + 前台累计 >= 门槛 => DONE；手动补卡 => MANUAL；其余按有无区间区分。
 */
object Judge {
    fun judge(day: GameDay, game: Game, rules: Rules): Verdict {
        val merged = Spans.merge(day.spans)
        // 总数优先用系统聚合（Aggregator 写进 totalMs），没有才退回按段求和
        val total = if (day.totalMs >= 0L) day.totalMs else merged.sumOf { it.duration }
        val first = merged.minByOrNull { it.start }?.start
        val threshold = rules.thresholdMin.coerceAtLeast(1) * 60_000L

        val state = when {
            day.manualAt != null -> State.MANUAL
            total >= threshold -> State.DONE
            total > 0L -> State.RUNNING
            else -> State.NOT_OPENED
        }
        return Verdict(game, state, total, first, threshold, day.manualAt)
    }

    fun judgeAll(days: Map<String, GameDay>, games: List<Game>, rules: Rules): List<Verdict> =
        games.filter { it.enabled }.map { g -> judge(days[g.pkg] ?: GameDay(g.pkg), g, rules) }
}
