package com.zglinus.playcheck.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.zglinus.playcheck.R
import com.zglinus.playcheck.core.*
import com.zglinus.playcheck.data.Store
import com.zglinus.playcheck.engine.Estimates
import com.zglinus.playcheck.ui.MainActivity

/** 4×2 小部件：只读汇总结果，不自己查 UsageStats；点一下进详情。 */
class CheckWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        val rules = Store.rules()
        val now = System.currentTimeMillis()
        val day = DayClock.dayKey(now, rules.refreshHour)
        val verdicts = Judge.judgeAll(Store.day(day), Store.games(), rules)
        ids.forEach { id -> mgr.updateAppWidget(id, build(ctx, verdicts, now)) }
    }

    companion object {
        fun refreshAll(ctx: Context, verdicts: List<Verdict>, now: Long) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, CheckWidget::class.java))
                ids.forEach { id -> mgr.updateAppWidget(id, build(ctx, verdicts, now)) }
            }
        }

        private fun build(ctx: Context, verdicts: List<Verdict>, now: Long): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_checkin)
            val rules = Store.rules()
            val estimates = Estimates.all(ctx, rules, now)
            val done = verdicts.count { it.done }
            val played = verdicts.sumOf { it.totalMs }
            val est = verdicts.sumOf { estimates[it.game.pkg]?.ms ?: 0L }

            rv.setTextViewText(R.id.w_title, if (done == verdicts.size) "全部打卡" else "没打开")
            rv.setTextViewText(R.id.w_count, "$done/${verdicts.size}")

            // 左：未打开，最多 5 个
            val rows = intArrayOf(R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4, R.id.w_row5)
            val dots = intArrayOf(R.id.w_dot1, R.id.w_dot2, R.id.w_dot3, R.id.w_dot4, R.id.w_dot5)
            val names = intArrayOf(R.id.w_name1, R.id.w_name2, R.id.w_name3, R.id.w_name4, R.id.w_name5)
            val pending = verdicts.filter { it.state == State.NOT_OPENED }
            rows.indices.forEach { i ->
                val v = pending.getOrNull(i)
                if (v == null) {
                    rv.setViewVisibility(rows[i], View.GONE)
                } else {
                    rv.setViewVisibility(rows[i], View.VISIBLE)
                    rv.setImageViewResource(dots[i], R.drawable.widget_dot_todo)
                    rv.setTextViewText(names[i], v.game.label.ifEmpty { v.game.pkg })
                }
            }

            // 右：两个环，进度与预估总时长写在环中间；预估环用图标主题色
            val ringPx = dp(ctx, 46)
            rv.setImageViewBitmap(
                R.id.w_ring1,
                Rings.render(ringPx, if (verdicts.isEmpty()) 0f else done.toFloat() / verdicts.size, 0xFF2C6A4E.toInt())
            )
            rv.setTextViewText(R.id.w_ring1Center, "$done/${verdicts.size}")

            val estColor = themeColor(ctx, verdicts)
            rv.setImageViewBitmap(
                R.id.w_ring2,
                Rings.render(ringPx, if (est > 0) played.toFloat() / est.coerceAtLeast(1L) else 0f, estColor)
            )
            rv.setTextViewText(R.id.w_ring2Center, if (est > 0) Fmt.duration(est) else "--:--")

            val i = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.w_root, pi)
            return rv
        }

        /** 多个游戏时取各自图标主题色的平均，代表"这一次的预估"用色。 */
        private fun themeColor(ctx: Context, verdicts: List<Verdict>): Int {
            if (verdicts.isEmpty()) return 0xFF2C6A4E.toInt()
            var r = 0L; var g = 0L; var b = 0L
            verdicts.forEach {
                val c = com.zglinus.playcheck.ui.IconColors.of(ctx, it.game.pkg)
                r += android.graphics.Color.red(c)
                g += android.graphics.Color.green(c)
                b += android.graphics.Color.blue(c)
            }
            val n = verdicts.size
            return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }

        private fun dp(ctx: Context, v: Int): Int =
            (v * ctx.resources.displayMetrics.density).toInt()
    }
}
