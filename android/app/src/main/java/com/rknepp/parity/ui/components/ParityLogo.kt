package com.rknepp.parity.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Parity mark: a bordered rounded square holding a Spectral "P".
 * Reproduced in Compose per the Paper spec (no raster asset).
 */
@Composable
fun ParityLogo(modifier: Modifier = Modifier, size: Int = 56) {
    Box(
        modifier = modifier
            .size(size.dp)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(percent = 28),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "P",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = (size * 0.5).sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
