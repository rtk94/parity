package com.rknepp.parity.relationships.ui

/**
 * Format integer cents plus an ISO-style currency code into a simple
 * display string, e.g. `1234` + `USD` -> `12.34 USD`. The ledger is
 * integer-cents only; this never does float math — it splits on the
 * minor unit with integer division and pads the fractional part.
 *
 * Assumes a 2-decimal minor unit, which holds for every currency the
 * backend's `^[A-Z]{3}$` rule realistically targets in two-party use.
 * A future phase can special-case zero-decimal currencies if needed.
 */
fun formatCents(cents: Long, currencyCode: String): String {
    val negative = cents < 0
    val abs = if (negative) -cents else cents
    val major = abs / 100
    val minor = abs % 100
    val sign = if (negative) "-" else ""
    return "$sign$major.${minor.toString().padStart(2, '0')} $currencyCode"
}
