package com.example.bleat.ui.components

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bleat.ble.ConnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
  devices: List<BluetoothDevice>,
  scanning: Boolean,
  connState: ConnState,
  connectedDevice: BluetoothDevice?,
  permissionsGranted: Boolean,
  onStartScan: () -> Unit,
  onStopScan: () -> Unit,
  onConnect: (BluetoothDevice) -> Unit,
  onDisconnect: () -> Unit,
  onRequestPermissions: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp)
        .navigationBarsPadding()
        .padding(bottom = 24.dp),
    ) {
      Text("Find your Sleepytroll", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(16.dp))

      if (!permissionsGranted) {
        Text(
          "Bluetooth permission is needed to find and control the Sleepytroll.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) { Text("Allow Bluetooth") }
        return@Column
      }

      if (connState == ConnState.CONNECTED) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
              deviceName(connectedDevice) ?: "Connected device",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              "Connected",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
          TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        Spacer(Modifier.height(12.dp))
      }

      if (scanning) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
          "Looking nearby…",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
      }

      devices.forEach { device ->
        Row(
          Modifier
            .fillMaxWidth()
            .clickable {
              onConnect(device)
              onDismiss()
            }
            .padding(vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(deviceName(device) ?: "Sleepytroll", style = MaterialTheme.typography.titleMedium)
            Text(
              device.address,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            "Connect",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      if (!scanning && devices.isEmpty() && connState != ConnState.CONNECTED) {
        Text(
          "No Sleepytroll found. Make sure it's switched on and nearby.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
      }

      if (!scanning) {
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onStartScan) {
          Text(if (devices.isEmpty()) "Scan" else "Scan again")
        }
      }
    }
  }

  // Auto-scan when the sheet opens (unless already connected); always stop when it closes.
  LaunchedEffect(Unit) {
    if (permissionsGranted && connState != ConnState.CONNECTED) onStartScan()
  }
  DisposableEffect(Unit) {
    onDispose { onStopScan() }
  }
}

/** BLUETOOTH_CONNECT is granted before rows render, but guard anyway — name can still throw/null. */
private fun deviceName(device: BluetoothDevice?): String? =
  device?.let { runCatching { it.name }.getOrNull() }
