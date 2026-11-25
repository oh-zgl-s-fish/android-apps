package com.zglinus.installment.feature

import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.time.LocalDate

class ScheduleActivity : PageActivity() {

    override val navIndex = 1
    override val isTabPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plan = Repo.find(intent.getLongExtra("id", -1L)) ?: Repo.find(Repo.active.firstOrNull()?.id ?: -1L)
            ?: run { finish(); return }
        val title = "${plan.bank} ${plan.tail}"
        showBack()
        header("每期明细", "$title · ${plan.periods} 期")

        val install = plan.startDate
        val first = Calc.firstDue(install, plan.statementDay, plan.dueDay)
        val dates = Calc.dueDates(first, plan.periods)
        val today = LocalDate.of(2026, 11, 24)

        // 推导链
        val chain = Ui.card(this)
        val row = Ui.row(this)
        fun step(k: String, v: String) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            c.addView(Ui.text(this, k, 9f, Ui.onVar).apply { gravity = Gravity.CENTER })
            c.addView(Ui.text(this, v, 11f, Ui.onSurface, bold = true).apply { gravity = Gravity.CENTER })
            row.addView(c)
        }
        step("分期日", "%02d-%02d".format(install.monthValue, install.dayOfMonth))
        step("账单日 ${plan.statementDay} 出账", "%02d-%02d".format(install.monthValue, plan.statementDay))
        step("首期还款", "%02d-%02d".format(first.monthValue, first.dayOfMonth))
        chain.addView(row)
        add(chain)

        add(
            Ui.text(
                this,
                "分期日 ≤ 账单日，计入当月账单；还款日 ${plan.dueDay} 在账单日之前，故落在次月。到期日已过的期自动标「已完成」。",
                10f, Ui.onVar
            ).apply { setPadding(dp(4f), dp(2f), dp(4f), dp(8f)) }
        )

        val table = Ui.card(this, 12f, 6f)
        val head = Ui.row(this)
        head.addView(Ui.text(this, "#", 9f, Ui.onVar), LinearLayout.LayoutParams(dp(18f), ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(Ui.text(this, "到期日", 9f, Ui.onVar), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        head.addView(Ui.text(this, "本金", 9f, Ui.onVar), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(this, "利息", 9f, Ui.onVar), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        head.addView(Ui.text(this, "合计", 9f, Ui.onVar).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(58f), ViewGroup.LayoutParams.WRAP_CONTENT))
        table.addView(head)

        var doneCount = 0
        var doneSum = 0.0
        dates.forEachIndexed { i, d ->
            val done = !d.isAfter(today)
            if (done) { doneCount++; doneSum += plan.amounts.getOrNull(i) ?: plan.perPay }
            val r = Ui.row(this)
            r.setPadding(0, dp(6f), 0, dp(6f))
            val alpha = if (done) 0.45f else 1f
            val num = Ui.text(this, if (done) "✓" else "${i + 1}", 10f, if (done) Ui.onVar else Ui.onVar)
            num.alpha = alpha
            r.addView(num, LinearLayout.LayoutParams(dp(18f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val date = Ui.text(this, d.toString(), 10f, if (done) Ui.onVar else Ui.onSurface)
            date.alpha = alpha
            r.addView(date, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            // 逐期本金/利息用真实值，不再拿均摊值铺满全部期数
            val rowAmount = plan.amounts.getOrNull(i)
            val rowInterest = plan.interests.getOrNull(i) ?: plan.periodSplit(i).second
            val rowPrincipal = rowAmount?.let { it - rowInterest } ?: plan.periodSplit(i).first
            listOf(Ui.plain(rowPrincipal), Ui.plain(rowInterest)).forEach { v ->
                val t = Ui.text(this, v, 10f, Ui.onVar)
                t.alpha = alpha
                r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val sum = Ui.text(this, Ui.plain(rowAmount ?: (rowPrincipal + rowInterest)), 10f, Ui.onSurface, bold = true)
            sum.gravity = Gravity.END
            sum.alpha = alpha
            r.addView(sum, LinearLayout.LayoutParams(dp(58f), ViewGroup.LayoutParams.WRAP_CONTENT))
            table.addView(r)
        }
        add(table)

        // 出账日 / 还款日跟随信用卡，只展示不可改
        val card = Repo.cards.firstOrNull { it.bank == plan.bank }
        val stmt = card?.statementDay ?: plan.statementDay
        val due = card?.dueDay ?: plan.dueDay
        val info = Ui.row(this).apply { setPadding(dp(4), dp(12), dp(4), dp(4)) }
        info.addView(
            Ui.text(this, "账单日 / 还款日", 11f, Ui.onVar),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        info.addView(Ui.text(this, "每月 $stmt 日 / $due 日", 11f, Ui.onSurface, bold = true))
        add(info)

        val foot = Ui.card(this, 12f, 10f)
        val frow = Ui.row(this)
        frow.addView(Ui.text(this, "已完成 $doneCount 期 ${Ui.plain(doneSum)} · 待还 ${plan.periods - doneCount} 期 ${Ui.plain(plan.total - doneSum)}", 10f, Ui.onVar))
        frow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        frow.addView(Ui.text(this, Ui.money(plan.total), 14f, Ui.onSurface, bold = true))
        foot.addView(frow)
        add(foot)
    }

}
