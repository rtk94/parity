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

/**
 * Human-readable file size for attachment rows, e.g. `840 B`, `12.3 KB`,
 * `1.4 MB`. Uses binary (1024) steps and one decimal above bytes.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "${"%.1f".format(value)} ${units[unitIndex]}"
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
