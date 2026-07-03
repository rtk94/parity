package com.rknepp.parity.ui.components

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Render a backend ISO-8601 `Z`-suffixed timestamp as a short local
 * date: `Jun 12` for the current year, `Jun 12, 2025` otherwise.
 * Falls back to the raw string if parsing fails — display code should
 * never crash on a timestamp.
 */
fun formatIsoDate(iso: String): String {
    val local = try {
        java.time.Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (_: DateTimeParseException) {
        return iso
    }
    val pattern = if (local.year == LocalDate.now().year) "MMM d" else "MMM d, yyyy"
    return local.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
