package com.rknepp.parity.relationships.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatTest {

    @Test
    fun formatsZero() {
        assertEquals("0.00 USD", formatCents(0, "USD"))
    }

    @Test
    fun formatsSmallAmounts() {
        assertEquals("0.05 USD", formatCents(5, "USD"))
        assertEquals("12.34 USD", formatCents(1234, "USD"))
    }

    @Test
    fun formatsNegativeAmounts() {
        assertEquals("-12.34 EUR", formatCents(-1234, "EUR"))
    }

    @Test
    fun groupsThousands() {
        assertEquals("1,234.56 USD", formatCents(123456, "USD"))
        assertEquals("1,234,567.89 USD", formatCents(123456789, "USD"))
        assertEquals("-1,000.00 USD", formatCents(-100000, "USD"))
    }

    @Test
    fun signedAlwaysCarriesSign() {
        assertEquals("+6.17 USD", formatCentsSigned(617, "USD"))
        assertEquals("-6.17 USD", formatCentsSigned(-617, "USD"))
        assertEquals("+0.00 USD", formatCentsSigned(0, "USD"))
    }
}
