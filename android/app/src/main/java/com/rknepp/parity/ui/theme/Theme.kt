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
 * Colors Material's scheme has no role for: the "owed to you" green and
 * the pending amber. Accessed via [ParityThemeDefaults.colors].
 */
@Immutable
data class ParityExtendedColors(
    val positive: Color,
    val positiveContainer: Color,
    val pending: Color,
    val pendingContainer: Color,
)

private val LightExtended = ParityExtendedColors(
    positive = GreenLight,
    positiveContainer = GreenContainerLight,
    pending = AmberLight,
    pendingContainer = AmberContainerLight,
)

private val DarkExtended = ParityExtendedColors(
    positive = GreenDark,
    positiveContainer = GreenContainerDark,
    pending = AmberDark,
    pendingContainer = AmberContainerDark,
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
    primaryContainer = TrackLight,
    onPrimaryContainer = InkLight,
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
    onErrorContainer = RedLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = TrackLight,
    onSurfaceVariant = MutedLight,
    outline = RuleStrongLight,
    outlineVariant = RuleLight,
    // Tonal surface-container ramp (used by NavigationBar, menus, etc.)
    // and the inverse roles (Snackbars) — mapped to Paper tones so
    // nothing falls back to Material's baseline lavender. surfaceTint is
    // transparent to keep elevated surfaces flat, per the Paper spec.
    surfaceTint = Color.Transparent,
    surfaceBright = PaperRaisedLight,
    surfaceDim = RuleLight,
    surfaceContainerLowest = PaperRaisedLight,
    surfaceContainerLow = PaperLight,
    surfaceContainer = TrackLight,
    surfaceContainerHigh = RuleLight,
    surfaceContainerHighest = RuleStrongLight,
    inverseSurface = InkLight,
    inverseOnSurface = PaperLight,
    inversePrimary = GreenDark,
)

private val DarkColorScheme = darkColorScheme(
    primary = InkDark,
    onPrimary = OnInkDark,
    primaryContainer = TrackDark,
    onPrimaryContainer = InkDark,
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
    onErrorContainer = RedDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = TrackDark,
    onSurfaceVariant = MutedDark,
    outline = RuleStrongDark,
    outlineVariant = RuleDark,
    // See LightColorScheme for the rationale behind these roles.
    surfaceTint = Color.Transparent,
    surfaceBright = RuleStrongDark,
    surfaceDim = PaperDark,
    surfaceContainerLowest = PaperDark,
    surfaceContainerLow = PaperRaisedDark,
    surfaceContainer = PaperRaisedDark,
    surfaceContainerHigh = RuleDark,
    surfaceContainerHighest = RuleStrongDark,
    inverseSurface = InkDark,
    inverseOnSurface = PaperDark,
    inversePrimary = GreenLight,
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
