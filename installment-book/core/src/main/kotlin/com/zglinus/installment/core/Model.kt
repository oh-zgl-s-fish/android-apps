package com.zglinus.installment.core

import java.time.LocalDate

enum class Method(val label: String) {
    EQUAL_PRINCIPAL("等额本金"),
    EQUAL_INSTALLMENT("等额本息"),
    FLAT_RATE("等本等息"),
    INTEREST_FREE("免息")
}

data class Plan(
    val id: Long,
    val title: String = "",
    /** 备注，可空 */
    val note: String = "",
    val bank: String,
    val tail: String,
    val principal: Double,
    val periods: Int,
    val paidPeriods: Int,
    val perInterest: Double,
    val method: Method,
    val startDate: LocalDate,
    val statementDay: Int = 25,
    val dueDay: Int = 10,
    /** PH 等外部数据源的真实逐期金额（含息）；空 = 按方式均摊 */
    val amounts: List<Double> = emptyList(),
    /** 每期利息原值（来自导入的 items，空 = 按还款方式推算） */
    val interests: List<Double> = emptyList()
) {
    val perPrincipal: Double get() = principal / periods
    val totalInterest: Double
        get() = when {
            interests.isNotEmpty() -> interests.sum()
            amounts.isNotEmpty() -> (amounts.sum() - principal).coerceAtLeast(0.0)
            else -> perInterest * periods
        }
    val perPay: Double
        get() = if (amounts.isNotEmpty()) amounts.getOrElse(paidPeriods) { amounts.last() } else (principal / periods + perInterest)
    val total: Double get() = principal + totalInterest
    val remaining: Double
        get() = if (amounts.isNotEmpty()) amounts.drop(paidPeriods).sum() else perPay * remainPeriods
    /** 每期到期日 */
    val dues: List<LocalDate>
        get() = Calc.dueDates(Calc.firstDue(startDate, statementDay, dueDay), periods)

    /** 当月应还（含已还，用于预算口径） */
    val thisMonthDue: Double
        get() {
            val now = LocalDate.now()
            val ds = dues
            var sum = 0.0
            for (i in 0 until periods) {
                val d = ds.getOrNull(i) ?: continue
                if (d.year == now.year && d.monthValue == now.monthValue) {
                    sum += if (amounts.isNotEmpty()) amounts.getOrElse(i) { amounts.last() }
                    else perPrincipal + perInterest
                }
            }
            return sum
        }

    /** 剩余月数：同一自然月内的多期算 1 期 */
    val remainMonths: Int
        get() = dues.drop(paidPeriods).map { it.year * 12 + it.monthValue }.distinct().size

    /** 下个月应还合计 */
    val nextMonthDue: Double
        get() {
            val now = LocalDate.now()
            val target = now.plusMonths(1)
            var sum = 0.0
            val ds = dues
            for (i in 0 until periods) {
                val d = ds.getOrNull(i) ?: continue
                if (d.year == target.year && d.monthValue == target.monthValue) {
                    sum += if (amounts.isNotEmpty()) amounts.getOrElse(i) { amounts.last() }
                    else perPrincipal + perInterest
                }
            }
            return sum
        }

    val apr: Double get() = Calc.apr(method, principal, periods, totalInterest)
    val finished: Boolean get() = paidPeriods >= periods
    val remainPeriods: Int get() = periods - paidPeriods
    /** 换还款方式后，按新方式重算逐期本金/利息（丢弃导入的旧明细） */
    fun recalcWith(newMethod: Method): Plan {
        val n = periods.coerceAtLeast(1)
        val perP = principal / n
        val amts = ArrayList<Double>(n)
        val ints = ArrayList<Double>(n)
        for (i in 0 until n) {
            val interest = when (newMethod) {
                Method.INTEREST_FREE -> 0.0
                // 等本等息：每期利息固定
                Method.FLAT_RATE -> perInterest
                // 等额本金：利息随剩余本金递减
                Method.EQUAL_PRINCIPAL -> perInterest * (n - i) / n
                // 等额本息：利息前高后低，总利息守恒
                Method.EQUAL_INSTALLMENT -> perInterest * (n - i) * 2.0 / (n + 1)
            }
            ints += "%.2f".format(interest).toDouble()
            amts += "%.2f".format(perP + interest).toDouble()
        }
        val totalInt = ints.sum()
        return copy(
            method = newMethod,
            amounts = amts,
            interests = ints,
            perInterest = "%.2f".format(totalInt / n).toDouble()
        )
    }

    /** 第 i 期（0 基）的本金与利息拆分 */
    fun periodSplit(i: Int): Pair<Double, Double> {
        val amount = if (amounts.isNotEmpty()) amounts.getOrElse(i) { amounts.last() }
                     else perPrincipal + perInterest
        val pp = when (method) {
            Method.EQUAL_PRINCIPAL -> perPrincipal
            // 等额本息：利息按剩余期数线性摊（总利息守恒）
            Method.EQUAL_INSTALLMENT ->
                amount - totalInterest * (periods - i) * 2.0 / (periods * (periods + 1))
            else -> perPrincipal
        }
        return pp to (amount - pp)
    }

    /** 已付利息 = 前 N 期各自利息之和（等额本息前高后低，不能用 perInterest×期数） */
    val paidInterest: Double
        get() = when {
            interests.isNotEmpty() -> interests.take(paidPeriods).sum()
            amounts.isNotEmpty() -> (0 until paidPeriods).sumOf { periodSplit(it).second }
            else -> perInterest * paidPeriods
        }
    val paidPrincipal: Double
        get() = if (amounts.isNotEmpty()) amounts.take(paidPeriods).sum() - paidInterest
                else perPrincipal * paidPeriods
}

