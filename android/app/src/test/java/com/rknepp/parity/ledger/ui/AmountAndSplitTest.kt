package com.rknepp.parity.ledger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParsingTest {

    @Test
    fun parsesWholeAmounts() {
        assertEquals(1200L, parseAmountToCents("12"))
        assertEquals(100L, parseAmountToCents("1"))
    }

    @Test
    fun parsesDecimalAmounts() {
        assertEquals(1234L, parseAmountToCents("12.34"))
        assertEquals(1230L, parseAmountToCents("12.3"))
        assertEquals(1200L, parseAmountToCents("12."))
    }

    @Test
    fun parsesThousandsSeparators() {
        assertEquals(123456L, parseAmountToCents("1,234.56"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseAmountToCents(""))
        assertNull(parseAmountToCents("abc"))
        assertNull(parseAmountToCents("12a"))
        assertNull(parseAmountToCents("12.345"))
        assertNull(parseAmountToCents("12.3.4"))
        assertNull(parseAmountToCents("-5"))
        assertNull(parseAmountToCents(".50"))
    }
}

class SplitMathTest {

    private fun state(totalCents: Long, percent: Int) = CreateExpenseState(
        totalCents = totalCents,
        counterpartySharePercent = percent,
    )

    @Test
    fun evenSplitOfEvenTotal() {
        val s = state(1000, 50)
        assertEquals(500L, s.counterpartyShareCents)
        assertEquals(500L, s.payerShareCents)
    }

    @Test
    fun oddCentTotalsRoundHalfUpAndStillSum() {
        val s = state(1001, 50)
        assertEquals(501L, s.counterpartyShareCents)
        assertEquals(500L, s.payerShareCents)
        assertEquals(s.totalCents, s.counterpartyShareCents + s.payerShareCents)
    }

    @Test
    fun extremesAssignEverythingToOneSide() {
        assertEquals(0L, state(1234, 0).counterpartyShareCents)
        assertEquals(1234L, state(1234, 0).payerShareCents)
        assertEquals(1234L, state(1234, 100).counterpartyShareCents)
        assertEquals(0L, state(1234, 100).payerShareCents)
    }

    @Test
    fun sharesAlwaysSumToTotal() {
        for (total in listOf(1L, 3L, 99L, 101L, 12345L)) {
            for (pct in 0..100) {
                val s = state(total, pct)
                assertEquals(
                    "total=$total pct=$pct",
                    total,
                    s.counterpartyShareCents + s.payerShareCents,
                )
            }
        }
    }

    @Test
    fun integerMathMatchesExpectedRounding() {
        // 33% of 10.00 = 3.30; 33% of 0.01 = 0.0033 -> rounds to 0.
        assertEquals(330L, state(1000, 33).counterpartyShareCents)
        assertEquals(0L, state(1, 33).counterpartyShareCents)
        // 50% of 0.01 rounds half up to 0.01.
        assertEquals(1L, state(1, 50).counterpartyShareCents)
    }
}
