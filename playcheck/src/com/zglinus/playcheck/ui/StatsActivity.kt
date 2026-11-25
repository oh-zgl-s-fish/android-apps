package com.zglinus.playcheck.ui

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.zglinus.playcheck.R
import com.zglinus.playcheck.core.*
import com.zglinus.playcheck.data.Store
import com.zglinus.playcheck.engine.Aggregator
import com.zglinus.playcheck.engine.KeepAlive
import com.zglinus.playcheck.engine.Notifier
import com.zglinus.playcheck.engine.Schedule
import com.zglinus.playcheck.platform.AndroidUsage

class StatsActivity : BaseActivity() {

    override fun buildScreen(): View = screenStats()

// ---------------- 统计 ----------------

    private fun screenStats(): LinearLayout {
        val box = column()
        box.addView(headerRow("STATS", "统计", false, back = true))
        val rules = Store.rules()
        val streak = runCatching { Store.streak(System.currentTimeMillis(), rules.refreshHour) }.getOrDefault(0)

        val head = filledCard(0xFF2C6A4E.toInt())
        head.addView(label("连续打卡", 12f, 0xFFCCFFFFFF.toInt(), true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        row.addView(label("$streak", 44f, Color.WHITE, true))
        row.addView(label("天", 14f, 0xFFCCFFFFFF.toInt()).apply { setPadding(dp(4f), 0, 0, dp(6f)) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(label("近 30 天 ${monthDone()} 天达标", 11f, 0xFFCCFFFFFF.toInt()))
        head.addView(row)
        box.addView(head)

        val heat = card()
        heat.addView(label("打卡日历", 13f, T.ON_SURFACE, true))
        heat.addView(Heatmap(this).apply { levels = heatLevels(); todayIndex = 34 },
            LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(heat)

        val rates = card()
        rates.addView(label("各游戏达标率 · 近 30 天", 13f, T.ON_SURFACE, true))
        Store.games().forEach { g ->
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f), 0, 0)
            }
            row2.addView(label(g.label.ifEmpty { g.pkg }, 12f, T.ON_SURFACE), LinearLayout.LayoutParams(dp(96f), WRAP))
            row2.addView(com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
                max = 100; progress = rate30(g)
                trackCornerRadius = dp(4f)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10f) }
            })
            row2.addView(label("${rate30(g)}%", 11f, T.ON_VAR))
            rates.addView(row2)
        }
        box.addView(rates)

        // 预估明细：把「预估合计」拆开，能逐项核对
        val now = System.currentTimeMillis()
        val ests = com.zglinus.playcheck.engine.Estimates.all(this, rules, now)
        val estTotal = verdicts.sumOf { ests[it.game.pkg]?.ms ?: 0L }
        val ec = card()
        ec.addView(label("预估合计 ${Fmt.duration(estTotal)}", 13f, T.ON_SURFACE, true))
        val noSample = verdicts.count { (ests[it.game.pkg]?.samples ?: 0) == 0 }
        ec.addView(label(
            buildString {
                append("近 20 天记录 · 0.8×中位 + 0.2×均值 · 不含今天")
                append("\n样本按每日 ${rules.refreshHour}:00 换日，含次日 0–${rules.refreshHour} 点")
                if (noSample > 0) append("\n$noSample 个游戏没有样本，合计只统计有样本的")
            },
            11f, T.ON_VAR
        ).apply { setPadding(0, dp(3f), 0, dp(6f)) })
        verdicts.forEach { v ->
            val r = ests[v.game.pkg]
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5f), 0, 0)
            }
            line.addView(label(v.game.label.ifEmpty { v.game.pkg }, 12f, T.ON_SURFACE),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            val recent = com.zglinus.playcheck.engine.Estimates.recent(this, rules, now, v.game.pkg, 3)
            val tail = when {
                r == null || r.samples == 0 -> "无样本"
                else -> "${r.samples} 次 · ${Fmt.duration(r.ms)}" +
                    recent.firstOrNull()?.let { (k, ms) -> " · 最近 ${k.substringAfter('|')} ${Fmt.duration(ms)}" }.orEmpty()
            }
            line.addView(label(tail, 11f, T.ON_VAR))
            ec.addView(line)
            if (recent.isNotEmpty()) {
                ec.addView(label(
                    "样本：" + recent.joinToString(" · ") { (k, ms) -> "$k ${Fmt.duration(ms)}" },
                    10f, T.ON_VAR
                ).apply { setPadding(0, dp(1f), 0, dp(4f)) })
            }
        }
        box.addView(ec)
        return box
    }

    private fun monthDone(): Int {
        val rules = Store.rules(); val now = System.currentTimeMillis(); val games = Store.games()
        return (0 until 30).count { back ->
            Judge.judgeAll(Store.day(DayClock.dayKey(now - back * 86_400_000L, rules.refreshHour)), games, rules).any { it.done }
        }
    }

    private fun rate30(g: Game): Int {
        val rules = Store.rules(); val now = System.currentTimeMillis()
        val ok = (0 until 30).count { back ->
            val key = DayClock.dayKey(now - back * 86_400_000L, rules.refreshHour)
            Judge.judge(Store.day(key)[g.pkg] ?: GameDay(g.pkg), g, rules).done
        }
        return ok * 100 / 30
    }

    private fun heatLevels(): List<Int> {
        val rules = Store.rules(); val games = Store.games(); val now = System.currentTimeMillis()
        val out = ArrayList<Int>(35)
        for (i in 34 downTo 0) {
            val key = DayClock.dayKey(now - i * 86_400_000L, rules.refreshHour)
            out.add(Judge.judgeAll(Store.day(key), games, rules).count { it.done }.coerceIn(0, 3))
        }
        return out
    }

    
}
