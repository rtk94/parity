package com.rknepp.parity.ui.theme

import androidx.compose.ui.graphics.Color

// Parity "Paper" palette — ink on warm paper, one green accent.
// Amber == pending / needs-a-party. Red == you owe / destructive.

// ── Light ────────────────────────────────────────────────
val InkLight = Color(0xFF14140F)
val PaperLight = Color(0xFFFAF8F3)
val PaperRaisedLight = Color(0xFFFFFFFF)
val GreenLight = Color(0xFF2F6B45)
val GreenContainerLight = Color(0xFFE7F0E9)
val AmberLight = Color(0xFFB07A1E)
val AmberContainerLight = Color(0xFFF7E9CC)
val RedLight = Color(0xFFA83F30)
val RedContainerLight = Color(0xFFF6DDD6)
val MutedLight = Color(0xFF7A776C)
val TrackLight = Color(0xFFEFEADD)
val RuleLight = Color(0xFFE7E2D5)
val RuleStrongLight = Color(0xFFDCD6C8)
val OnInkLight = Color(0xFFFAF8F3)

// ── Dark (ink inverts to paper-on-near-black) ────────────
val InkDark = Color(0xFFEEEADD)
val PaperDark = Color(0xFF17160F)
val PaperRaisedDark = Color(0xFF211F16)
val GreenDark = Color(0xFF7CC99A)
val GreenContainerDark = Color(0xFF1E3A29)
val AmberDark = Color(0xFFE0B45C)
val AmberContainerDark = Color(0xFF3A2F16)
val RedDark = Color(0xFFE58C7C)
val RedContainerDark = Color(0xFF52231B)
val MutedDark = Color(0xFF94907F)
val TrackDark = Color(0xFF211F16)
val RuleDark = Color(0xFF2C2A20)
val RuleStrongDark = Color(0xFF33301F)
val OnInkDark = Color(0xFF17160F)

// Avatar tint pool: stable per-name colors for initials avatars.
// Retained from the Phase 8 theme so InitialsAvatar keeps working; the
// Paper spec moves avatars to transparent-fill ink outlines, at which
// point this pool and the ParityExtendedColors.avatarPool role retire.
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
