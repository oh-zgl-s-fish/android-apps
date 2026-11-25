package com.zglinus.installment.feature

import android.app.DatePickerDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*
import java.time.LocalDate

/**
 * 新建分期，照 PH 的「创建分期计划」：
 * 名称 / 本金 / 利息总额 / 期数 / 方式 / 年化（与利息双向联动）
 * 付款来源 / 入账日 / 出账日 / 还款日 / 首期日（自动推算可覆盖）
 * 自动还款 / 备注 / 每期明细
 *
 * 规则：改任何一个上游输入，下游全部重算，但不动你正在编辑的那个字段。
 */
class NewPlanActivity : PageActivity() {

    override val navIndex = 1
    override val isTabPage = false

    // 状态
    private var title = ""
    private var principal = 12000.0
    private var interest = 792.0
    private var periods = 12
    private var method = Method.FLAT_RATE
    private var card: Card = Repo.cards.firstOrNull() ?: Card("招商银行", "4821", 25, 10)
    private var entryDate: LocalDate = LocalDate.now()
    private var statementDay = card.statementDay
    private var dueDay = card.dueDay
    private var firstDueOverride: LocalDate? = null
    private var autoPay = Store.autoPay
    private var note = ""
    private var editing = ""      // 正在编辑的字段，避免被回写覆盖
    private var syncing = false
    /** 利息/年化里最后被用户编辑的那个，作为锚点（改期数/方式/本金时它不动，另一个重算） */
    private var anchor = "interest"
    private var aprRate = Calc.apr(method, principal, periods, interest)
    // 程序性回写时置真，挡掉 TextWatcher 把自己的写值当成用户输入（否则会反向覆盖上游字段）
    private var writing = false

    private fun setQuiet(et: android.widget.EditText, v: String) {
        if (et.text.toString() == v) return
        writing = true
        et.setText(v)
        et.setSelection(v.length)
        writing = false
    }

