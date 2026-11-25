package com.zglinus.installment.feature

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*

class DashboardActivity : PageActivity() {

    override val navIndex = 0

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
        val now = java.time.LocalDate.now()
        header("分期", "${now.year} 年 ${now.monthValue} 月")
        content.removeAllViews()
        hero()
        nextMonthCard()
        dueBar()
        composition()
        ending()
        progress()
        trend()
        add(Ui.vspace(this, 24f))
    }

    // ---------------- 01 ----------------

    private fun hero() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                colors = intArrayOf(
                    Ui.colorOf(this@DashboardActivity, Ui.primaryContainer),
                    Ui.colorOf(this@DashboardActivity, Ui.container)
                )
                orientation = GradientDrawable.Orientation.TL_BR
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val top = Ui.row(this)
        top.addView(Ui.text(this, "本月可支配预算", 11f, Ui.onPrimaryContainer))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(
            Ui.text(this, "编辑收入", 11f, Ui.onPrimaryContainer, bold = true).apply {
                setPadding(dp(8), dp(2), dp(2), dp(2))
                setOnClickListener { incomeSheet() }
            }
        )
        card.addView(top)

        val negative = Repo.disposable < 0
        val amount = Ui.text(
            this, Ui.money(Repo.disposable), 34f,
            if (negative) Ui.error else Ui.onPrimaryContainer, bold = true
        )
        amount.setPadding(0, dp(2), 0, dp(2))
        card.addView(amount)
        Anim.countUp(amount, Repo.disposable, dur = 640)
        card.addView(
            Ui.text(
                this,
                "收入 ${Ui.plain(Repo.incomeTotal)} − 还款 ${Ui.plain(Repo.repay)} − 固定支出 ${Ui.plain(Repo.fixedExpense)}",
                11f, Ui.onPrimaryContainer
            )
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(9)
            ).apply { topMargin = dp(12) }
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(Ui.colorOf(this@DashboardActivity, Ui.outlineVariant))
            }
            clipToOutline = true
        }
        val total = Repo.incomeTotal.coerceAtLeast(1.0)
        val bars = listOf(
            Repo.repay to Ui.primary,
            Repo.fixedExpense to Ui.blend(
                Ui.colorOf(this, Ui.primary), Ui.colorOf(this, Ui.container), 0.62f
            ),
            Repo.disposable.coerceAtLeast(0.0) to Ui.colorOf(this, Ui.surface)
        )
        bars.forEach { (v, attr) ->
            bar.addView(View(this).apply {
                setBackgroundColor(attr)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                (v.coerceAtLeast(0.0) / total).toFloat().coerceIn(0.0001f, 1f)))
        }
        card.addView(bar)
        if (negative) {
            card.addView(
                Ui.text(this, "支出已超过收入，可支配为负", 10f, Ui.error)
                    .apply { setPadding(0, dp(6), 0, 0) }
            )
        }

        val legend = Ui.row(this).apply { setPadding(0, dp(7), 0, 0) }
        listOf(
            "还款 ${Ui.plain(Repo.repay)}" to Ui.primary,
            "固定支出 ${Ui.plain(Repo.fixedExpense)}" to Ui.blend(
                Ui.colorOf(this, Ui.primary), Ui.colorOf(this, Ui.container), 0.62f
            ),
            "可支配 ${Ui.plain(Repo.disposable)}" to Ui.container
        ).forEach { (label, attr) ->
            val item = Ui.row(this).apply {
                val dot = View(this@DashboardActivity).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        if (attr == Ui.colorOf(this@DashboardActivity, Ui.container)) {
                            setColor(attr)
                            setStroke(dp(1), Ui.colorOf(this@DashboardActivity, Ui.outlineVariant))
                        } else setColor(attr)
                    }
                }
                addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
                addView(Ui.text(this@DashboardActivity, " $label", 10f, Ui.onPrimaryContainer))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(12) }
            }
            legend.addView(item)
        }
        card.addView(legend.apply { setPadding(0, dp(9), 0, 0) })
        add(card)
    }

    /** 小卡片：下个月可支配预算 */
    private fun nextMonthCard() {
        val now = java.time.LocalDate.now()
        val next = now.plusMonths(1)
        val card = Ui.card(this, 14f, 11f)
        val row = Ui.row(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, "${next.monthValue} 月可支配", 10f, Ui.onVar))
        val v = Repo.nextMonthDisposable
        col.addView(
            Ui.text(this, Ui.money(v), 17f, if (v < 0) Ui.error else Ui.onSurface, bold = true)
        )
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
        }
        col2.addView(
            Ui.text(this, "还款 ${Ui.amount(Repo.nextMonthRepay)}", 9f, Ui.onVar).apply { isSingleLine = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        col2.addView(
            Ui.text(this, "固定 ${Ui.amount(Repo.fixedExpense)}", 9f, Ui.onVar).apply { isSingleLine = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        row.addView(col2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(row)
        add(card)
    }

    private fun dueBar() {
        val card = Ui.card(this, 14f, 11f)
        val row = Ui.row(this)
        // 找下一个真正有还款的那一笔（跳过已结清的）
        val today = java.time.LocalDate.now()
        var nextPlan: Plan? = null
        var nextDate: java.time.LocalDate? = null
        var nextAmount = 0.0
        Repo.active.filter { !it.finished }.forEach { p ->
            val dues = p.dues
            for (i in p.paidPeriods until p.periods) {
                val d = dues.getOrNull(i) ?: continue
                if (d < today) continue
                if (nextDate == null || d < nextDate!!) {
                    nextDate = d; nextPlan = p
                    nextAmount = if (p.amounts.isNotEmpty()) p.amounts.getOrElse(i) { p.amounts.last() }
                    else p.perPrincipal + p.perInterest
                }
                break
            }
        }
        val nd = nextDate
        if (nd == null || nextPlan == null) {
            row.addView(Ui.text(this, "没有待还", 12f, Ui.onSurface, bold = true))
        } else {
            row.addView(Ui.text(this, "${nd.monthValue} 月 ${nd.dayOfMonth} 日", 12f, Ui.onSurface, bold = true))
            val who = (nextPlan as Plan).title.ifBlank { nextPlan.bank }
            row.addView(Ui.text(this, "  $who · 应还 ${Ui.money(nextAmount)}", 11f, Ui.onVar))
        }
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Ui.text(this, "去还款", 11f, Ui.primary, bold = true).apply {
            setOnClickListener { NavHolder.nav.openPlans() }
        })
        card.addView(row)
        add(card)
    }

    private fun composition() {
        val card = Ui.card(this)
        card.addView(
            Ui.cardHeader(
                this, "分期组成",
                "合计 ${Ui.amount(Repo.remainingTotal)} · 综合年化 ${Ui.pct(Repo.weightedApr)}"
            )
        )
        // 按银行聚合（同卡多笔合并）
        val byBank = Repo.active.groupBy { it.bank }
            .map { (bank, list) -> bank to list.sumOf { it.remaining } }
            .sortedByDescending { it.second }
        val total = byBank.sumOf { it.second }.takeIf { it > 0 } ?: 1.0

        val row = Ui.row(this)
        val lightSeg = Ui.blend(
            Ui.colorOf(this, Ui.primary), Ui.colorOf(this, Ui.surface), 0.58f
        )
        val base = Ui.colorOf(this, Ui.primary)
        fun shade(i: Int) = when {
            i == 0 -> base
            else -> Ui.blend(base, Ui.colorOf(this, Ui.surface), (0.28f + 0.22f * (i - 1)).coerceAtMost(0.92f))
        }
        val segs = byBank.mapIndexed { i, (_, amt) -> DonutView.Seg(amt, shade(i)) }
            .ifEmpty { listOf(DonutView.Seg(1.0, Ui.colorOf(this, Ui.outlineVariant))) }

        row.addView(DonutView(this).apply {
            segments = segs
            centerTop = String.format("%,.0f", Repo.remainingTotal)
            centerBottom = "待还"
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
        })

        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        byBank.forEachIndexed { i, (bank, amt) ->
            val line = Ui.row(this).apply { setPadding(0, dp(5), 0, dp(5)) }
            val sw = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(shade(i))
                }
            }
            line.addView(sw, LinearLayout.LayoutParams(dp(8), dp(8)))
            line.addView(
                Ui.text(this, "  $bank  ${"%.1f".format(amt / total * 100)}%", 11f, Ui.onVar),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            line.addView(Ui.text(this, Ui.plain(amt), 11f, Ui.onSurface, bold = true))
            legend.addView(line)
        }
        row.addView(legend)
        card.addView(row)
        add(card)
    }

    private fun ending() {
        val card = Ui.card(this, 14f, 12f)
        // 优先只放 1 个月内结清的；没有才放宽到 2 个月
        var upcoming = Repo.active.filter { !it.finished && it.remainMonths <= 1 }
        if (upcoming.isEmpty()) upcoming = Repo.active.filter { !it.finished && it.remainMonths <= 2 }
        upcoming = upcoming.sortedBy { it.remainMonths }
        card.addView(
            Ui.cardHeader(
                this, "即将结束",
                if (upcoming.isEmpty()) "没有快结束的计划" else "剩 ${upcoming.size} 笔 · 按银行汇总"
            )
        )
        if (upcoming.isNotEmpty()) {
            val sumRemain = upcoming.sumOf { it.remaining }
            val earl = upcoming.minOf { p -> p.dues.getOrElse(p.periods - 1) { java.time.LocalDate.now() } }
            val later = upcoming.maxOf { p -> p.dues.getOrElse(p.periods - 1) { java.time.LocalDate.now() } }
            val summary = Ui.row(this).apply { setPadding(0, dp(2), 0, dp(6)) }
            summary.addView(Ui.text(this, "${upcoming.size} 笔 · 待还合计 ${Ui.amount(sumRemain)}", 11f, Ui.primary, bold = true))
            summary.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            summary.addView(
                Ui.text(this, if (earl == later) "$earl 全部结清" else "$earl ~ $later", 10f, Ui.onVar)
            )
            card.addView(summary)
        }
        if (upcoming.isEmpty()) {
            card.addView(Ui.text(this, "没有剩余 2 个月内结清的分期。", 11f, Ui.onVar))
        } else {
            // 按银行聚合
            val byBank = upcoming.groupBy { it.bank }
            byBank.forEach { (bank, list) ->
                val row = Ui.row(this).apply { setPadding(0, dp(8), 0, 0) }
                val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
                col.addView(Ui.text(this, bank, 12f, Ui.onSurface, bold = true))
                val last = list.maxOf { p -> p.dues.getOrElse(p.periods - 1) { java.time.LocalDate.now() } }
                val sub = "  ${list.size} 笔 · 待还 ${Ui.amount(list.sumOf { it.remaining })} · ${list.maxOf { it.remainMonths }} 个月内结清（${last}）"
                col.addView(Ui.text(this, sub, 10f, Ui.onVar))
                row.addView(col, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(row)

            }
        }
        add(card)
    }

    private fun progress() {
        val card = Ui.card(this)
        card.addView(
            Ui.cardHeader(
                this, "本金 / 利息进度",
                "本金 ${(Repo.paidPrincipalAll / Repo.totalPrincipal * 100).toInt()}% · 利息 ${(Repo.paidInterestActive / Repo.totalInterestAll * 100).toInt()}%"
            )
        )
        fun line(name: String, paid: Double, total: Double, second: Boolean) {
            val head = Ui.row(this)
            head.addView(Ui.text(this, "$name ${Ui.amount(paid)} / ${Ui.amount(total)}", 10f, Ui.onVar))
            head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            head.addView(Ui.text(this, "${(paid / total * 100).toInt()}%", 11f, Ui.onSurface, bold = true))
            card.addView(head)
            card.addView(Ui.progressBar(this, paid / total, second))
            card.addView(Ui.vspace(this, 6f))
        }
        line("本金", Repo.paidPrincipalAll, Repo.totalPrincipal, false)
        line("利息", Repo.paidInterestActive, Repo.totalInterestAll, true)
        add(card)
    }

    private fun trend() {
        val card = Ui.card(this)
        val data = Repo.series()
        card.addView(
            Ui.cardHeader(this, "可支配走势",
                "最高 ${Ui.amount(data.maxOf { it.second })} · 最低 ${Ui.amount(data.minOf { it.second })}")
        )
        card.addView(TrendView(this).apply {
            points = data
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84))
            setOnClickListener { NavHolder.nav.openMonthly() }
        })
        val earliest = Repo.active.minByOrNull { it.remainPeriods }
        val footNote = if (earliest == null) {
            "现在没有在还计划"
        } else {
            val lastDue = Calc.dueDates(
                Calc.firstDue(earliest.startDate, earliest.statementDay, earliest.dueDay),
                earliest.periods
            ).last()
            "${lastDue.year} 年 ${lastDue.monthValue} 月「${earliest.bank.take(2)} ${earliest.tail}」结清后，每月可支配回到 ${Ui.money(Repo.disposable + earliest.perPay)}"
        }
        card.addView(
            Ui.text(this, footNote, 10f, Ui.onVar).apply { setPadding(0, dp(6), 0, dp(2)) }
        )
        add(card)
    }

    // ---------------- 03 编辑收入 ----------------

    private fun incomeSheet() {
        val sheet = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Ui.colorOf(this@DashboardActivity, Ui.surface))
            }
        }
        box.addView(Ui.text(this, "编辑收入", 19f, Ui.onSurface, bold = true))
        box.addView(
            Ui.text(this, "改完即时重算可支配预算，不离开这一屏", 11f, Ui.onVar)
                .apply { setPadding(0, dp(2), 0, dp(10)) }
        )

        val fields = ArrayList<EditText>()
        Repo.income.forEachIndexed { i, item ->
            val r = Ui.row(this).apply { setPadding(0, dp(6), 0, dp(6)) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.text(this, item.name, 13f, Ui.onSurface, bold = true))
            col.addView(Ui.text(this, item.hint, 10f, Ui.onVar))
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val et = EditText(this).apply {
                setText(String.format("%.2f", item.amount))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Ui.colorOf(this@DashboardActivity, Ui.onSurface))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                gravity = Gravity.END
                background = null
                minWidth = dp(96)
            }
            fields.add(et)
            r.addView(et)
            box.addView(r)
        }

        val totalView = Ui.text(this, "", 15f, Ui.onSurface, bold = true)
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Ui.colorOf(this@DashboardActivity, Ui.primaryContainer))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        preview.addView(Ui.text(this, "本月可支配预算", 10f, Ui.onPrimaryContainer))
        preview.addView(totalView)

        fun sum(): Double = fields.sumOf { it.text.toString().toDoubleOrNull() ?: 0.0 }
        fun refresh() {
            val income = sum()
            totalView.text = Ui.money(income - Repo.repay - Repo.fixedExpense)
        }
        fields.forEach { et ->
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { refresh() }
            })
        }
        refresh()
        box.addView(preview)

        val actions = Ui.row(this).apply { setPadding(0, dp(12), 0, 0) }
        actions.addView(Ui.text(this, "取消", 13f, Ui.primary, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val save = Ui.text(this, "保存并重算", 13f, Ui.onPrimary, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Ui.colorOf(this@DashboardActivity, Ui.primary))
            }
            setOnClickListener {
                fields.forEachIndexed { i, et ->
                    val v = et.text.toString().toDoubleOrNull() ?: 0.0
                    if (i < Repo.income.size) {
                        Repo.income[i] = Repo.income[i].copy(amount = v)
                    }
                }
                Store.save(this@DashboardActivity)
                sheet.dismiss()
                render()
            }
        }
        actions.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        box.addView(actions)

        sheet.setContentView(box)
        sheet.show()
    }
}
