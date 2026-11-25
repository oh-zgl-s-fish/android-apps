package com.zglinus.installment.feature

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*

/** 每月可支配（MD3） */
class MonthlyActivity : PageActivity() {

    override val navIndex = 0
    override val isTabPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showBack()
        header("每月可支配", "")

        val data = Repo.seriesDetail(12)
        val thisMonth = data.firstOrNull()

        // 本月可支配
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Ui.colorOf(this@MonthlyActivity, Ui.primaryContainer))
            }
        }
        hero.addView(Ui.text(this, "本月可支配", 11f, Ui.onPrimaryContainer))
        hero.addView(
            Ui.text(this, thisMonth?.third?.let { Ui.money(it) } ?: "—", 26f, Ui.onPrimaryContainer, bold = true)
                .apply { setPadding(0, dp(4), 0, dp(2)) }
        )
        val sub = Ui.row(this)
        sub.addView(Ui.text(this, "还款 ${thisMonth?.second?.let { Ui.amount(it) } ?: "0"}", 10.5f, Ui.onPrimaryContainer))
        sub.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        sub.addView(Ui.text(this, "固定 ${Ui.amount(Repo.fixedExpense)}", 10.5f, Ui.onPrimaryContainer))
        sub.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        sub.addView(Ui.text(this, "收入 ${Ui.amount(Repo.incomeTotal)}", 10.5f, Ui.onPrimaryContainer))
        hero.addView(sub)
        add(hero)

        // 走势
        val chart = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(20f).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Ui.colorOf(this@MonthlyActivity, Ui.container))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        val chartInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        chartInner.addView(Ui.text(this, "12 个月可支配", 11f, Ui.onVar))
        chartInner.addView(TrendView(this).apply {
            points = data.map { it.first to it.third }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
        })
        chart.addView(chartInner)
        add(chart)

        // 明细表
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(20f).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Ui.colorOf(this@MonthlyActivity, Ui.container))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val head = Ui.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Ui.colorOf(this@MonthlyActivity, Ui.primaryContainer))
            }
        }
        listOf("月份" to 0.8f, "还款" to 1.1f, "固定" to 1.05f, "可支配" to 1.2f).forEachIndexed { idx, (t, w) ->
            val tv = Ui.text(this, t, 9.5f, Ui.onPrimaryContainer, bold = true)
            if (idx > 0) tv.gravity = Gravity.END
            head.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w))
        }
        inner.addView(head)

        data.forEachIndexed { i, row ->
            val r = Ui.row(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(9), dp(8), dp(9))
                if (i % 2 == 1) background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Ui.colorOf(this@MonthlyActivity, Ui.onSurface) and 0x14FFFFFF)
                }
            }
            val month = Ui.text(this, row.first, 10f, Ui.onSurface)
            val repay = Ui.text(this, Ui.plain(row.second), 10f, Ui.onVar).apply { gravity = Gravity.END }
            val fixed = Ui.text(this, Ui.plain(Repo.fixedExpense), 10f, Ui.onVar).apply { gravity = Gravity.END }
            val disp = Ui.text(this, Ui.plain(row.third), 10.5f, Ui.onSurface, bold = true).apply { gravity = Gravity.END }
            r.addView(month, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
            r.addView(repay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            r.addView(fixed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f))
            r.addView(disp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            inner.addView(r)
        }

        card.addView(inner)
        add(card)
    }
}
