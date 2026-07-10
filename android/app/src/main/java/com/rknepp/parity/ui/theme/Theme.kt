package com.rknepp.parity.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors Material's scheme has no role for: the "owed to you" green,
 * the pending amber, and the avatar tint pool. Accessed via
 * [ParityThemeDefaults.colors].
 *
 * `avatarPool` is retained from Phase 8 so [com.rknepp.parity.ui.components.InitialsAvatar]
 * keeps compiling; it retires when avatars move to the Paper ink-outline style.
 */
@Immutable
data class ParityExtendedColors(
    val positive: Color,
    val positiveContainer: Color,
    val pending: Color,
    val pendingContainer: Color,
    val avatarPool: List<Color>,
)

private val LightExtended = ParityExtendedColors(
    positive = GreenLight,
    positiveContainer = GreenContainerLight,
    pending = AmberLight,
    pendingContainer = AmberContainerLight,
    avatarPool = AvatarPoolLight,
)

private val DarkExtended = ParityExtendedColors(
    positive = GreenDark,
    positiveContainer = GreenContainerDark,
    pending = AmberDark,
    pendingContainer = AmberContainerDark,
    avatarPool = AvatarPoolDark,
)

val LocalParityExtendedColors = staticCompositionLocalOf { LightExtended }

/** Accessor for [ParityExtendedColors] inside a [ParityTheme]. */
object ParityThemeDefaults {
    val colors: ParityExtendedColors
        @Composable get() = LocalParityExtendedColors.current
}

private val LightColorScheme = lightColorScheme(
    primary = InkLight,
    onPrimary = OnInkLight,
    secondary = MutedLight,
    onSecondary = OnInkLight,
    secondaryContainer = TrackLight,
    onSecondaryContainer = InkLight,
    tertiary = GreenLight,
    onTertiary = OnInkLight,
    tertiaryContainer = GreenContainerLight,
    onTertiaryContainer = InkLight,
    error = RedLight,
    onError = OnInkLight,
    errorContainer = RedContainerLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = TrackLight,
    onSurfaceVariant = MutedLight,
    outline = RuleStrongLight,
    outlineVariant = RuleLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = InkDark,
    onPrimary = OnInkDark,
    secondary = MutedDark,
    onSecondary = OnInkDark,
    secondaryContainer = TrackDark,
    onSecondaryContainer = InkDark,
    tertiary = GreenDark,
    onTertiary = OnInkDark,
    tertiaryContainer = GreenContainerDark,
    onTertiaryContainer = InkDark,
    error = RedDark,
    onError = OnInkDark,
    errorContainer = RedContainerDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = TrackDark,
    onSurfaceVariant = MutedDark,
    outline = RuleStrongDark,
    outlineVariant = RuleDark,
)

/**
 * App theme — the Paper design system. Follows the system light/dark
 * setting. Opinionated palette; no Material You dynamic color.
 */
@Composable
fun ParityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtended else LightExtended
    CompositionLocalProvider(LocalParityExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ParityTypography,
            shapes = ParityShapes,
            content = content,
        )
    }
}
