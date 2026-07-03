package com.rknepp.parity.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Colors that Material's scheme has no role for: the "money owed to
 * you" green and the avatar tint pool. Accessed via [ParityTheme.colors].
 */
@Immutable
data class ParityExtendedColors(
    val positive: Color,
    val positiveContainer: Color,
    val avatarPool: List<Color>,
)

private val LightExtendedColors = ParityExtendedColors(
    positive = MoneyPositiveLight,
    positiveContainer = MoneyPositiveContainerLight,
    avatarPool = AvatarPoolLight,
)

private val DarkExtendedColors = ParityExtendedColors(
    positive = MoneyPositiveDark,
    positiveContainer = MoneyPositiveContainerDark,
    avatarPool = AvatarPoolDark,
)

val LocalParityExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** Accessor for [ParityExtendedColors] inside a [ParityTheme]. */
object ParityThemeDefaults {
    val colors: ParityExtendedColors
        @Composable get() = LocalParityExtendedColors.current
}

private val LightColorScheme = lightColorScheme(
    primary = ParityTealLight,
    onPrimary = ParityOnTealLight,
    primaryContainer = ParityTealContainerLight,
    onPrimaryContainer = ParityOnTealContainerLight,
    secondary = ParitySecondaryLight,
    onSecondary = ParityOnSecondaryLight,
    secondaryContainer = ParitySecondaryContainerLight,
    onSecondaryContainer = ParityOnSecondaryContainerLight,
    tertiary = ParityGreenLight,
    onTertiary = ParityOnGreenLight,
    tertiaryContainer = ParityGreenContainerLight,
    onTertiaryContainer = ParityOnGreenContainerLight,
    error = ParityErrorLight,
    onError = ParityOnErrorLight,
    errorContainer = ParityErrorContainerLight,
    onErrorContainer = ParityOnErrorContainerLight,
    background = ParityBackgroundLight,
    onBackground = ParityOnBackgroundLight,
    surface = ParitySurfaceLight,
    onSurface = ParityOnSurfaceLight,
    surfaceVariant = ParitySurfaceVariantLight,
    onSurfaceVariant = ParityOnSurfaceVariantLight,
    outline = ParityOutlineLight,
    outlineVariant = ParityOutlineVariantLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = ParityTealDark,
    onPrimary = ParityOnTealDark,
    primaryContainer = ParityTealContainerDark,
    onPrimaryContainer = ParityOnTealContainerDark,
    secondary = ParitySecondaryDark,
    onSecondary = ParityOnSecondaryDark,
    secondaryContainer = ParitySecondaryContainerDark,
    onSecondaryContainer = ParityOnSecondaryContainerDark,
    tertiary = ParityGreenDark,
    onTertiary = ParityOnGreenDark,
    tertiaryContainer = ParityGreenContainerDark,
    onTertiaryContainer = ParityOnGreenContainerDark,
    error = ParityErrorDark,
    onError = ParityOnErrorDark,
    errorContainer = ParityErrorContainerDark,
    onErrorContainer = ParityOnErrorContainerDark,
    background = ParityBackgroundDark,
    onBackground = ParityOnBackgroundDark,
    surface = ParitySurfaceDark,
    onSurface = ParityOnSurfaceDark,
    surfaceVariant = ParitySurfaceVariantDark,
    onSurfaceVariant = ParityOnSurfaceVariantDark,
    outline = ParityOutlineDark,
    outlineVariant = ParityOutlineVariantDark,
)

/**
 * App theme. Defaults to the Parity brand palette; set [dynamicColor]
 * to true to prefer Material You wallpaper colors on Android 12+.
 */
@Composable
fun ParityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalParityExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ParityTypography,
            content = content,
        )
    }
}