    // 视图
    private lateinit var etTitle: EditText
    private lateinit var etPrincipal: EditText
    private lateinit var etInterest: EditText
    private lateinit var etApr: EditText
    private lateinit var etStmt: EditText
    private lateinit var etDue: EditText
    private lateinit var tvCard: TextView
    private lateinit var tvEntry: TextView
    private lateinit var tvFirst: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvSub: TextView
    private lateinit var table: LinearLayout
    private lateinit var periodRow: LinearLayout
    private lateinit var etPeriods: EditText
    private val rowP = mutableListOf<Double>()
    private val rowI = mutableListOf<Double>()
    private val manual = mutableListOf<Boolean>()
    private lateinit var methodRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showBack()
        header("新建分期", "")
        buildUi()
        syncFields(source = "")
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        etTitle = field("计划名称", title, "例如：iPhone 24 期") { v -> title = v; editing = "title"; syncFields("") }
        etPrincipal = numberField("总金额（本金）", principal) { v -> principal = v; editing = "principal"; syncFields("principal") }
        // 利息 ⇄ 年化：标签直接在输入框上，中间的 ⇆ 表示两者联动
        val irRow = Ui.row(this).apply { setPadding(dp(0), dp(12), dp(0), dp(0)) }
        etInterest = mdFieldInline(String.format("%.2f", interest), "利息") { s2 ->
            s2.toDoubleOrNull()?.let { interest = it.coerceAtLeast(0.0); editing = "interest"; syncFields("interest") }
        }
        etApr = mdFieldInline(String.format("%.2f", Calc.apr(method, principal, periods, interest) * 100), "年化 %") { s2 ->
            s2.toDoubleOrNull()?.let { editing = "apr"; syncFields("apr") }
        }
        irRow.addView(etInterest.tag as View, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        irRow.addView(Ui.text(this, "⇆", 15f, Ui.primary).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40f), ViewGroup.LayoutParams.WRAP_CONTENT))
        irRow.addView(etApr.tag as View, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(irRow)

        // 期数
        add(Ui.text(this, "期数", 11f, Ui.onVar).apply { setPadding(dp(4), dp(12), 0, dp(6)) })
        periodRow = Ui.row(this)
        listOf(3, 6, 12, 24, 36).forEach { n ->
            val chip = Ui.chip(this, "$n 期", n == periods)
            chip.layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f).apply { rightMargin = dp(6f) }
            chip.setOnClickListener {
                periods = n; editing = "periods"
                if (!manual.all { true }) { manual.clear() }
                setQuiet(etPeriods, n.toString())
                refreshChips(); syncFields("periods")
            }
            periodRow.addView(chip)
        }
        add(periodRow)
        etPeriods = mdField("自定义期数", periods.toString(), numeric = true) { s2 ->
            val v = s2.toIntOrNull()?.coerceIn(1, 60) ?: return@mdField
            if (v != periods) {
                periods = v; editing = "periods"
                manual.fill(false)
                refreshChips(); syncFields("periods")
            }
        }

        // 方式
        add(Ui.text(this, "还款方式", 11f, Ui.onVar).apply { setPadding(dp(4), dp(12), 0, dp(6)) })
        methodRow = Ui.row(this)
        Method.entries.forEach { m ->
            val chip = Ui.chip(this, m.label, m == method)
            chip.layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f).apply { rightMargin = dp(6f) }
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            chip.setOnClickListener {
                method = m; editing = "method"
                refreshChips(); syncFields("method")
            }
            methodRow.addView(chip)
        }
        add(methodRow)

        // 付款来源
        add(Ui.text(this, "信用账户", 11f, Ui.onVar).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        tvCard = pickRow("${card.bank} ${card.tail}") {
            val names = Repo.cards.map { "${it.bank} ${it.tail}" }.toTypedArray()
            if (names.isEmpty()) {
                Toast.makeText(this, "先去账户里加一张卡", Toast.LENGTH_SHORT).show()
                return@pickRow
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("信用账户")
                .setItems(names) { _, which ->
                    card = Repo.cards[which]
                    statementDay = card.statementDay
                    statementDay = card.statementDay
                    dueDay = card.dueDay
                    firstDueOverride = null
                    tvCard.text = "${card.bank} ${card.tail}"
                    syncFields("card")
                }
                .show()
        }

        // 日期三件套
        add(Ui.text(this, "入账日 / 出账日 / 还款日", 11f, Ui.onVar).apply { setPadding(dp(4), dp(14), 0, dp(6)) })
        tvEntry = pickRow(entryDate.toString()) { pickDate(entryDate) { d ->
            entryDate = d; firstDueOverride = null; tvEntry.text = d.toString(); syncFields("entry")
        } }

        // 出账日 / 还款日跟随所选信用卡，只展示不可改
        val dayNote = Ui.row(this).apply { setPadding(dp(4), dp(6), dp(4), dp(2)) }
        dayNote.addView(
            Ui.text(this, "出账日 ${statementDay} 日 · 还款日 ${dueDay} 日", 11f, Ui.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        add(dayNote)

        tvFirst = pickRow(Calc.firstDue(entryDate, statementDay, dueDay).toString()) {
            pickDate(Calc.firstDue(entryDate, statementDay, dueDay), override = true) { d ->
                firstDueOverride = d; tvFirst.text = d.toString(); syncFields("first")
            }
        }


        // 自动还款 / 备注
        val autoRow = Ui.row(this).apply { setPadding(dp(4), dp(14), dp(4), dp(4)) }
        autoRow.addView(Ui.text(this, "自动还款", 12f, Ui.onSurface))
        autoRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        autoRow.addView(SwitchCompat(this).apply {
            isChecked = autoPay
            setOnCheckedChangeListener { _, on -> autoPay = on }
        })
        add(autoRow)
        field("备注", note, "可留空") { v -> note = v }

        // 每期明细
        val head = Ui.row(this).apply { setPadding(dp(4), dp(16), dp(4), dp(6)) }
        head.addView(Ui.text(this, "每期明细", 12f, Ui.onSurface, bold = true))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(Ui.text(this, "重新按方式分摊", 10f, Ui.primary, bold = true).apply {
            setOnClickListener {
                manual.fill(false)
                spreadAll(principal, periods, interest)
                renderTable(principal, periods, interest)
                refreshTotals()
            }
        })
        add(head)

        table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        val tableCard = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(20f).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Ui.colorOf(this@NewPlanActivity, Ui.container))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }
            addView(table)
        }
        add(tableCard)

        // 结果条
        val result = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(Ui.colorOf(this@NewPlanActivity, Ui.primaryContainer))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14f) }
        }
        val top = Ui.row(this)
        top.addView(Ui.text(this, "每期应还", 10f, Ui.onPrimaryContainer))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tvResult = Ui.text(this, "¥0.00", 21f, Ui.onPrimaryContainer, bold = true)
        top.addView(tvResult)
        result.addView(top)
        tvSub = Ui.text(this, "", 10f, Ui.onPrimaryContainer).apply { setPadding(0, dp(4), 0, 0) }
        result.addView(tvSub)
        add(result)

        // 动作（MD3）
        val actions = Ui.row(this).apply { setPadding(0, dp(16), 0, dp(28)) }
        val cancel = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "取消"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(48f), 1f).apply { rightMargin = dp(10f) }
            setOnClickListener { finish() }
        }
        val submit = com.google.android.material.button.MaterialButton(this).apply {
            text = "创建计划"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(48f), 1.6f)
            setOnClickListener { create() }
        }
        actions.addView(cancel); actions.addView(submit)
        add(actions)
    }

    // ---------------- 重算 ----------------

    /** 只有当前编辑字段作为源，其余全部跟随 */
    private fun syncFields(source: String) {
        if (syncing) return
        syncing = true

        val p = principal.coerceAtLeast(0.0)
        val n = periods.coerceAtLeast(1)

        when (source) {
            // 用户编辑年化 → 年化是锚点，利息由它推
            "apr" -> {
                anchor = "apr"
                aprRate = (etApr.value() / 100.0).coerceAtLeast(0.0)
                interest = Calc.interestFromApr(method, p, n, aprRate)
                setQuiet(etInterest, String.format("%.2f", interest))
            }
            // 用户编辑利息 → 利息是锚点，年化由它推
            "interest" -> {
                anchor = "interest"
                aprRate = Calc.apr(method, p, n, interest)
                setQuiet(etApr, String.format("%.2f", aprRate * 100))
            }
            // 改本金 / 期数 / 还款方式 / 卡片：锚点不动，另一个重算
            "principal", "periods", "method", "card" -> {
                if (anchor == "interest") {
                    aprRate = Calc.apr(method, p, n, interest)
                    setQuiet(etApr, String.format("%.2f", aprRate * 100))
                } else {
                    interest = Calc.interestFromApr(method, p, n, aprRate)
                    setQuiet(etInterest, String.format("%.2f", interest))
                }
            }
            else -> { /* title / note / entry / first：不动金额 */ }
        }

        // 首期日（用户没手动改过就跟着入账日/日期设置走）
        val derived = Calc.firstDue(entryDate, statementDay, dueDay)
        if (firstDueOverride == null) tvFirst.text = derived.toString()

        // 上游一变，手工改动作废，明细按当前还款方式整表重算
        if (source in setOf("principal", "interest", "apr", "periods", "method", "card")) {
            manual.fill(false)
        }

        val perPay = paymentPerPeriod(method, p, n, interest, 0)
        tvResult.text = Ui.money(perPay)
        tvSub.text = "本息 ${Ui.amount(p + interest)} · 利息 ${Ui.amount(interest)} · 年化 ${Ui.pct(aprRate)}"

        renderTable(p, n, interest)
        syncing = false
    }

    private fun recalcs() = syncFields("spread")

    /** 第 i 期（0-based）应还 */
    private fun paymentPerPeriod(m: Method, p: Double, n: Int, i: Double, index: Int): Double =
        when (m) {
            Method.INTEREST_FREE -> p / n
            Method.FLAT_RATE -> p / n + i / n
            Method.EQUAL_PRINCIPAL -> {
                val r = if (p > 0) 2 * i / (p * (n + 1)) else 0.0
                p / n + (p - p / n * index) * r
            }
            Method.EQUAL_INSTALLMENT -> {
                val a = (p + i) / n
                a
            }
        }

    private fun renderTable(p: Double, n: Int, totalInterest: Double) {
        table.removeAllViews()
        val head = Ui.row(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Ui.colorOf(this@NewPlanActivity, Ui.primaryContainer))
            }
        }
        listOf("#" to 0.35f, "到期日" to 1.5f, "本金" to 1.25f, "利息" to 1.1f, "合计" to 1.15f)
            .forEachIndexed { idx, (t, w) ->
                val tv = Ui.text(
                    this, t, 9.5f,
                    Ui.colorOf(this@NewPlanActivity, Ui.onPrimaryContainer), bold = true
                )
                if (idx >= 2) tv.gravity = Gravity.END
                head.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w))
            }
        table.addView(head)

        // 期数变了，或没有任何手工改动 → 整表按当前还款方式重排
        if (rowP.size != n) {
            rowP.clear(); rowI.clear(); manual.clear()
            for (i in 0 until n) { rowP += 0.0; rowI += 0.0; manual += false }
        }
        if (manual.none { it }) spreadAll(p, n, totalInterest)

        val first = firstDueOverride ?: Calc.firstDue(entryDate, statementDay, dueDay)
        for (i in 0 until n) {
            val row = Ui.row(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(9), dp(8), dp(9))
                if (i % 2 == 1) background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(Ui.colorOf(this@NewPlanActivity, Ui.onSurface) and 0x14FFFFFF)
                }
            }
            val d = first.plusMonths(i.toLong())
            row.addView(Ui.text(this, "${i + 1}", 10f, Ui.onVar),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f))
            row.addView(Ui.text(this, "$d", 9.5f, Ui.onVar),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
            row.addView(editCell(rowP[i], manual[i]) { v -> onRowEdit(i, true, v) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f))
            row.addView(editCell(rowI[i], manual[i]) { v -> onRowEdit(i, false, v) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            val total = Ui.text(this, Ui.plain(rowP[i] + rowI[i]), 10f,
                Ui.colorOf(this@NewPlanActivity, Ui.onSurface), bold = true)
            total.gravity = Gravity.END
            total.tag = "sum$i"
            row.addView(total, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
            table.addView(row)
        }
    }

    /** 可编辑单元格（本金/利息） */
    private fun editCell(value: Double, edited: Boolean, onChange: (Double) -> Unit): View {
        val et = EditText(this).apply {
            setText(String.format("%.2f", value))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(Ui.colorOf(this@NewPlanActivity, if (edited) Ui.primary else Ui.onSurface))
            setPadding(dp(2), dp(2), dp(2), dp(2))
            gravity = Gravity.END
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Ui.colorOf(this@NewPlanActivity, Ui.onSurface) and 0x0DFFFFFF)
                if (edited) setStroke(dp(1), Ui.colorOf(this@NewPlanActivity, Ui.primary))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (syncingRow) return
                    val v = s?.toString()?.toDoubleOrNull() ?: return
                    onChange(v)
                }
            })
        }
        return et
    }

    private var syncingRow = false

    /** 手工改第 i 期 → 剩余量摊到后面各期，合计/年化跟着变 */
    private fun onRowEdit(i: Int, isPrincipal: Boolean, value: Double) {
        if (i !in rowP.indices) return
        manual[i] = true
        if (isPrincipal) rowP[i] = value.coerceAtLeast(0.0) else rowI[i] = value.coerceAtLeast(0.0)
        redistribute(i)
        syncingRow = true
        refreshRowWidgets()
        syncingRow = false
        refreshTotals()
    }

    /** 手工改了某期后，剩余各期按当前还款方式重算（本金/利息分别守恒） */
    private fun redistribute(i: Int) {
        val n = rowP.size
        if (i >= n - 1) return
        val left = n - i - 1
        val restP = (principal - rowP.take(i + 1).sum()).coerceAtLeast(0.0)
        val restI = (interest - rowI.take(i + 1).sum()).coerceAtLeast(0.0)

        // 先算各期权重，再按剩余总额缩放，保证本金/利息总额都守住
        val wP = DoubleArray(left) { 1.0 }                     // 本金：各方式都均摊
        val wI = DoubleArray(left)
        var balance = restP
        val perP = if (left > 0) restP / left else 0.0
        for (j in 0 until left) {
            wI[j] = when (method) {
                Method.INTEREST_FREE -> 0.0
                Method.FLAT_RATE -> 1.0                         // 等本等息：每期利息相同
                Method.EQUAL_PRINCIPAL -> balance               // 等额本金：利息随剩余本金递减
                Method.EQUAL_INSTALLMENT -> balance * 2.0 / (left + 1 - j)  // 等额本息：前高后低
            }
            balance -= perP
            if (balance < 0) balance = 0.0
        }
        val sumI = wI.sum()
        val sumP = wP.sum()
        for (j in 0 until left) {
            rowP[i + 1 + j] = if (sumP > 0) restP * wP[j] / sumP else 0.0
            rowI[i + 1 + j] = if (sumI > 0) restI * wI[j] / sumI else restI / left
            manual[i + 1 + j] = false
        }
    }

    /** 完全按方式重排（丢掉手工改动） */
    private fun spreadAll(p: Double, n: Int, totalInterest: Double) {
        val r = if (p > 0) 2 * totalInterest / (p * (n + 1)) else 0.0
        var balance = p
        for (i in 0 until n) {
            val principalPart = when (method) {
                Method.EQUAL_INSTALLMENT -> {
                    val a = (p + totalInterest) / n
                    (a - balance * r).coerceAtLeast(0.0)
                }
                else -> p / n
            }
            val interestPart = when (method) {
                Method.INTEREST_FREE -> 0.0
                Method.FLAT_RATE -> totalInterest / n
                else -> balance * r
            }
            rowP[i] = principalPart
            rowI[i] = interestPart
            balance -= principalPart
            if (balance < 0) balance = 0.0
        }
    }

    private fun refreshRowWidgets() {
        val first = firstDueOverride ?: Calc.firstDue(entryDate, statementDay, dueDay)
        for (i in rowP.indices) {
            val row = table.getChildAt(i + 1) as? LinearLayout ?: continue
            (row.getChildAt(2) as? EditText)?.let { if (!it.hasFocus()) it.setText(String.format("%.2f", rowP[i])) }
            (row.getChildAt(3) as? EditText)?.let { if (!it.hasFocus()) it.setText(String.format("%.2f", rowI[i])) }
            (row.getChildAt(4) as? TextView)?.text = Ui.plain(rowP[i] + rowI[i])
            row.getChildAt(2)?.alpha = if (manual[i]) 1f else 1f
        }
        val firstDate = first.plusMonths(0)
        if (firstDate.year < 1971) return
    }

    private fun refreshTotals() {
        val totalInterest = rowI.sum()
        val perPay = rowP.firstOrNull() ?: 0.0
        tvResult.text = Ui.money((rowP.firstOrNull() ?: 0.0) + (rowI.firstOrNull() ?: 0.0))
        tvSub.text = "本息 ${Ui.amount(principal + totalInterest)} · 利息 ${Ui.amount(totalInterest)} · 年化 ${Ui.pct(Calc.apr(method, principal, periods, totalInterest))}"
    }

    private fun create() {
        if (principal <= 0) { Toast.makeText(this, "本金要大于 0", Toast.LENGTH_SHORT).show(); return }
        val first = firstDueOverride ?: Calc.firstDue(entryDate, statementDay, dueDay)
        val plan = Plan(
            note = note,
            id = Repo.newId(),
            title = title.ifBlank { "${card.bank} ${card.tail} 分期" },
            bank = card.bank,
            tail = card.tail,
            principal = principal,
            periods = periods,
            paidPeriods = 0,
            perInterest = interest / periods,
            method = method,
            startDate = entryDate,
            statementDay = statementDay,
            dueDay = dueDay
        )
        Repo.add(plan)
        Store.save(this)
        Toast.makeText(this, "已创建 · 每期 ${Ui.money(paymentPerPeriod(method, principal, periods, interest, 0))}", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------------- 控件 ----------------

    private fun refreshChips() {
        listOf(3, 6, 12, 24, 36).forEachIndexed { i, n ->
            val v = periodRow.getChildAt(i) as TextView
            paintChip(v, n == periods)
        }
        Method.entries.forEachIndexed { i, m ->
            paintChip(methodRow.getChildAt(i) as TextView, m == method)
        }
    }

    private fun paintChip(v: TextView, on: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            if (on) setColor(Ui.colorOf(this@NewPlanActivity, Ui.secondaryContainer))
            else {
                setColor(0x00000000)
                setStroke(dp(1), Ui.colorOf(this@NewPlanActivity, Ui.outlineVariant))
            }
        }
        v.setTextColor(Ui.colorOf(this@NewPlanActivity, if (on) Ui.onSecondaryContainer else Ui.onVar))
    }

    /** 行内 MD3 输入框：无浮动标签，左右并排用 */
    private fun mdFieldInline(initial: String, hint: String, onChange: (String) -> Unit): EditText {
        val til = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            this.hint = hint
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            val r = dp(12f).toFloat()
            setBoxCornerRadii(r, r, r, r)
            setBoxStrokeColor(Ui.colorOf(this@NewPlanActivity, Ui.primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val et = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            setText(initial)
            setSingleLine()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (writing) return      // 程序回写不算用户输入
                    onChange(s?.toString() ?: "")
                }
            })
        }
        til.addView(et)
        // 返回 EditText，但调用方需要拿到 TIL 一起布局 → 用 tag 挂一下
        et.tag = til
        return et
    }

    private fun mdField(
        label: String, initial: String, hint: String = "",
        numeric: Boolean = false, attach: Boolean = true, onChange: (String) -> Unit
    ): EditText {
        val til = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            this.hint = label
            if (hint.isNotBlank()) runCatching { placeholderText = hint }
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            val r = dp(8f).toFloat()
            setBoxCornerRadii(r, r, r, r)
            setBoxStrokeColor(Ui.colorOf(this@NewPlanActivity, Ui.primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        val et = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            setText(initial)
            setSingleLine()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (writing) return      // 程序回写不算用户输入
                    onChange(s?.toString() ?: "")
                }
            })
        }
        til.addView(et)
        if (attach) add(til)
        return et
    }

    private fun field(label: String, initial: String, hint: String, onChange: (String) -> Unit): EditText =
        mdField(label, initial, hint, onChange = onChange)

    private fun numberField(label: String, value: Double, onChange: (Double) -> Unit): EditText =
        mdField(label, String.format("%.2f", value), numeric = true) { s ->
            onChange(s.toDoubleOrNull() ?: 0.0)
        }

    private fun dayEdit(label: String, value: Int, onChange: (Int) -> Unit): EditText =
        mdField(label, value.toString(), numeric = true, attach = false) { s ->
            val v = s.toIntOrNull()?.coerceIn(1, 28) ?: return@mdField
            onChange(v)
        }

    /** 只返回布局，由调用方挂载，避免「已有 parent」 */
    private fun dayField(label: String, value: Int, onChange: (Int) -> Unit): View {
        val til = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = label
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            val r = dp(8f).toFloat()
            setBoxCornerRadii(r, r, r, r)
            setBoxStrokeColor(Ui.colorOf(this@NewPlanActivity, Ui.primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        val et = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            setText(value.toString())
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toIntOrNull()?.coerceIn(1, 28) ?: return
                    onChange(v)
                }
            })
        }
        til.addView(et)
        return til
    }

    private fun pickRow(text: String, onClick: () -> Unit): TextView {
        val row = Ui.row(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Ui.colorOf(this@NewPlanActivity, Ui.container))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
            setOnClickListener { onClick() }
        }
        val tv = Ui.text(this, text, 13f, Ui.onSurface, bold = true)
        row.addView(tv)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Ui.text(this, "›", 15f, Ui.onPrimary, bold = true))
        add(row)
        return tv
    }

    private fun pickDate(initial: LocalDate, override: Boolean = false, onPick: (LocalDate) -> Unit) {
        DatePickerDialog(
            this,
            { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth
        ).show()
    }

    private fun EditText.value(): Double = text.toString().toDoubleOrNull() ?: 0.0
}
