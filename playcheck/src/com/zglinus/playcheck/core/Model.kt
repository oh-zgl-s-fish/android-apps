package com.zglinus.playcheck.core

/** 一段前台使用区间（毫秒）。core 层不依赖任何 android.*。 */
data class Span(val start: Long, val end: Long) {
    val duration: Long get() = (end - start).coerceAtLeast(0L)
    fun clip(from: Long, to: Long): Span? {
        val s = maxOf(start, from); val e = minOf(end, to)
        return if (e > s) Span(s, e) else null
    }
}

/** 被监控的游戏。pkg 是唯一键，label 允许为空（运行时用 PackageManager 补）。 */
data class Game(val pkg: String, val label: String = "", val enabled: Boolean = true)

/** 判定规则。thresholdMin 单位为分钟。 */
data class Rules(
    val thresholdMin: Int = 15,
    val refreshHour: Int = 4,
    val allowManual: Boolean = true,
    val onlyForeground: Boolean = true
)

enum class State { NOT_OPENED, RUNNING, DONE, MANUAL }

/** 一天的原始证据：区间 + 手动补卡时刻（null 表示没补）。 */
data class GameDay(
    val pkg: String,
    val spans: List<Span> = emptyList(),
    val manualAt: Long? = null,
    /** 系统给的前台总时长；-1 表示没采到，退回按 spans 求和。 */
    val totalMs: Long = -1L
)

/** 判定结果。remainingMs 只在 RUNNING 时有意义。 */
data class Verdict(
    val game: Game,
    val state: State,
    val totalMs: Long,
    val firstOpen: Long?,
    val thresholdsMs: Long,
    val manualAt: Long?
) {
    val remainingMs: Long get() = (thresholdsMs - totalMs).coerceAtLeast(0L)
    val done: Boolean get() = state == State.DONE || state == State.MANUAL
}
