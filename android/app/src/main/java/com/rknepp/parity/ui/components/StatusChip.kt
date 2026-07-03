package com.rknepp.parity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rknepp.parity.ui.theme.ParityThemeDefaults

/**
 * Small tonal pill for ledger-entry and relationship statuses.
 * Pending = secondary (amber-ish attention handled by copy, tone by
 * scheme), confirmed/accepted = positive green, discarded/rejected =
 * muted outline, reversal = error tone.
 */
@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val (container, content, label) = statusChipColors(status)
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun statusChipColors(status: String): Triple<Color, Color, String> {
    val scheme = MaterialTheme.colorScheme
    val extended = ParityThemeDefaults.colors
    return when (status.lowercase()) {
        "pending" -> Triple(
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
            "Pending",
        )
        "confirmed" -> Triple(
            extended.positiveContainer.copy(alpha = 0.6f),
            extended.positive,
            "Confirmed",
        )
        "accepted" -> Triple(
            extended.positiveContainer.copy(alpha = 0.6f),
            extended.positive,
            "Active",
        )
        "discarded" -> Triple(
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
            "Discarded",
        )
        "rejected" -> Triple(
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
            "Rejected",
        )
        else -> Triple(
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
            status.replaceFirstChar { it.uppercase() },
        )
    }
}

/** Static pill for a currency code, e.g. `USD`. */
@Composable
fun CurrencyChip(code: String, modifier: Modifier = Modifier) {
    Text(
        text = code,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
