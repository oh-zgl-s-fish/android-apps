package com.zglinus.installment.core

import java.time.LocalDate
import kotlin.math.pow

object Calc {

    /** 折算年化：等本等息按 IRR 近似；等额本金/本息按名义利率 */
    fun apr(method: Method, principal: Double, periods: Int, totalInterest: Double): Double {
        if (principal <= 0.0 || periods <= 0) return 0.0
        if (totalInterest <= 0.0) return 0.0
        return when (method) {
            Method.INTEREST_FREE -> 0.0
            Method.FLAT_RATE -> 24 * totalInterest / ((periods + 1) * principal)
            Method.EQUAL_PRINCIPAL -> 12 * (2 * totalInterest / (principal * (periods + 1)))
            Method.EQUAL_INSTALLMENT -> {
                val a = (principal + totalInterest) / periods
                var lo = 1e-9
                var hi = 1.0
                repeat(200) {
                    val r = (lo + hi) / 2
                    val f = principal * r * (1 + r).pow(periods) / ((1 + r).pow(periods) - 1) - a
                    if (f > 0) hi = r else lo = r
                }
                12 * (lo + hi) / 2
            }
        }
    }

    /** 总利息 ⇄ 年化 双向 */
    fun interestFromApr(method: Method, principal: Double, periods: Int, apr: Double): Double {
        if (principal <= 0.0 || periods <= 0) return 0.0
        if (apr <= 0.0) return 0.0
        return when (method) {
            Method.INTEREST_FREE -> 0.0
            Method.FLAT_RATE -> apr * (periods + 1) * principal / 24
            Method.EQUAL_PRINCIPAL -> apr * principal * (periods + 1) / 24
            Method.EQUAL_INSTALLMENT -> {
                val r = apr / 12
                val a = principal * r * (1 + r).pow(periods) / ((1 + r).pow(periods) - 1)
                a * periods - principal
            }
        }
    }

    /** 首期还款日：分期日 ≤ 账单日 → 本期账单；还款日 ≤ 账单日 → 次月 */
    fun firstDue(installDate: LocalDate, statementDay: Int, dueDay: Int): LocalDate {
        var y = installDate.year
        var m = installDate.monthValue
        if (installDate.dayOfMonth > statementDay) {
            m += 1
            if (m > 12) { m = 1; y += 1 }
        }
        if (dueDay <= statementDay) {
            m += 1
            if (m > 12) { m = 1; y += 1 }
        }
        return LocalDate.of(y, m, dueDay)
    }

    fun dueDates(first: LocalDate, periods: Int): List<LocalDate> =
        (0 until periods).map { first.plusMonths(it.toLong()) }
}
