package com.example.bleat.ui

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
import androidx.compose.ui.unit.dp
import com.example.bleat.ble.ConnState
import com.example.bleat.ble.DeviceInfo
import com.example.bleat.ble.Telemetry
import com.example.bleat.commands.CommandUiState
import com.example.bleat.ui.components.ControlSections
import com.example.bleat.ui.components.HeroRockingControl
import com.example.bleat.ui.components.HeroState
import com.example.bleat.ui.components.ScanSheet
import com.example.bleat.ui.components.StatusHeader
import com.example.bleat.ui.components.heroState
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
  keepAlive: Boolean,
  devices: List<BluetoothDevice>,
  scanning: Boolean,
  permissionsGranted: Boolean,
  connectedDevice: BluetoothDevice?,
  onToggle: (String, Boolean) -> Unit,
  onSlider: (String, Int) -> Unit,
  onOption: (String, Int) -> Unit,
  onAction: (String) -> Unit,
  onKeepAlive: (Boolean) -> Unit,
  onStartScan: () -> Unit,
  onStopScan: () -> Unit,
  onConnect: (BluetoothDevice) -> Unit,
  onDisconnect: () -> Unit,
  onRequestPermissions: () -> Unit,
) {
  val byId = uiState.associateBy { it.id }
  var showSheet by rememberSaveable { mutableStateOf(false) }
  val connected = connState == ConnState.CONNECTED

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
      val hero = heroState(connState, telemetry, byId["bh"], byId["fr"])
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HeroRockingControl(
          state = hero,
          timerText = telemetry?.takeIf { it.running && it.timerSeconds > 0 }?.timerText,
          onTap = {
            when (hero) {
              HeroState.Disconnected ->
                if (permissionsGranted) showSheet = true else onRequestPermissions()
              is HeroState.Running -> onToggle("bh", false)
              is HeroState.Stopped, is HeroState.Standby -> onToggle("bh", true)
              else -> {}
            }
          },
        )
      }

      AckCaption(ack)

      byId["fr"]?.let { fr ->
        SpeedSlider(fr, enabled = connected, onSlider = onSlider)
        Spacer(Modifier.height(16.dp))
      }

      ControlSections(
        byId = byId,
        telemetry = telemetry,
        deviceInfo = deviceInfo,
        enabled = connected,
        keepAlive = keepAlive,
        onKeepAlive = onKeepAlive,
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
      connState = connState,
      connectedDevice = connectedDevice,
      permissionsGranted = permissionsGranted,
      onStartScan = onStartScan,
      onStopScan = onStopScan,
      onConnect = onConnect,
      onDisconnect = onDisconnect,
      onRequestPermissions = onRequestPermissions,
      onDismiss = { showSheet = false },
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
        "Device replied: ${ack?.second.orEmpty()}",
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
        "Speed",
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
