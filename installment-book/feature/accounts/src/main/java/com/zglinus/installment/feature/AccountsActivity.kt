package com.zglinus.installment.feature

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*

/** 账户：信用账户 / 储蓄账户 / 收入账户 */
class AccountsActivity : PageActivity() {

    override val navIndex = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume(); render()
    }

    private fun render() {
        content.removeAllViews()
        val creditOut = Repo.remainingTotal
        header("账户", "信用 ${Repo.cards.size} · 储蓄 ${Repo.savings.size} · 收入 ${Repo.income.size}")

        // 顶部小结
        val kpi = Ui.card(this, 16f, 14f)
        val row = Ui.row(this)
        listOf(
            "信用待还" to Ui.amount(creditOut),
            "储蓄合计" to Ui.amount(Repo.savings.sumOf { it.balance }),
            "月收入" to Ui.amount(Repo.incomeTotal)
        ).forEachIndexed { i, (k, v) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(Ui.text(this, k, 9.5f, Ui.onVar))
            cell.addView(Ui.text(this, v, 14f, Ui.onSurface, bold = true))
            row.addView(cell)
            Anim.staggerIn(cell, i)
        }
        kpi.addView(row)
        add(kpi)

        // —— 信用账户 ——
        // 一个入口看全部分期汇总
        add(listItem(
            R.drawable.ic_add_plain, "分期汇总",
            "${(Repo.active + Repo.ended).size} 笔 · 待还 ${Ui.amount(Repo.remainingTotal)}"
        ) { NavHolder.nav.openBills() })
        add(md3Divider())

        add(sectionLabel("信用账户"))
        Repo.cards.forEach { c ->
            add(listItem(
                R.drawable.ic_card, "${c.bank} ${c.tail}",
                "账单 ${c.statementDay} 日 · 还款 ${c.dueDay} 日"
            ) { editCard(c) })
            add(md3Divider())
        }
        add(listItem(R.drawable.ic_add_plain, "新增信用账户", "银行、尾号、账单日、还款日") {
            editCard(null)
        })
        add(md3Divider())

        // —— 储蓄账户 ——
        add(sectionLabel("储蓄账户"))
        Repo.savings.forEach { s ->
            val inflow = Repo.incomeTo(s.name)
            add(listItem(
                R.drawable.ic_savings, s.name,
                "余额 · 本月入账 ${Ui.amount(inflow)}",
                trailing = Ui.text(this, Ui.money(s.balance), 15f, Ui.onSurface)
            ) { editSavings(s) })
            add(md3Divider())
        }
        add(listItem(R.drawable.ic_add_plain, "新增储蓄账户", "名称与余额") { editSavings(null) })
        add(md3Divider())

        // —— 收入账户 ——
        add(sectionLabel("收入账户"))
        Repo.income.forEachIndexed { i, item ->
            add(listItem(
                R.drawable.ic_income, item.name,
                "${item.hint} · → ${item.savingsRef.ifBlank { "未指定账户" }}",
                trailing = Ui.text(this, Ui.money(item.amount), 15f, Ui.onSurface)
            ) { editIncome(i) })
            add(md3Divider())
        }
        add(listItem(R.drawable.ic_add_plain, "新增收入账户", "工资 / 兼职 / 其他") { editIncome(null) })
        add(md3Divider())

        // —— 自动化 ——
        add(sectionLabel("自动化"))
        add(listItem(
            R.drawable.ic_card, "自动还款", "到期从默认卡扣款",
            trailing = SwitchCompat(this).apply {
                isChecked = Store.autoPay
                setOnCheckedChangeListener { _, on -> Store.setAutoPay(context, on) }
            }
        ) { })

        // —— 数据 ——
        add(sectionLabel("数据"))
        add(listItem(R.drawable.ic_storage, "重置为示例数据", "会清空账户、计划与配置",
            trailing = Ui.text(this, "›", 16f, Ui.error, bold = true)) { confirmReset() })
        add(Ui.vspace(this, 16f))
    }

    // ---------- 编辑 ----------

    private fun editCard(existing: Card?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val bank = field(box, "银行", existing?.bank ?: "招商银行", false)
        val tail = field(box, "尾号", existing?.tail ?: "", false)
        val stmt = field(box, "账单日 1–28", existing?.statementDay?.toString() ?: "25", true)
        val due = field(box, "还款日 1–28", existing?.dueDay?.toString() ?: "10", true)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "新增信用账户" else "编辑信用账户")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton(if (existing == null) "" else "删除") { _, _ ->
                if (existing != null) {
                    Repo.removeCard(existing)
                    Store.save(this); render()
                }
            }
            .setPositiveButton("保存") { _, _ ->
                val b = bank.text.toString().trim().ifBlank { "招商银行" }
                val t = tail.text.toString().trim().ifBlank { "0000" }
                val s = stmt.text.toString().toIntOrNull()?.coerceIn(1, 28) ?: 25
                val d = due.text.toString().toIntOrNull()?.coerceIn(1, 28) ?: 10
                if (existing == null) Repo.cards += Card(b, t, s, d)
                else Repo.updateCard(existing, b, t, s, d)
                Store.save(this); render()
            }
            .show()
    }

    private fun editSavings(existing: Savings?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field(box, "名称", existing?.name ?: "招行储蓄卡", false)
        val balance = field(box, "余额", existing?.balance?.let { String.format("%.2f", it) } ?: "0", true)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "新增储蓄账户" else "编辑储蓄账户")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton(if (existing == null) "" else "删除") { _, _ ->
                if (existing != null) {
                    Repo.savings.remove(existing)
                    Store.save(this); render()
                }
            }
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim().ifBlank { "储蓄账户" }
                val v = balance.text.toString().toDoubleOrNull() ?: 0.0
                if (existing == null) Repo.savings += Savings(n, v)
                else {
                    existing.name = n; existing.balance = v
                }
                Store.save(this); render()
            }
            .show()
    }

    private fun editIncome(index: Int?) {
        val item = index?.let { Repo.income.getOrNull(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field(box, "名称", item?.name ?: "", false)
        val amount = field(box, "每月金额", item?.amount?.let { String.format("%.2f", it) } ?: "0", true)
        var ref = item?.savingsRef ?: (Repo.savings.firstOrNull()?.name ?: "")
        val refView = Ui.text(
            this, "入账账户：${ref.ifBlank { "未指定" }}", 12f, Ui.primary, bold = true
        ).apply {
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                val names = Repo.savings.map { it.name }.toTypedArray()
                if (names.isEmpty()) {
                    Toast.makeText(this@AccountsActivity, "先去上面加一个储蓄账户", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                MaterialAlertDialogBuilder(this@AccountsActivity)
                    .setTitle("入账到哪个储蓄账户")
                    .setItems(names) { _, which ->
                        ref = names[which]
                        text = "入账账户：$ref"
                    }
                    .show()
            }
        }
        box.addView(refView)

        MaterialAlertDialogBuilder(this)
            .setTitle(if (item == null) "新增收入账户" else "编辑 ${item.name}")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton(if (item == null) "" else "删除") { _, _ ->
                if (index != null) {
                    Repo.income.removeAt(index)
                    Store.save(this); render()
                }
            }
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim().ifBlank { "收入" }
                val v = amount.text.toString().toDoubleOrNull() ?: 0.0
                if (index == null) Repo.income += IncomeItem(n, v, "每月", ref)
                else Repo.income[index] = IncomeItem(n, v, item?.hint ?: "每月", ref)
                Store.save(this); render()
            }
            .show()
    }

    private fun field(parent: LinearLayout, label: String, initial: String, number: Boolean): EditText {
        parent.addView(Ui.text(this, label, 10f, Ui.onVar).apply { setPadding(0, dp(8), 0, 0) })
        val et = EditText(this).apply {
            setText(initial)
            if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        parent.addView(et)
        return et
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("重置数据")
            .setMessage("会清空账户、分期与配置，恢复成示例数据。不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("重置") { _, _ ->
                Repo.reset(); Repo.seed(); Store.save(this)
                Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
    }
}
