package com.trexx.shakeytroll.ui.components

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.DeviceInfo
import com.trexx.shakeytroll.ble.Telemetry
import com.trexx.shakeytroll.commands.CommandUiState
import com.trexx.shakeytroll.commands.SleepytrollCommands
import kotlin.math.roundToInt

@Composable
fun ControlSections(
  byId: Map<String, CommandUiState>,
  telemetry: Telemetry?,
  deviceInfo: DeviceInfo?,
  enabled: Boolean,
  keepAlive: Boolean,
  keepAliveStatus: String?,
  onKeepAlive: (Boolean) -> Unit,
  onSlider: (String, Int) -> Unit,
  onOption: (String, Int) -> Unit,
  onAction: (String) -> Unit,
) {
  byId["mode"]?.let { mode ->
    SectionCard(stringResource(mode.labelRes)) {
      SegmentedOptions(mode, enabled, onOption)
    }
    Spacer(Modifier.height(12.dp))
  }

  byId["sp"]?.let { sp ->
    SectionCard(stringResource(sp.labelRes)) {
      SegmentedOptions(sp, enabled, onOption)
    }
    Spacer(Modifier.height(12.dp))
  }

  val sound = byId["sh"]
  val movement = byId["au"]
  if (sound != null || movement != null) {
    SectionCard(stringResource(R.string.section_sensitivity)) {
      sound?.let {
        LabeledSlider(it, enabled, name = stringResource(R.string.slider_sound), onSlider = onSlider)
      }
      if (sound != null && movement != null) Spacer(Modifier.height(10.dp))
      movement?.let {
        LabeledSlider(it, enabled, name = stringResource(R.string.slider_movement), onSlider = onSlider)
      }
    }
    Spacer(Modifier.height(12.dp))
  }

  byId["st"]?.let { timer ->
    SectionCard(stringResource(R.string.section_timer)) {
      // Official-app rule: total motor time is capped at 180 min, so the timer can only be set to
      // what is left of that budget (channel-3 motor minutes); the ViewModel shrinks the max.
      val motor = deviceInfo?.motorMinutes
      val budget = motor?.let { SleepytrollCommands.RUN_TIMER_CAP_MIN - it }
      val exhausted = budget != null && budget < timer.min
      LabeledSlider(
        timer,
        enabled && !exhausted,
        name = stringResource(R.string.slider_run_timer),
        continuous = true,
        valueLabel = { stringResource(R.string.minutes_value, it) },
        onSlider = onSlider,
      )
      when {
        exhausted -> Text(
          stringResource(R.string.timer_budget_exhausted, motor ?: 0, SleepytrollCommands.RUN_TIMER_CAP_MIN),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
        budget != null -> Text(
          stringResource(R.string.timer_budget_left, budget),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (telemetry?.running == true && telemetry.timerSeconds > 0) {
        Text(
          stringResource(R.string.timer_time_left, telemetry.timerText),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.keepalive_title), style = MaterialTheme.typography.bodyMedium)
          Text(
            stringResource(R.string.keepalive_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = keepAlive, onCheckedChange = onKeepAlive, enabled = enabled)
      }
      keepAliveStatus?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
      }
    }
    Spacer(Modifier.height(12.dp))
  }

  SectionCard(stringResource(R.string.section_device)) {
    val counters = listOfNotNull(
      deviceInfo?.motorMinutes?.let { stringResource(R.string.device_motor_minutes, it, SleepytrollCommands.RUN_TIMER_CAP_MIN) },
      deviceInfo?.deviceTotalMin?.let { stringResource(R.string.device_total_minutes, it) },
      deviceInfo?.batteryCycles?.let { stringResource(R.string.device_battery_cycles, it) },
    )
    if (counters.isEmpty()) {
      Text(
        stringResource(R.string.device_no_counters),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(
        counters.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    val context = LocalContext.current
    val appVersion = remember {
      runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
      }.getOrNull()
    }
    appVersion?.let {
      Spacer(Modifier.height(4.dp))
      Text(stringResource(R.string.device_app_version, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    byId["reset"]?.let { reset ->
      Spacer(Modifier.height(4.dp))
      ResetButton(reset, enabled, onAction)
    }
  }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(10.dp))
      content()
    }
  }
}

@Composable
private fun SegmentedOptions(item: CommandUiState, enabled: Boolean, onOption: (String, Int) -> Unit) {
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    item.options.forEachIndexed { index, label ->
      SegmentedButton(
        selected = index == item.selectedIndex,
        onClick = { onOption(item.id, index) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = item.options.size),
        enabled = enabled,
        icon = {}, // the checkmark steals width the three tight labels need
        label = { Text(stringResource(label), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      )
    }
  }
}

/**
 * Same local-state pattern as the old CommandCard: user drags freely, the command is sent on
 * release, and device-reported values (channel-2 sync) land between drags via LaunchedEffect.
 */
@Composable
private fun LabeledSlider(
  item: CommandUiState,
  enabled: Boolean,
  name: String,
  continuous: Boolean = false,
  valueLabel: @Composable (Int) -> String = { "$it${item.unit}" },
  onSlider: (String, Int) -> Unit,
) {
  var value by remember(item.id) { mutableIntStateOf(item.intValue) }
  LaunchedEffect(item.intValue) { value = item.intValue }
  Column {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      Text(
        valueLabel(value),
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(
      value = value.toFloat(),
      onValueChange = { raw ->
        // Snap to the command's step; "continuous" just hides the tick dots for dense ranges.
        value = ((raw / item.step).roundToInt() * item.step).coerceIn(item.min, item.max)
      },
      onValueChangeFinished = { onSlider(item.id, value) },
      valueRange = item.min.toFloat()..item.max.toFloat(),
      steps = if (continuous) 0 else ((item.max - item.min) / item.step - 1).coerceAtLeast(0),
      enabled = enabled,
    )
  }
}

@Composable
private fun ResetButton(item: CommandUiState, enabled: Boolean, onAction: (String) -> Unit) {
  var confirming by remember { mutableStateOf(false) }
  TextButton(
    onClick = { if (item.confirm) confirming = true else onAction(item.id) },
    enabled = enabled,
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
  ) {
    Text(stringResource(item.labelRes))
  }
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      confirmButton = {
        TextButton(onClick = {
          confirming = false
          onAction(item.id)
        }) { Text(stringResource(R.string.dialog_confirm)) }
      },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.dialog_cancel)) } },
      title = { Text(stringResource(item.labelRes)) },
      text = { Text(stringResource(R.string.dialog_send_prompt, stringResource(item.labelRes))) },
    )
  }
}
