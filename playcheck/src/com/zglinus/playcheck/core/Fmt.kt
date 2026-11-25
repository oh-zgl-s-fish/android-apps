package com.zglinus.playcheck.core

object Fmt {
    fun mmss(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    /** 时长：<1h 用 mm:ss，>=1h 用 h:mm:ss。别拿 hhmm 当时长用——那是时刻。 */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, total % 3600 / 60, total % 60)
        else String.format("%02d:%02d", total / 60, total % 60)
    }

    fun minutes(ms: Long): String = "${(ms / 60_000).toInt()} 分"

    fun hhmm(ts: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }
}
