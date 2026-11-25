package com.zglinus.installment.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CalcTest {

    private val p = 12000.0
    private val n = 12
    private val interest = 792.0

    @Test fun `等本等息年化 12_18`() {
        val apr = Calc.apr(Method.FLAT_RATE, p, n, interest)
        assertEquals(12.18, Math.round(apr * 10000) / 100.0, "等本等息")
    }

    @Test fun `等额本息同利息年化 11_97`() {
        val apr = Calc.apr(Method.EQUAL_INSTALLMENT, p, n, interest)
        assertEquals(11.97, Math.round(apr * 10000) / 100.0, "等额本息")
    }

    @Test fun `等额本金与等本等息同值`() {
        val a = Calc.apr(Method.EQUAL_PRINCIPAL, p, n, interest)
        val b = Calc.apr(Method.FLAT_RATE, p, n, interest)
        assertEquals(b, a, 1e-9)
    }

    @Test fun `免息为 0`() {
        assertEquals(0.0, Calc.apr(Method.INTEREST_FREE, p, n, 0.0))
    }

    @Test fun `年化反算总利息回到 792`() {
        for (m in Method.entries) {
            if (m == Method.INTEREST_FREE) continue
            val apr = Calc.apr(m, p, n, interest)
            val back = Calc.interestFromApr(m, p, n, apr)
            assertEquals(interest, Math.round(back * 100) / 100.0, "round-trip $m")
        }
    }

    @Test fun `首期还款日 7-18 账单 25 还款 10 落在 8-10`() {
        val d = Calc.firstDue(LocalDate.of(2026, 7, 18), 25, 10)
        assertEquals(LocalDate.of(2026, 8, 10), d)
    }

    @Test fun `分期日晚于账单日顺延一期`() {
        val d = Calc.firstDue(LocalDate.of(2026, 7, 28), 25, 10)
        assertEquals(LocalDate.of(2026, 9, 10), d)
    }
}
