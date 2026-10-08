package com.trexx.shakeytroll.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.BleForegroundService
import com.trexx.shakeytroll.ble.ConnState
import com.trexx.shakeytroll.ble.DeviceInfo
import com.trexx.shakeytroll.ble.SensorActivity
import com.trexx.shakeytroll.ble.Telemetry
import com.trexx.shakeytroll.commands.CommandUiState
import com.trexx.shakeytroll.commands.SleepytrollCommands
import com.trexx.shakeytroll.ui.components.ControlSections
import com.trexx.shakeytroll.ui.components.HeroRockingControl
import com.trexx.shakeytroll.ui.components.HeroState
import com.trexx.shakeytroll.ui.components.ScanSheet
import com.trexx.shakeytroll.ui.components.SensorActivityCard
import com.trexx.shakeytroll.ui.components.StatusHeader
import com.trexx.shakeytroll.ui.components.heroState
import com.trexx.shakeytroll.ui.components.rememberElapsedRealtime
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
  uiState: List<CommandUiState>,
  connState: ConnState,
  telemetry: Telemetry?,
  deviceInfo: DeviceInfo?,
  motorWarning: String?,
  ack: Pair<Int, String>?,
  sensorActivity: SensorActivity,
  lastStatusAt: Long?,
  devices: List<BluetoothDevice>,
  scanning: Boolean,
  scanError: String?,
  permissionsGranted: Boolean,
  connectedDevice: BluetoothDevice?,
  rememberedDevice: Pair<String, String>?,
  onToggle: (String, Boolean) -> Unit,
  onSlider: (String, Int) -> Unit,
  onOption: (String, Int) -> Unit,
  onAction: (String) -> Unit,
  onStartScan: () -> Unit,
  onStopScan: () -> Unit,
  onConnect: (BluetoothDevice) -> Unit,
  onConnectRemembered: (String) -> Unit,
  onDisconnect: () -> Unit,
  onRequestPermissions: () -> Unit,
) {
  val byId = uiState.associateBy { it.id }
  var showSheet by rememberSaveable { mutableStateOf(false) }
  val connected = connState == ConnState.CONNECTED
  val now by rememberElapsedRealtime()
  // The Mode control already merges what the device reports with what the user just picked.
  val modeControl = byId[SleepytrollCommands.MODE]
  val sensorMode = modeControl?.selectedIndex in 1..2 // sensor, baby monitor
  val statusAgeMs = lastStatusAt?.takeIf { connected && telemetry != null }?.let { now - it }
  val stale = statusAgeMs != null && statusAgeMs > BleForegroundService.STATUS_STALE_MS

  Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
    Column(
      Modifier
        .padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp),
    ) {
      Spacer(Modifier.height(8.dp))
      StatusHeader(
        connState = connState,
        telemetry = telemetry,
        deviceInfo = deviceInfo,
        motorWarning = motorWarning,
        onConnectionClick = { showSheet = true },
      )

      Spacer(Modifier.height(20.dp))
      val hero = heroState(connState, telemetry, byId["bh"], byId["fr"], sensorMode, sensorActivity.currentBout)
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HeroRockingControl(
          state = hero,
          modeLabel = modeControl?.takeIf { telemetry != null && connected }
            ?.let { stringResource(it.options[it.selectedIndex]) },
          timerText = telemetry?.takeIf { it.running && it.timerSeconds > 0 }?.timerText,
          nowMs = now,
          stale = stale,
          onTap = {
            when (hero) {
              HeroState.Disconnected ->
                if (permissionsGranted) showSheet = true else onRequestPermissions()
              is HeroState.Running -> onToggle("bh", false)
              is HeroState.Stopped, is HeroState.Listening -> onToggle("bh", true)
              else -> {}
            }
          },
        )
      }

      if (stale) StaleCaption((statusAgeMs / 1000).toInt()) else AckCaption(ack)

      if (connected && sensorMode) {
        SensorActivityCard(sensorActivity, now)
        Spacer(Modifier.height(16.dp))
      }

      byId["fr"]?.let { fr ->
        SpeedSlider(fr, enabled = connected, onSlider = onSlider)
        Spacer(Modifier.height(16.dp))
      }

      ControlSections(
        byId = byId,
        telemetry = telemetry,
        deviceInfo = deviceInfo,
        enabled = connected,
        onSlider = onSlider,
        onOption = onOption,
        onAction = onAction,
      )
      Spacer(Modifier.height(24.dp))
    }
  }

  if (showSheet) {
    ScanSheet(
      devices = devices,
      scanning = scanning,
      scanError = scanError,
      connState = connState,
      connectedDevice = connectedDevice,
      rememberedDevice = rememberedDevice,
      permissionsGranted = permissionsGranted,
      onStartScan = onStartScan,
      onStopScan = onStopScan,
      onConnect = onConnect,
      onConnectRemembered = onConnectRemembered,
      onDisconnect = onDisconnect,
      onRequestPermissions = onRequestPermissions,
      onDismiss = { showSheet = false },
    )
  }
}

/** Takes the ack caption's slot while the device has gone quiet, so the hero isn't trusted blindly. */
@Composable
private fun StaleCaption(seconds: Int) {
  Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
    Text(
      pluralStringResource(R.plurals.status_stale_seconds, seconds, seconds),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

/** Quiet, self-fading confirmation of the device's channel-4 reply — no snackbar sliding over content. */
@Composable
private fun AckCaption(ack: Pair<Int, String>?) {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(ack) {
    if (ack != null) {
      visible = true
      delay(2000)
      visible = false
    }
  }
  Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(tween(600))) {
      Text(
        stringResource(R.string.ack_caption, ack?.second.orEmpty()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SpeedSlider(item: CommandUiState, enabled: Boolean, onSlider: (String, Int) -> Unit) {
  var value by remember(item.id) { mutableIntStateOf(item.intValue) }
  LaunchedEffect(item.intValue) { value = item.intValue }
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(R.string.speed_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.width(12.dp))
      Slider(
        value = value.toFloat(),
        onValueChange = { value = it.roundToInt().coerceIn(item.min, item.max) },
        onValueChangeFinished = { onSlider(item.id, value) },
        valueRange = item.min.toFloat()..item.max.toFloat(),
        enabled = enabled,
        modifier = Modifier.weight(1f),
      )
    }
  }
}
