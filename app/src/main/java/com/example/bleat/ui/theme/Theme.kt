package com.example.bleat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun SleepytrollTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) = MaterialTheme(
  colorScheme = if (darkTheme) NightColors else DayColors,
  typography = SleepytrollTypography,
  shapes = SleepytrollShapes,
  content = content,
)