data class IncomeItem(val name: String, val amount: Double, val hint: String, val savingsRef: String = "")

/** 信用账户（信用卡） */
data class Card(
    var bank: String,
    var tail: String,
    var statementDay: Int,
    var dueDay: Int
)

/** 储蓄账户 */
data class Savings(var name: String, var balance: Double)

/** AI 会话 */
data class ChatMsg(val role: String, val text: String, val image: String? = null)
data class Chat(val id: Long, var title: String, val msgs: MutableList<ChatMsg> = mutableListOf())

/** MCP 服务配置（本地保存） */
data class McpServer(
    var name: String,
    var url: String,
    var token: String = "",
    var enabled: Boolean = true
)

/** 纯内存仓库：所有修改都通过这里，改完由 ui 层负责落盘 */
object Repo {

    val income = mutableListOf(
        IncomeItem("工资", 7510.0, "固定 · 每月 10 号"),
        IncomeItem("兼职", 690.0, "浮动 · 上月 620"),
        IncomeItem("其他", 0.0, "未填")
    )

    var fixedExpense: Double = 2988.0

    val cards = mutableListOf<Card>()
    val savings = mutableListOf<Savings>()

    val active = mutableListOf<Plan>()
    val ended = mutableListOf<Plan>()

    private var nextId = 1L
    fun newId(): Long {
        var cand = nextId
        val used = (active + ended).map { it.id }.toSet()
        while (cand in used) cand++
        nextId = cand + 1
        return cand
    }

    /** 首次启动的演示账本（银行名真实、卡号尾号与金额均为示例，可在设置里清空后录入自己的）。 */
    fun seed() {
        if (active.isNotEmpty() || ended.isNotEmpty() || cards.isNotEmpty()) return
        active += Plan(
            id = newId(), bank = "招商银行", tail = "0000", principal = 12000.0, periods = 12,
            paidPeriods = 4, perInterest = 66.0, method = Method.FLAT_RATE,
            startDate = LocalDate.of(2026, 7, 18), statementDay = 25, dueDay = 10
        )
        active += Plan(
            id = newId(), bank = "交通银行", tail = "0001", principal = 6000.0, periods = 6,
            paidPeriods = 4, perInterest = 33.0, method = Method.FLAT_RATE,
            startDate = LocalDate.of(2026, 7, 18), statementDay = 20, dueDay = 10
        )
        ended += Plan(
            id = newId(), bank = "建设银行", tail = "0002", principal = 3000.0, periods = 3,
            paidPeriods = 3, perInterest = 16.5, method = Method.FLAT_RATE,
            startDate = LocalDate.of(2026, 4, 10), statementDay = 18, dueDay = 8
        )
        ended += Plan(
            id = newId(), bank = "中信银行", tail = "0003", principal = 5000.0, periods = 6,
            paidPeriods = 6, perInterest = 27.5, method = Method.FLAT_RATE,
            startDate = LocalDate.of(2026, 2, 10), statementDay = 15, dueDay = 5
        )
        cards.clear()
        cards += Card("招商银行", "0000", 25, 10)
        cards += Card("交通银行", "0001", 20, 10)
        cards += Card("建设银行", "0002", 18, 8)
        cards += Card("中信银行", "0003", 15, 5)
        savings.clear()
        savings += Savings("招行储蓄卡", 12480.0)
        savings += Savings("零钱", 1860.0)
    }

    fun reset() {
        active.clear(); ended.clear(); cards.clear(); savings.clear(); nextId = 1L
    }

    val incomeTotal: Double get() = income.sumOf { it.amount }

    /** 某个储蓄账户本月入账（收入账户挂过去的部分） */
    fun incomeTo(savingsName: String): Double =
        income.filter { it.savingsRef == savingsName }.sumOf { it.amount }

    /** 没挂账户的收入总额，用来提示配置不全 */
    val unassignedIncome: Double get() = income.filter { it.savingsRef.isBlank() }.sumOf { it.amount }
    val repay: Double get() = active.sumOf { it.thisMonthDue }
    val disposable: Double get() = incomeTotal - repay - fixedExpense

    /** 下个月应还合计 */
    val nextMonthRepay: Double get() = active.sumOf { it.nextMonthDue }

