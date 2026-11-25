package com.zglinus.installment.feature

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PlanDetailActivity : PageActivity() {

    override val navIndex = 1
    override val isTabPage = false
    private var planId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        planId = intent.getLongExtra("id", -1L)
        render()
    }

    override fun onResume() {
        super.onResume(); render()
    }

    private fun render() {
        val plan = Repo.find(planId) ?: run { finish(); return }
        showBack()
        header(
            plan.title.ifBlank { "分期详情" },
            "${plan.bank}${if (plan.tail.isBlank()) "" else " ··· ${plan.tail}"} · ${plan.method.label}"
        )
        content.removeAllViews()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                colors = intArrayOf(
                    Ui.colorOf(this@PlanDetailActivity, Ui.primaryContainer),
                    Ui.colorOf(this@PlanDetailActivity, Ui.container)
                )
                orientation = GradientDrawable.Orientation.TL_BR
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        head.addView(Ui.text(this, if (plan.finished) "已结清" else "剩余应还", 11f, Ui.onPrimaryContainer))
        val amount = Ui.text(this, Ui.money(plan.remaining), 31f, Ui.onPrimaryContainer, bold = true)
        head.addView(amount)
        if (!plan.finished) Anim.countUp(amount, plan.remaining)
        head.addView(
            Ui.text(this, "${plan.periods} 期 · 每期 ${Ui.amount(plan.perPay)} · 起期 ${plan.startDate}", 11f, Ui.onPrimaryContainer)
        )
        if (plan.note.isNotBlank()) {
            head.addView(
                Ui.text(this, "备注：${plan.note}", 10.5f, Ui.onPrimaryContainer).apply {
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
        val prog = Ui.row(this).apply { setPadding(0, dp(10), 0, 0) }
        prog.addView(Ui.progressBar(this, plan.paidPeriods.toDouble() / plan.periods), LinearLayout.LayoutParams(0, dp(7), 1f))
        prog.addView(Ui.text(this, "  ${plan.paidPeriods} / ${plan.periods} 期", 10f, Ui.onPrimaryContainer, bold = true))
        head.addView(prog)
        val next = Ui.row(this).apply {
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Ui.colorOf(this@PlanDetailActivity, Ui.container))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val first = Calc.firstDue(plan.startDate, plan.statementDay, plan.dueDay)
        val dates = Calc.dueDates(first, plan.periods)
        val nextDue = dates.getOrNull(plan.paidPeriods)
        next.addView(
            Ui.text(
                this,
                if (nextDue != null) "◷  下次还款 ${nextDue.monthValue} 月 ${nextDue.dayOfMonth} 日" else "◷  全部结清",
                11f, Ui.onSurface
            )
        )
        next.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        next.addView(Ui.text(this, if (nextDue != null) Ui.amount(plan.perPay) else "—", 12f, Ui.onSurface, bold = true))
        head.addView(next)
        add(head)

        val three = Ui.row(this)
        listOf(
            "本金余额" to Ui.amount(plan.perPrincipal * plan.remainPeriods),
            "待摊利息" to Ui.amount(plan.perInterest * plan.remainPeriods),
            "折算年化" to Ui.pct(plan.apr)
        ).forEachIndexed { i, (k, v) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(11), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Ui.colorOf(this@PlanDetailActivity, Ui.container))
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { rightMargin = dp(8) }
            }
            cell.addView(Ui.text(this, k, 9f, Ui.onVar))
            cell.addView(Ui.text(this, v, 13f, Ui.onSurface, bold = true))
            three.addView(cell)
            Anim.staggerIn(cell, i)
        }
        add(three)

        // 还款时间线：真实推导，不再写死
        val section = Ui.row(this).apply { setPadding(dp(4), dp(12), dp(4), dp(6)) }
        section.addView(Ui.text(this, "还款计划", 13f, Ui.onSurface, bold = true))
        section.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        section.addView(Ui.text(this, "全部 ${plan.periods} 期 ›", 10f, Ui.primary, bold = true).apply {
            setOnClickListener { NavHolder.nav.openSchedule(plan.id) }
        })
        add(section)

        val from = (plan.paidPeriods - 1).coerceAtLeast(0)
        val to = (plan.paidPeriods + 4).coerceAtMost(dates.size - 1)
        for (i in from..to) {
            val done = i < plan.paidPeriods
            val isNext = i == plan.paidPeriods
            val r = Ui.row(this).apply { setPadding(dp(2), dp(8), dp(2), dp(8)) }
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(
                        Ui.colorOf(
                            this@PlanDetailActivity,
                            if (done || isNext) Ui.primary else Ui.outlineVariant
                        )
                    )
                }
            }
            r.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val d = dates[i]
            col.addView(
                Ui.text(
                    this, "第 ${i + 1} 期 · ${d.monthValue} 月 ${d.dayOfMonth} 日",
                    12f, if (done) Ui.onVar else Ui.onSurface, bold = !done
                )
            )
            col.addView(
                Ui.text(
                    this,
                    when {
                        done -> "已还清"
                        isNext -> if (Store.autoPay) "待还 · 自动还款已开启" else "待还"
                        else -> "待还 · 还剩 ${plan.periods - i} 期"
                    },
                    9f, Ui.onVar
                )
            )
            r.addView(col)
            r.addView(Ui.text(this, Ui.amount(plan.perPay), 12f, if (done) Ui.onVar else Ui.onSurface, bold = !done))
            add(r)
        }

        if (!plan.finished) {
            // 还款方式：可改，改完按新方式重算逐期
        val methodRow = Ui.row(this).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                val labels = Method.values().map { it.label }.toTypedArray()
                val idx = Method.values().indexOf(plan.method)
                MaterialAlertDialogBuilder(this@PlanDetailActivity)
                    .setTitle("还款方式")
                    .setSingleChoiceItems(labels, idx) { d, which ->
                        val m = Method.values()[which]
                        if (m != plan.method) {
                            val np = plan.recalcWith(m)
                            Repo.replace(np)
                            Store.save(this@PlanDetailActivity)
                            render()
                        }
                        d.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        methodRow.addView(Ui.text(this, "还款方式", 11f, Ui.onVar),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        methodRow.addView(Ui.text(this, plan.method.label + "  ›", 11f, Ui.primary, bold = true))
        add(methodRow)

        val actions = Ui.row(this).apply { setPadding(0, dp(16), 0, 0) }
            fun button(label: String, filled: Boolean): View =
                Ui.text(this, label, 13f, if (filled) Ui.onPrimary else Ui.primary, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat()
                        if (filled) setColor(Ui.colorOf(this@PlanDetailActivity, Ui.primary))
                        else {
                            setColor(0x00000000)
                            setStroke(dp(1), Ui.colorOf(this@PlanDetailActivity, Ui.outlineVariant))
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { rightMargin = dp(8) }
                }
            actions.addView(button("提前结清", false).apply {
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@PlanDetailActivity)
                        .setTitle("提前结清")
                        .setMessage("将把剩下的 ${plan.remainPeriods} 期一次性结清（${Ui.money(plan.remaining)}），这笔计划转入「已结束」。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确认结清") { _, _ ->
                            Repo.settle(plan)
                            Store.save(this@PlanDetailActivity)
                            Toast.makeText(this@PlanDetailActivity, "已结清", Toast.LENGTH_SHORT).show()
                            render()
                        }
                        .show()
                }
            })
            actions.addView(button("立即还款", true).apply {
                setOnClickListener {
                    val updated = Repo.payNext(plan)
                    Store.save(this@PlanDetailActivity)
                    Toast.makeText(
                        this@PlanDetailActivity,
                        if (updated.finished) "第 ${plan.periods} 期已还，计划完成" else "已还第 ${plan.paidPeriods + 1} 期",
                        Toast.LENGTH_SHORT
                    ).show()
                    render()
                }
            })
            add(actions)
        }
    }
}
