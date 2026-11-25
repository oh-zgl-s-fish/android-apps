package com.zglinus.playcheck.engine

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.zglinus.playcheck.data.Store

/**
 * 前台服务：只为“推送准点”保活，不轮询使用记录（查询仍走 30 分钟批量）。
 * 通知走 LOW 渠道，可被用户单独静音；停掉服务就退回 WorkManager/Alarm 兜底。
 */
class KeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = Notifier.ongoing(this, emptyList(), System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_ONGOING, n)
        }
        Thread {
            runCatching { Aggregator.settle(this) ; Schedule.alert22(this) }
        }.start()
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            Store.init(ctx)
            if (!Store.keepAlive()) return
            runCatching {
                val i = Intent(ctx, KeepAlive::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, KeepAlive::class.java)) }
        }
    }
}
