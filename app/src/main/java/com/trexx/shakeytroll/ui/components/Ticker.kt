package com.trexx.shakeytroll.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.util.Locale

/** SystemClock.elapsedRealtime(), refreshed every [periodMs] — drives "N min ago" and bout timers. */
@Composable
fun rememberElapsedRealtime(periodMs: Long = 1_000L): State<Long> =
  produceState(SystemClock.elapsedRealtime(), periodMs) {
    while (true) {
      delay(periodMs)
      value = SystemClock.elapsedRealtime()
    }
  }

/** A duration as M:SS, or H:MM:SS from an hour up. */
fun formatDuration(ms: Long): String {
  val s = (ms / 1000).coerceAtLeast(0)
  return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
  else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
