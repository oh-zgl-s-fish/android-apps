package com.zglinus.playcheck.engine

import android.content.Context
import com.zglinus.playcheck.core.*
import com.zglinus.playcheck.data.Store

/** 从已有日记录里算每个游戏的预估时长（最近 20 个自然日，不含今天）。 */
object Estimates {

    /** 自然日起点（本地 00:00） */
    private fun startOfDay(ms: Long): Long {
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    /**
     * 某个自然日内、某个游戏的总时长。
     * 04:00 换日会让一个自然日横跨两个 day key，所以两边的记录都要取，再按自然日裁剪。
     */
    private fun dayTotal(ctx: Context, rules: Rules, pkg: String, dayStart: Long): Long {
        val dayEnd = dayStart + 86_400_000L
        val keyA = DayClock.dayKey(dayStart + 60_000L, rules.refreshHour)
        val keyB = DayClock.dayKey(dayEnd - 60_000L, rules.refreshHour)
        val spans = ArrayList<Span>()
        for (k in setOf(keyA, keyB)) {
            Store.day(k)[pkg]?.spans?.forEach { spans.add(it) }
        }
        val clipped = spans.mapNotNull { it.clip(dayStart, dayEnd) }
        return Spans.total(clipped)
    }

    /** pkg → 预估结果（毫秒 + 样本数） */
    fun all(ctx: Context, rules: Rules, now: Long, days: Int = 60): Map<String, Estimate.Result> {
        val todayStart = startOfDay(now)
        val perPkg = HashMap<String, MutableList<Long>>()
        val pkgs = HashSet<String>()
        // 先收集这些天出现过哪些包
        for (i in 1..days) {
            val d0 = todayStart - i * 86_400_000L
            val keyA = DayClock.dayKey(d0 + 60_000L, rules.refreshHour)
            val keyB = DayClock.dayKey(d0 + 86_400_000L - 60_000L, rules.refreshHour)
            Store.day(keyA).keys.forEach { pkgs.add(it) }
            Store.day(keyB).keys.forEach { pkgs.add(it) }
        }
        // 由新到旧逐日取自然日总量
        for (pkg in pkgs) {
            val list = ArrayList<Long>()
            for (i in 1..days) {
                val d0 = todayStart - i * 86_400_000L
                val total = dayTotal(ctx, rules, pkg, d0)
                if (total > 0) list.add(total)
            }
            perPkg[pkg] = list
        }
        return perPkg.mapValues { (_, v) -> Estimate.calc(v) }
    }

    fun one(ctx: Context, rules: Rules, now: Long, pkg: String): Estimate.Result =
        all(ctx, rules, now)[pkg] ?: Estimate.Result(0L, 0)

    /** 某个游戏最近几天的自然日记录（由新到旧），用于核对预估。 */
    fun recent(ctx: Context, rules: Rules, now: Long, pkg: String, n: Int = 5, days: Int = 60): List<Pair<String, Long>> {
        val todayStart = startOfDay(now)
        val out = ArrayList<Pair<String, Long>>()
        for (i in 1..days) {
            if (out.size >= n) break
            val d0 = todayStart - i * 86_400_000L
            val total = dayTotal(ctx, rules, pkg, d0)
            if (total > 0) {
                val label = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(java.util.Date(d0))
                out.add(label to total)
            }
        }
        return out
    }
}
