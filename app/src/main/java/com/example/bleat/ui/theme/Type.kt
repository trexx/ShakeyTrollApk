package com.example.bleat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.bleat.R

// Bundled static instances (license: assets/fonts/OFL-Nunito.txt) so the app looks the same
// offline and on first run — no downloadable-fonts dependency on Play Services.
val Nunito = FontFamily(
  Font(R.font.nunito_medium, FontWeight.Medium),
  Font(R.font.nunito_semibold, FontWeight.SemiBold),
  Font(R.font.nunito_bold, FontWeight.Bold),
)

private val Default = Typography()

// Nunito carries the display/title/label voice; body text stays on the system font.
val SleepytrollTypography = Typography(
  displayLarge = Default.displayLarge.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 64.sp),
  displaySmall = Default.displaySmall.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold),
  headlineSmall = Default.headlineSmall.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold),
  titleLarge = Default.titleLarge.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold),
  titleMedium = Default.titleMedium.copy(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
  titleSmall = Default.titleSmall.copy(fontFamily = Nunito, fontWeight = FontWeight.SemiBold),
  labelLarge = Default.labelLarge.copy(fontFamily = Nunito, fontWeight = FontWeight.SemiBold),
  labelMedium = Default.labelMedium.copy(fontFamily = Nunito, fontWeight = FontWeight.SemiBold),
)