    /** 下个月可支配预算 */
    val nextMonthDisposable: Double get() = incomeTotal - nextMonthRepay - fixedExpense
    val remainingTotal: Double get() = active.sumOf { it.remaining }
    val totalPrincipal: Double get() = active.sumOf { it.principal }
    val paidPrincipalAll: Double get() = active.sumOf { it.paidPrincipal }
    val totalInterestAll: Double get() = active.sumOf { it.totalInterest }
    val paidInterestActive: Double get() = active.sumOf { it.paidInterest }

    /** 已付总利息：在还 + 已结束，都算 */
    val paidInterestTotal: Double get() = (active + ended).sumOf { it.paidInterest }
    /** 累计手续费（已发生+将发生） */
    val interestAllTime: Double get() = (active + ended).sumOf { it.totalInterest }

    val weightedApr: Double
        get() = if (remainingTotal <= 0.0) 0.0
        else active.sumOf { it.remaining * it.apr } / remainingTotal

    fun payNext(plan: Plan): Plan {
        val idx = active.indexOfFirst { it.id == plan.id }
        if (idx < 0) return plan
        val updated = plan.copy(paidPeriods = (plan.paidPeriods + 1).coerceAtMost(plan.periods))
        active[idx] = updated
        if (updated.finished) {
            active.removeAt(idx)
            ended.add(0, updated)
        }
        return updated
    }

    fun settle(plan: Plan): Plan {
        val idx = active.indexOfFirst { it.id == plan.id }
        if (idx < 0) return plan
        val updated = plan.copy(paidPeriods = plan.periods)
        active.removeAt(idx)
        ended.add(0, updated)
        return updated
    }

    fun nextIdAfter(v: Long) {
        if (v > nextId) nextId = v
    }

    fun add(plan: Plan) {
        active.add(plan)
    }

    fun find(id: Long): Plan? = (active + ended).firstOrNull { it.id == id }

    /** 用新版本替换同 id 的计划（不改动列表顺序） */
    fun replace(plan: Plan) {
        val ai = active.indexOfFirst { it.id == plan.id }
        if (ai >= 0) { active[ai] = plan; return }
        val ei = ended.indexOfFirst { it.id == plan.id }
        if (ei >= 0) ended[ei] = plan
    }

    /** 找（或按计划补）一张卡 */
    fun ensureCard(bank: String, tail: String, statementDay: Int = 25, dueDay: Int = 10): Card {
        cards.firstOrNull { it.bank == bank && it.tail == tail }?.let { return it }
        return Card(bank, tail, statementDay, dueDay).also { cards += it }
    }

    /** 改卡：同步所有引用它的计划 */
    fun updateCard(card: Card, bank: String, tail: String, stmt: Int, due: Int) {
        val oldBank = card.bank; val oldTail = card.tail
        card.bank = bank; card.tail = tail; card.statementDay = stmt; card.dueDay = due
        for (i in active.indices) {
            val p = active[i]
            if (p.bank == oldBank && p.tail == oldTail) active[i] = p.copy(bank = bank, tail = tail, statementDay = stmt, dueDay = due)
        }
        for (i in ended.indices) {
            val p = ended[i]
            if (p.bank == oldBank && p.tail == oldTail) ended[i] = p.copy(bank = bank, tail = tail, statementDay = stmt, dueDay = due)
        }
    }

    fun removeCard(card: Card) {
        cards.remove(card)
    }

    /** 信用额度合计（暂未建模，用待还+已还本金近似占位） */
    val creditOutstanding: Double get() = remainingTotal

    /** 未来 12 个月可支配 */
    fun series(months: Int = 12): List<Pair<String, Double>> =
        seriesDetail(months).map { it.first to it.third }

    /** (月份, 还款, 可支配) */
    fun seriesDetail(months: Int = 12): List<Triple<String, Double, Double>> {
        val out = ArrayList<Triple<String, Double, Double>>()
        var d = java.time.LocalDate.now().withDayOfMonth(1)
        repeat(months) {
            val due = active.sumOf { p ->
                val last = Calc.dueDates(Calc.firstDue(p.startDate, p.statementDay, p.dueDay), p.periods).last()
                if (!d.isAfter(last.withDayOfMonth(1))) p.perPay else 0.0
            }
            out.add(Triple("${d.monthValue}月", due, incomeTotal - fixedExpense - due))
            d = d.plusMonths(1)
        }
        return out
    }

    /** 接下来 N 次扣款 */
    fun upcoming(n: Int = 5): List<Triple<String, String, Double>> {
        data class D(val date: LocalDate, val label: String, val amount: Double)
        val all = ArrayList<D>()
        active.forEach { p ->
            val first = Calc.firstDue(p.startDate, p.statementDay, p.dueDay)
            Calc.dueDates(first, p.periods).forEachIndexed { i, d ->
                if (i >= p.paidPeriods) all.add(D(d, "${p.bank.take(2)} ${p.tail}", p.perPay))
            }
        }
        return all.sortedBy { it.date }.take(n).map {
            Triple("%02d-%02d".format(it.date.monthValue, it.date.dayOfMonth), it.label, it.amount)
        }
    }
}
