package com.trexx.shakeytroll.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.SensorActivity
import com.trexx.shakeytroll.ble.SensorActivityTracker

/**
 * Sensor/baby-monitor mode at a glance: when the sensor last tripped, how often and how long it
 * has rocked in the last hour, and a strip of those bouts so "too often" or "too long" shows up
 * without doing sums. Triggers are inferred from run-state edges by [SensorActivityTracker].
 */
@Composable
fun SensorActivityCard(activity: SensorActivity, nowMs: Long) {
  val windowMs = SensorActivityTracker.WINDOW_MS
  val since = nowMs - windowMs
  val count = activity.triggersSince(since)
  val rockedMin = (activity.rockedMsSince(since, nowMs) / 60_000).toInt()
  val scheme = MaterialTheme.colorScheme

  Surface(
    shape = MaterialTheme.shapes.large,
    color = scheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
      Text(stringResource(R.string.sensor_title), style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(6.dp))

      val last = activity.lastTriggerMs
      val headline = when {
        last == null -> stringResource(R.string.sensor_no_triggers)
        activity.currentBout != null -> stringResource(R.string.sensor_rocking_now, ago(nowMs - last))
        else -> stringResource(R.string.sensor_last_trigger, ago(nowMs - last))
      }
      Text(headline, style = MaterialTheme.typography.bodyMedium)
      if (count > 0) {
        Text(
          pluralStringResource(R.plurals.sensor_triggers_summary, count, count, rockedMin),
          style = MaterialTheme.typography.bodySmall,
          color = scheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.height(10.dp))
      val description = pluralStringResource(R.plurals.sensor_timeline_desc, count, count, rockedMin)
      val track = scheme.surfaceContainerHighest
      val mark = scheme.primary
      Canvas(
        Modifier
          .fillMaxWidth()
          .height(12.dp)
          .semantics { contentDescription = description },
      ) {
        val radius = CornerRadius(4.dp.toPx())
        drawRoundRect(track, cornerRadius = radius)
        val gap = 2.dp.toPx()
        val minWidth = 3.dp.toPx() // a few-second bout must still be visible
        activity.bouts.forEach { b ->
          val x0 = ((b.startMs - since).toFloat() / windowMs * size.width).coerceIn(0f, size.width)
          val x1 = (((b.endMs ?: nowMs) - since).toFloat() / windowMs * size.width).coerceIn(0f, size.width)
          // A surface-coloured gap on the right keeps back-to-back bouts readable as separate.
          val width = (x1 - x0 - gap).coerceAtLeast(minWidth).coerceAtMost(size.width - x0)
          if (width > 0f) drawRoundRect(mark, Offset(x0, 0f), Size(width, size.height), radius)
        }
      }
      Spacer(Modifier.height(4.dp))
      Row(Modifier.fillMaxWidth()) {
        Text(
          stringResource(R.string.sensor_timeline_start),
          style = MaterialTheme.typography.labelSmall,
          color = scheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        Text(
          stringResource(R.string.sensor_timeline_end),
          style = MaterialTheme.typography.labelSmall,
          color = scheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ago(ms: Long): String {
  val minutes = (ms / 60_000).toInt()
  return if (minutes < 1) stringResource(R.string.sensor_just_now)
  else pluralStringResource(R.plurals.sensor_minutes_ago, minutes, minutes)
}
