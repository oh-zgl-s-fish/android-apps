package com.zglinus.playcheck.platform

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.zglinus.playcheck.core.Span
import com.zglinus.playcheck.core.Spans

/** 使用记录的数据源抽象。UI 与引擎只认这个接口，换实现不影响判定。 */
interface UsageSource {
    fun spans(pkg: String, from: Long, to: Long): List<Span>

    /** 区间内的前台总时长（毫秒）。默认退化成按段求和；实现方应优先用系统聚合。 */
    fun foregroundMs(pkg: String, from: Long, to: Long, refreshHour: Int): Long =
        spans(pkg, from, to).sumOf { it.duration }
    fun installed(pkgs: List<String>): Set<String>
    fun icon(pkg: String): Drawable?
    fun label(pkg: String): String?
}

/**
 * UsageStatsManager 实现：一次区间查询，本地聚合，不按分钟轮询。
 * 息屏（SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN）与切后台都按“段结束”处理。
 */
class AndroidUsage(
    private val ctx: Context,
    private val minSegmentMs: Long = 60_000L
) : UsageSource {

    private val usm: UsageStatsManager? =
        ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    fun hasPermission(): Boolean {
        if (usm == null) return false
        val to = System.currentTimeMillis()
        val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, to - 86_400_000L, to)
        return !list.isNullOrEmpty()
    }

    override fun spans(pkg: String, from: Long, to: Long): List<Span> {
        val u = usm ?: return emptyList()
        if (to <= from) return emptyList()
        val events = try { u.queryEvents(from, to) } catch (t: Throwable) { return emptyList() }
        val e = UsageEvents.Event()
        var open = 0L
        var lastFg: String? = null          // 窗口内最后一个进入前台的包
        var firstOther = 0L                 // open 之后第一个“别的包抢到前台”的 RESUMED = 本段结束的上界
        val resumedActs = HashSet<String>() // 本包当前处于 RESUMED 的 Activity
        val out = ArrayList<Span>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val ts = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // 只有“别的包被 RESUMED”才算本段结束的上界。KEYGUARD/STANDBY/SCREEN 这类全局事件
                    // 的 package 是 null，早先按“任意别的包事件”取上界，会把刚打开 1 秒就切走，
                    // 整段因此被 <60s 的门槛丢掉——真机上表现为首开 --:--、记录 0 段。
                    if (e.packageName != pkg && open != 0L && firstOther == 0L) firstOther = ts
                    lastFg = e.packageName
                    if (e.packageName == pkg) {
                        // 同包多 Activity：只有全部暂停才算这一段结束（否则切界面会被提前截段）
                        if (resumedActs.isEmpty()) open = ts
                        resumedActs.add(e.className ?: "")
                    }
                }
                // 真机上（HyperOS 17）：米哈游系游戏切走时只发 ACTIVITY_STOPPED、没有 ACTIVITY_PAUSED，
                // 只认 PAUSED 会让这段永远不结束 → 首开 / 打开记录全丢。STOPPED 同样按“段结束”处理。
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (e.packageName == pkg && resumedActs.isNotEmpty()) {
                        resumedActs.remove(e.className ?: "")
                        if (resumedActs.isEmpty() && open != 0L) { close(out, open, ts); open = 0L }
                    }
                    if (lastFg == e.packageName) lastFg = null
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    if (open != 0L) { close(out, open, ts); open = 0L }
                    resumedActs.clear()
                    lastFg = null
                }
            }
        }
        if (open != 0L) {
            // 收尾口径：只有“这个包确实是窗口里最后进入前台的那个”且屏幕可交互，才把尾巴补到 now（在玩，实时涨）。
            // 旧逻辑只看设备状态（屏幕亮着就补到 now），于是任何一次没收到 PAUSE 的会话
            // ——应用被杀、崩溃、厂商漏发——都会以 now 收口，每次刷新都继续涨，表现就是“计时停不下来”。
            val stillFront = lastFg == pkg && Keyguard.canCountNow(ctx)
            val end = when {
                stillFront -> to
                firstOther > open -> firstOther   // 之后已经有人抢了前台/熄屏 -> 到此为止
                else -> open                      // 什么证据都没有 -> 宁可不计，也不让它一直涨
            }
            if (end > open) close(out, open, end)
        }
        return Spans.merge(out).mapNotNull { it.clip(from, to) }
    }

    /**
     * 前台总时长直接用系统聚合：`queryAndAggregateUsageStats(...)[pkg].totalTimeInForeground`。
     * 之前是自己按 ACTIVITY_RESUMED/PAUSED 拼段再补尾巴，漏一次 PAUSE 就会一直涨——那套不再用于总数。
     * 唯一的偏差：系统按**本地自然日**分桶，不认 04:00 换日，所以把 00:00→refreshHour 这段扣掉
     * （只有这一小段还读事件明细）。
     */
    override fun foregroundMs(pkg: String, from: Long, to: Long, refreshHour: Int): Long {
        val u = usm ?: return spans(pkg, from, to).sumOf { it.duration }
        val agg = runCatching { u.queryAndAggregateUsageStats(from, to) }.getOrNull()
            ?: return spans(pkg, from, to).sumOf { it.duration }
        val total = agg[pkg]?.totalTimeInForeground ?: 0L
        if (refreshHour <= 0) return total
        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = from
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val cut = if (from > dayStart) spans(pkg, dayStart, from).sumOf { it.duration } else 0L
        return (total - cut).coerceAtLeast(0L)
    }

    private fun close(out: MutableList<Span>, start: Long, end: Long) {
        if (end - start >= minSegmentMs) out.add(Span(start, end))
    }

    override fun installed(pkgs: List<String>): Set<String> {
        val pm = ctx.packageManager
        return pkgs.filter {
            runCatching { pm.getApplicationInfo(it, 0) }.isSuccess
        }.toSet()
    }

    override fun icon(pkg: String): Drawable? = runCatching {
        ctx.packageManager.getApplicationIcon(pkg)
    }.getOrNull()

    override fun label(pkg: String): String? = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
