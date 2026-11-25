package com.zglinus.playcheck.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zglinus.playcheck.core.DayClock
import com.zglinus.playcheck.data.Store

/**
 * 定时：30 分钟一次批量汇总（不精确，允许被系统推迟），22:00 一次精确检查。
 * 关掉常驻通知后，22:00 仍由这里兜底，只是可能晚。
 */
object Schedule {
    const val ACTION_TICK = "com.zglinus.playcheck.TICK"
    const val ACTION_ALERT = "com.zglinus.playcheck.ALERT"

    fun every30(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 11,
            Intent(ctx, TickReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 60_000L,
            30L * 60_000L, pi)
    }

    fun alert22(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = DayClock.nextAt(22, 0, System.currentTimeMillis())
        val pi = PendingIntent.getBroadcast(
            ctx, 12,
            Intent(ctx, AlertReceiver::class.java).setAction(ACTION_ALERT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(PendingIntent.getBroadcast(ctx, 11, Intent(ctx, TickReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
        am.cancel(PendingIntent.getBroadcast(ctx, 12, Intent(ctx, AlertReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
    }

    fun ensure(ctx: Context) {
        Store.init(ctx)
        every30(ctx)
        alert22(ctx)
    }
}
