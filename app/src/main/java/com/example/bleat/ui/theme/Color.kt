package com.example.bleat.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Night palette — warm indigo/plum with an amber "night-light" accent. This is the app's
// primary identity: dim and warm enough to glance at next to a sleeping baby.
val NightBg = Color(0xFF14121F)
val NightSurface = Color(0xFF1F1C2E)
val NightSurfaceHigh = Color(0xFF262238)
val Amber = Color(0xFFF2B879)
val AmberDeep = Color(0xFF3A2B1A)
val AmberBright = Color(0xFFF7DDBB)
val Lavender = Color(0xFFA79FC9)
val TextSoft = Color(0xFFEDEAF4)
val TextDim = Color(0xFF9A93B0)
val RoseMuted = Color(0xFFE08A8A)

val NightColors = darkColorScheme(
  primary = Amber,
  onPrimary = Color(0xFF2C1D0D),
  primaryContainer = AmberDeep,
  onPrimaryContainer = AmberBright,
  inversePrimary = Color(0xFFA66A26),
  secondary = Lavender,
  onSecondary = Color(0xFF211D33),
  secondaryContainer = Color(0xFF322C4A),
  onSecondaryContainer = Color(0xFFDDD8F0),
  tertiary = Color(0xFF8FA3C9),
  onTertiary = Color(0xFF1A2233),
  tertiaryContainer = Color(0xFF2A3448),
  onTertiaryContainer = Color(0xFFD3DEF2),
  background = NightBg,
  onBackground = TextSoft,
  surface = NightSurface,
  onSurface = TextSoft,
  surfaceVariant = Color(0xFF2A2640),
  onSurfaceVariant = TextDim,
  surfaceTint = Amber,
  inverseSurface = TextSoft,
  inverseOnSurface = Color(0xFF242030),
  error = RoseMuted,
  onError = Color(0xFF330F0F),
  errorContainer = Color(0xFF3A2230),
  onErrorContainer = Color(0xFFF2C7C7),
  outline = Color(0xFF45405C),
  outlineVariant = Color(0xFF2E2A44),
  // ModalBottomSheet/cards resolve against surfaceContainer* — set them so sheets stay plum, not gray.
  surfaceContainerLowest = Color(0xFF110F1A),
  surfaceContainerLow = Color(0xFF1B1929),
  surfaceContainer = NightSurface,
  surfaceContainerHigh = NightSurfaceHigh,
  surfaceContainerHighest = Color(0xFF2C2841),
  surfaceDim = Color(0xFF110F1A),
  surfaceBright = Color(0xFF322E47),
)

// Daytime scheme — same family in daylight: lavender-tinted neutrals, amber deepened for contrast.
val DayColors = lightColorScheme(
  primary = Color(0xFFA66A26),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFF6DFC2),
  onPrimaryContainer = Color(0xFF3A2B1A),
  inversePrimary = Amber,
  secondary = Color(0xFF625A8A),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFE5E0F5),
  onSecondaryContainer = Color(0xFF211D33),
  tertiary = Color(0xFF4A5D80),
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Color(0xFFD9E2F5),
  onTertiaryContainer = Color(0xFF1A2233),
  background = Color(0xFFF6F3F9),
  onBackground = Color(0xFF241F33),
  surface = Color(0xFFFCFAFE),
  onSurface = Color(0xFF241F33),
  surfaceVariant = Color(0xFFE8E3F0),
  onSurfaceVariant = Color(0xFF6D6786),
  surfaceTint = Color(0xFFA66A26),
  inverseSurface = Color(0xFF2A2536),
  inverseOnSurface = Color(0xFFF2EEF8),
  error = Color(0xFFA65454),
  onError = Color(0xFFFFFFFF),
  errorContainer = Color(0xFFF6D9D9),
  onErrorContainer = Color(0xFF4A1F1F),
  outline = Color(0xFF7A7492),
  outlineVariant = Color(0xFFCBC5DA),
  surfaceContainerLowest = Color(0xFFFFFFFF),
  surfaceContainerLow = Color(0xFFF1EDF7),
  surfaceContainer = Color(0xFFECE7F3),
  surfaceContainerHigh = Color(0xFFE6E1EF),
  surfaceContainerHighest = Color(0xFFE0DAEB),
  surfaceDim = Color(0xFFD8D2E2),
  surfaceBright = Color(0xFFFCFAFE),
)
