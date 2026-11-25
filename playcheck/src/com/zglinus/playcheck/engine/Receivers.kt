package com.zglinus.playcheck.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.zglinus.playcheck.ui.MainActivity

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { Aggregator.settle(ctx) } catch (t: Throwable) { t.printStackTrace() } finally { pending.finish() }
        }.start()
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { Aggregator.alertIfNeeded(ctx) } catch (t: Throwable) { t.printStackTrace() } finally {
                Schedule.alert22(ctx)
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Schedule.ensure(ctx)
        KeepAlive.start(ctx)
        val pending = goAsync()
        Thread {
            try { Aggregator.settle(ctx) } catch (t: Throwable) { t.printStackTrace() } finally { pending.finish() }
        }.start()
    }
}

/** 通知里的“手动完成”，与列表里的按钮走同一段逻辑。 */
class ManualReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_MANUAL) return
        val pkg = intent.getStringExtra(Notifier.EXTRA_PKG) ?: return
        Aggregator.manual(ctx, pkg)
        ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(Notifier.ID_ALERT)
    }
}
