package com.zglinus.playcheck.core

import java.util.Calendar

/**
 * 换日口径：每天 refreshHour 点（默认 04:00）归档，跨点熬夜不把两天并成一天。
 * 只做时间算术，不碰时区数据库以外的 Android API。
 */
object DayClock {
    fun dayKey(now: Long, refreshHour: Int): String {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.HOUR_OF_DAY, -refreshHour)
        }
        return String.format(
            "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 当天窗口的起点（refreshHour 那一刻）。 */
    fun windowStart(now: Long, refreshHour: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, refreshHour)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var t = c.timeInMillis
        if (t > now) t -= 24L * 3600_000L
        return t
    }

    fun windowEnd(now: Long, refreshHour: Int): Long = windowStart(now, refreshHour) + 24L * 3600_000L

    /** 22:00 当天的触发时刻；已过则取明天。 */
    fun nextAt(hour: Int, minute: Int, now: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }
}
