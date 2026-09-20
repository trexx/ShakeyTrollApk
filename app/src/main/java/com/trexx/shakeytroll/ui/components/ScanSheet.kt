package com.trexx.shakeytroll.ui.components

import android.annotation.SuppressLint
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.ConnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(
  devices: List<BluetoothDevice>,
  scanning: Boolean,
  scanError: String?,
  connState: ConnState,
  connectedDevice: BluetoothDevice?,
  rememberedDevice: Pair<String, String>?,
  permissionsGranted: Boolean,
  onStartScan: () -> Unit,
  onStopScan: () -> Unit,
  onConnect: (BluetoothDevice) -> Unit,
  onConnectRemembered: (String) -> Unit,
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
      Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(16.dp))

      if (!permissionsGranted) {
        Text(
          stringResource(R.string.scan_permission_needed),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) { Text(stringResource(R.string.scan_allow_bluetooth)) }
        return@Column
      }

      if (connState == ConnState.CONNECTED) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
              deviceName(connectedDevice) ?: stringResource(R.string.scan_connected_device),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              stringResource(R.string.scan_connected),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
          TextButton(onClick = onDisconnect) { Text(stringResource(R.string.scan_disconnect)) }
        }
        Spacer(Modifier.height(12.dp))
      }

      // Last device first, so a reconnect is one tap and doesn't need the scan to find it.
      rememberedDevice
        ?.takeIf { (address, _) -> connState != ConnState.CONNECTED && devices.none { it.address == address } }
        ?.let { (address, name) ->
          DeviceRow(
            title = name,
            subtitle = stringResource(R.string.scan_last_connected, address),
            onClick = { onConnectRemembered(address); onDismiss() },
          )
        }

      if (scanning) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
          stringResource(R.string.scan_looking),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
      }

      devices.forEach { device ->
        DeviceRow(
          title = deviceName(device) ?: stringResource(R.string.scan_default_name),
          subtitle = device.address,
          onClick = { onConnect(device); onDismiss() },
        )
      }

      if (scanError != null) {
        Text(scanError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
      } else if (!scanning && devices.isEmpty() && connState != ConnState.CONNECTED) {
        Text(
          stringResource(R.string.scan_none_found),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
      }

      if (!scanning) {
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onStartScan) {
          Text(stringResource(if (devices.isEmpty()) R.string.scan_scan else R.string.scan_again))
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

@Composable
private fun DeviceRow(title: String, subtitle: String, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.scan_connect), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
  }
}

/** BLUETOOTH_CONNECT is granted before rows render, but guard anyway — name can still throw/null. */
@SuppressLint("MissingPermission")
private fun deviceName(device: BluetoothDevice?): String? =
  device?.let { runCatching { it.name }.getOrNull() }
