package com.rknepp.parity.relationships.ui

/**
 * Format integer cents plus an ISO-style currency code into a simple
 * display string, e.g. `1234` + `USD` -> `12.34 USD`. The ledger is
 * integer-cents only; this never does float math — it splits on the
 * minor unit with integer division and pads the fractional part.
 * Major units of 1,000 and above get thousands separators.
 *
 * Assumes a 2-decimal minor unit, which holds for every currency the
 * backend's `^[A-Z]{3}$` rule realistically targets in two-party use.
 * A future phase can special-case zero-decimal currencies if needed.
 */
fun formatCents(cents: Long, currencyCode: String): String {
    val sign = if (cents < 0) "-" else ""
    return "$sign${unsignedAmount(cents)} $currencyCode"
}

/**
 * Like [formatCents] but always carries an explicit sign, for ledger
 * deltas: `+6.17 USD` / `-6.17 USD`.
 */
fun formatCentsSigned(cents: Long, currencyCode: String): String {
    val sign = if (cents < 0) "-" else "+"
    return "$sign${unsignedAmount(cents)} $currencyCode"
}

private fun unsignedAmount(cents: Long): String {
    val abs = if (cents < 0) -cents else cents
    val major = abs / 100
    val minor = abs % 100
    val majorGrouped = major.toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
    return "$majorGrouped.${minor.toString().padStart(2, '0')}"
}
