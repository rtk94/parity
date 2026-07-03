package com.rknepp.parity.ui.theme

import androidx.compose.ui.graphics.Color

// Parity brand palette. Primary is a deep teal (trust/balance),
// tertiary is a green reserved for "money owed to you" accents.
// Tones follow the Material 3 tonal-role conventions.

// Light scheme
val ParityTealLight = Color(0xFF00696D)
val ParityOnTealLight = Color(0xFFFFFFFF)
val ParityTealContainerLight = Color(0xFF9CF1F5)
val ParityOnTealContainerLight = Color(0xFF002021)

val ParitySecondaryLight = Color(0xFF4A6365)
val ParityOnSecondaryLight = Color(0xFFFFFFFF)
val ParitySecondaryContainerLight = Color(0xFFCCE8E9)
val ParityOnSecondaryContainerLight = Color(0xFF051F21)

val ParityGreenLight = Color(0xFF3B6939)
val ParityOnGreenLight = Color(0xFFFFFFFF)
val ParityGreenContainerLight = Color(0xFFBCF0B4)
val ParityOnGreenContainerLight = Color(0xFF002204)

val ParityErrorLight = Color(0xFFBA1A1A)
val ParityOnErrorLight = Color(0xFFFFFFFF)
val ParityErrorContainerLight = Color(0xFFFFDAD6)
val ParityOnErrorContainerLight = Color(0xFF410002)

val ParityBackgroundLight = Color(0xFFF9FBFA)
val ParityOnBackgroundLight = Color(0xFF171D1D)
val ParitySurfaceLight = Color(0xFFF9FBFA)
val ParityOnSurfaceLight = Color(0xFF171D1D)
val ParitySurfaceVariantLight = Color(0xFFDAE4E5)
val ParityOnSurfaceVariantLight = Color(0xFF3F4849)
val ParityOutlineLight = Color(0xFF6F7979)
val ParityOutlineVariantLight = Color(0xFFBEC8C9)

// Dark scheme
val ParityTealDark = Color(0xFF80D4D9)
val ParityOnTealDark = Color(0xFF003739)
val ParityTealContainerDark = Color(0xFF004F52)
val ParityOnTealContainerDark = Color(0xFF9CF1F5)

val ParitySecondaryDark = Color(0xFFB0CCCE)
val ParityOnSecondaryDark = Color(0xFF1B3436)
val ParitySecondaryContainerDark = Color(0xFF324B4D)
val ParityOnSecondaryContainerDark = Color(0xFFCCE8E9)

val ParityGreenDark = Color(0xFFA1D399)
val ParityOnGreenDark = Color(0xFF0A390F)
val ParityGreenContainerDark = Color(0xFF235024)
val ParityOnGreenContainerDark = Color(0xFFBCF0B4)

val ParityErrorDark = Color(0xFFFFB4AB)
val ParityOnErrorDark = Color(0xFF690005)
val ParityErrorContainerDark = Color(0xFF93000A)
val ParityOnErrorContainerDark = Color(0xFFFFDAD6)

val ParityBackgroundDark = Color(0xFF0E1515)
val ParityOnBackgroundDark = Color(0xFFDDE4E4)
val ParitySurfaceDark = Color(0xFF0E1515)
val ParityOnSurfaceDark = Color(0xFFDDE4E4)
val ParitySurfaceVariantDark = Color(0xFF3F4849)
val ParityOnSurfaceVariantDark = Color(0xFFBEC8C9)
val ParityOutlineDark = Color(0xFF899393)
val ParityOutlineVariantDark = Color(0xFF3F4849)

// Semantic money colors, outside the Material scheme. "Positive" is
// money owed to you; negative reuses the scheme's error role.
val MoneyPositiveLight = Color(0xFF1B6E25)
val MoneyPositiveContainerLight = Color(0xFFA5F2A8)
val MoneyPositiveDark = Color(0xFF8BD88F)
val MoneyPositiveContainerDark = Color(0xFF005313)

// Avatar tint pool: stable per-name colors for initials avatars.
val AvatarPoolLight = listOf(
    Color(0xFF00696D),
    Color(0xFF745B00),
    Color(0xFF8E4956),
    Color(0xFF3B6939),
    Color(0xFF5D5791),
    Color(0xFF8D4E2A),
)
val AvatarPoolDark = listOf(
    Color(0xFF80D4D9),
    Color(0xFFEBC248),
    Color(0xFFFFB1C0),
    Color(0xFFA1D399),
    Color(0xFFC7BFFF),
    Color(0xFFFFB68E),
)
