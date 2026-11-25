package com.zglinus.playcheck.engine

import android.content.Context
import android.util.Log
import com.zglinus.playcheck.core.*
import com.zglinus.playcheck.data.Store
import com.zglinus.playcheck.platform.AndroidUsage
import com.zglinus.playcheck.widget.CheckWidget

/**
 * 批量汇总：一次区间查询，合并进当天记录，重新判定，刷新通知与小部件。
 * 由 30 分钟闹钟、开机、界面下拉、手动补卡共同触发；不驻留线程。
 */
object Aggregator {
    private const val TAG = "PlayCheck"

    fun settle(ctx: Context, now: Long = System.currentTimeMillis()): List<Verdict> {
        Store.init(ctx)
        val rules = Store.rules()
        val games = Store.games()
        val src = AndroidUsage(ctx)
        val day = DayClock.dayKey(now, rules.refreshHour)
        val from = DayClock.windowStart(now, rules.refreshHour)
        val existing = Store.day(day)

        var labelsChanged = false
        val resolved = games.map { g ->
            if (g.label.isEmpty()) {
                val l = src.label(g.pkg)
                if (!l.isNullOrEmpty()) { labelsChanged = true; g.copy(label = l) } else g
            } else g
        }
        if (labelsChanged) Store.saveGames(resolved)

        for (g in resolved) {
            val fresh = src.spans(g.pkg, from, now)
            val old = existing[g.pkg]?.spans ?: emptyList()
            // 当天这一段以“现场查询”为准：查询出数据就整段替换，不再和旧数据取并集。
            // 并集只会保留更大的结束点，旧版本把尾巴补到 now 攒下的虚高时长就永远退不回去。
            // 查询为空时保留旧值，避免一次读不到（权限被撤/OEM 清事件）就把当天清零。
            val spans = if (fresh.isNotEmpty()) Spans.merge(fresh) else Spans.merge(old)
            // 总时长用系统聚合（04:00 的边界在 UsageSource 里扣过）；spans 只留给时间轴/热力图
            val total = src.foregroundMs(g.pkg, from, now, rules.refreshHour)
            Store.saveDay(day, GameDay(g.pkg, spans, existing[g.pkg]?.manualAt, total))
        }

        val verdicts = Judge.judgeAll(Store.day(day), resolved, rules)
        Store.lastSettle(now)
        Log.i(TAG, "settle day=$day " + verdicts.joinToString { "${it.game.label}:${it.state}:${it.totalMs / 60000}m" })

        runCatching { Notifier.post(ctx, Notifier.ongoing(ctx, verdicts, now), Notifier.ID_ONGOING) }
        runCatching { CheckWidget.refreshAll(ctx, verdicts, now) }
        return verdicts
    }

    /** 22:00 检查：只给未达标的发一条，当天只发一次。 */
    fun alertIfNeeded(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val verdicts = settle(ctx, now)
        val rules = Store.rules()
        val day = DayClock.dayKey(now, rules.refreshHour)
        if (Store.lastAlertDay() == day) return false
        val missing = verdicts.filter { !it.done }
        if (missing.isEmpty()) return false
        val target = DayClock.nextAt(22, 0, now - 60_000L)
        val delayed = ((now - target) / 60_000L).coerceAtLeast(0)
        Notifier.post(ctx, Notifier.alert(ctx, missing, delayed), Notifier.ID_ALERT)
        Store.lastAlertDay(day)
        return true
    }

    fun manual(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()) {
        Store.init(ctx)
        val rules = Store.rules()
        val day = DayClock.dayKey(now, rules.refreshHour)
        Store.markManual(day, pkg, now)
        settle(ctx, now)
    }
}
