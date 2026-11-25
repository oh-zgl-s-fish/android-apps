package com.zglinus.installment.feature

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*

class PlansActivity : PageActivity() {

    override val navIndex = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        showFab()
        header("分期计划", "在还 ${Repo.active.size} 笔 · 已结束 ${Repo.ended.size} 笔")
        content.removeAllViews()

        // 顶部 KPI：待还 / 已付总利息 / 剩余期数 / 综合年化
        val kpi = Ui.card(this, 16f, 14f)
        val row = Ui.row(this)
        data class Cell(val k: String, val v: String)
        listOf(
            Cell("待还", Ui.amount(Repo.remainingTotal)),
            Cell("已付总利息", Ui.amount(Repo.paidInterestTotal)),
            Cell("剩余期数", "${Repo.active.sumOf { it.remainPeriods }} 期"),
            Cell("综合年化", Ui.pct(Repo.weightedApr))
        ).forEachIndexed { i, c ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(Ui.text(this, c.k, 9.5f, Ui.onVar))
            val v = Ui.text(this, c.v, if (i == 1) 15f else 14f, if (i == 1) Ui.primary else Ui.onSurface, bold = true)
            cell.addView(v)
            row.addView(cell)
            Anim.staggerIn(cell, i)
        }
        kpi.addView(row)
        kpi.setOnClickListener { NavHolder.nav.openBills() }
        add(kpi)

        if (Repo.active.isEmpty() && Repo.ended.isEmpty()) {
            val empty = Ui.card(this, 16f, 20f)
            empty.addView(Ui.text(this, "还没有分期计划", 13f, Ui.onSurface, bold = true))
            empty.addView(
                Ui.text(this, "右上角 ＋ 新建一笔，或用 AI 拍账单自动识别。", 11f, Ui.onVar)
                    .apply { setPadding(0, dp(4), 0, 0) }
            )
            add(empty)
            return
        }

        if (Repo.active.isNotEmpty()) {
            add(Ui.sectionHeader(this, "在还", "点击看每期明细"))
            Repo.active.forEachIndexed { i, p -> add(planCard(p)).also { Anim.staggerIn(content.getChildAt(content.childCount - 1), i + 4) } }
        }

        add(Ui.sectionHeader(this, "已结束", "不计入待还"))
        Repo.ended.forEach { p -> add(planCard(p)) }
    }

    private fun planCard(p: Plan): View {
        val card = Ui.card(this, 14f, 12f)
        val top = Ui.row(this)
        val name = p.title.ifBlank { "${p.bank} ${p.tail}" }
        top.addView(Ui.text(this, name, 13f, if (p.finished) Ui.onVar else Ui.onSurface, bold = true))
        top.addView(Ui.vspace(this, 2f).apply { layoutParams = LinearLayout.LayoutParams(dp(7), 1) })
        top.addView(
            Ui.text(this, if (p.finished) "已结束" else "在还", 9f, if (p.finished) Ui.onVar else Ui.onSecondaryContainer)
                .apply {
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Ui.colorOf(this@PlansActivity, if (p.finished) Ui.container else Ui.secondaryContainer))
                    }
                }
        )
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(
            Ui.text(
                this,
                if (p.finished) "已结清" else "年化 ${Ui.pct(p.apr)}",
                10f, if (p.finished) Ui.onVar else Ui.primary, bold = true
            )
        )
        card.addView(top)

        card.addView(
            Ui.text(
                this,
                "${p.periods} 期 · ${p.method.label} · 已还 ${p.paidPeriods} 期 · 每期 ${Ui.amount(p.perPay)}",
                10f, Ui.onVar
            ).apply { setPadding(0, dp(3), 0, dp(8)) }
        )

        card.addView(Ui.progressBar(this, p.paidPeriods.toDouble() / p.periods))

        val foot = Ui.row(this)
        foot.setPadding(0, dp(7), 0, 0)
        foot.addView(
            Ui.text(
                this,
                if (p.finished) "利息 ${Ui.amount(p.totalInterest)}" else "已付利息 ${Ui.amount(p.paidInterest)} · 剩 ${p.remainPeriods} 期",
                10f, Ui.onVar
            )
        )
        foot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        foot.addView(Ui.text(this, if (p.finished) "¥0.00" else Ui.money(p.remaining), 12f, Ui.onSurface, bold = true))
        card.addView(foot)

        card.setOnClickListener { NavHolder.nav.openPlanDetail(p.id) }
        return card
    }
}
