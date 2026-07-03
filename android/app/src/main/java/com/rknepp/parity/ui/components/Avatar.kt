package com.rknepp.parity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rknepp.parity.ui.theme.ParityThemeDefaults

/**
 * Initials avatar with a color chosen deterministically from the
 * display name, so a given counterparty always renders the same tint.
 */
@Composable
fun InitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val pool = ParityThemeDefaults.colors.avatarPool
    // Mod-then-normalize instead of absoluteValue: Int.MIN_VALUE has
    // no positive counterpart and would index out of bounds.
    val tint = pool[((name.hashCode() % pool.size) + pool.size) % pool.size]
    val initials = name
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
            ),
            // The pool color doubles as the text color over its own
            // 22% alpha wash; contrast is sufficient in both themes.
            color = tint,
        )
    }
}
