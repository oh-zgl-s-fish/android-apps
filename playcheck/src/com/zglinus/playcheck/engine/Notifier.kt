package com.zglinus.playcheck.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zglinus.playcheck.R
import com.zglinus.playcheck.core.*
import com.zglinus.playcheck.ui.MainActivity

/**
 * 两条渠道分开：常驻汇总（LOW，可单独静音）与漏卡提醒（DEFAULT，允许响）。
 * 文案里带“延迟 N 分钟”，迟到不装作准点。
 */
object Notifier {
    const val CH_ONGOING = "ongoing"
    const val CH_ALERT = "alert"
    const val ID_ONGOING = 1001
    const val ID_ALERT = 1002
    const val ACTION_MANUAL = "com.zglinus.playcheck.MANUAL"
    const val EXTRA_PKG = "pkg"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, ctx.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.channel_ongoing_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, ctx.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ctx.getString(R.string.channel_alert_desc)
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ongoing(ctx: Context, verdicts: List<Verdict>, settledAt: Long): Notification {
        val done = verdicts.count { it.done }
        val running = verdicts.filter { it.state == State.RUNNING }.maxByOrNull { it.totalMs }
        val body = buildString {
            append("今日 ")
            append(done).append("/").append(verdicts.size).append(" 已打卡")
            running?.let {
                append(" · ").append(it.game.label.ifEmpty { it.game.pkg })
                append(" 还差 ").append((it.remainingMs / 60_000L).coerceAtLeast(1)).append(" 分")
            }
            append(" · ").append(Fmt.hhmm(settledAt)).append(" 汇总")
        }
        val b = Notification.Builder(ctx, CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(
                when {
                    verdicts.isEmpty() -> "正在整理今日打卡"
                    done == verdicts.size -> "今日全部达标"
                    else -> "今日 $done/${verdicts.size} 已打卡"
                }
            )
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setCategory(Notification.CATEGORY_STATUS)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /** 漏卡提醒：为第一个未达标的游戏挂“手动完成”动作。 */
    fun alert(ctx: Context, missing: List<Verdict>, delayedMinutes: Long): Notification {
        val first = missing.first()
        val label = first.game.label.ifEmpty { first.game.pkg }
        val body = buildString {
            append("前台 ").append(first.totalMs / 60_000L).append(" 分，差 ")
            append((first.remainingMs / 60_000L).coerceAtLeast(1)).append(" 分。")
            append("24:00 前可手动完成。")
            if (delayedMinutes >= 2) append("（延迟 ").append(delayedMinutes).append(" 分钟）")
            if (missing.size > 1) append(" 另有 ").append(missing.size - 1).append(" 个未达标。")
        }
        val manual = Intent(ctx, ManualReceiver::class.java)
            .setAction(ACTION_MANUAL)
            .putExtra(EXTRA_PKG, first.game.pkg)
        val piManual = PendingIntent.getBroadcast(
            ctx, 1, manual, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$label 今天还没打卡")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .addAction(Notification.Action.Builder(null, "手动完成", piManual).build())
            .build()
    }

    fun post(ctx: Context, n: Notification, id: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
    }
}
