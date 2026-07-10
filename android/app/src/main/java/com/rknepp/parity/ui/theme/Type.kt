package com.rknepp.parity.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rknepp.parity.R

// Paper type system. Titles + money use Spectral (serif); all UI uses
// Hanken Grotesk (grotesk sans). Both are bundled offline under res/font/
// (OFL — see android/THIRD_PARTY_NOTICES/) so rendering never depends on
// Google Play Services or a network fetch.

// Spectral ships static weights.
private val Spectral = FontFamily(
    Font(R.font.spectral_regular, FontWeight.Normal),
    Font(R.font.spectral_medium, FontWeight.Medium),
    Font(R.font.spectral_semibold, FontWeight.SemiBold),
)

// Hanken Grotesk is a single variable font; select weights off the wght axis.
@OptIn(ExperimentalTextApi::class)
private fun hankenWeight(weight: FontWeight, axis: Int) =
    Font(
        R.font.hanken_grotesk_variable,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
    )

private val Hanken = FontFamily(
    hankenWeight(FontWeight.Normal, 400),
    hankenWeight(FontWeight.Medium, 500),
    hankenWeight(FontWeight.SemiBold, 600),
    hankenWeight(FontWeight.Bold, 700),
)

// UI text -> Hanken; titles -> Spectral. Money uses ParityMoney below.
val ParityTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp),
)

// Money is always serif SemiBold. Apply color + sign at the call site.
object ParityMoney {
    val hero = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 58.sp)
    val screen = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp)
    val row = TextStyle(fontFamily = Spectral, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
}
