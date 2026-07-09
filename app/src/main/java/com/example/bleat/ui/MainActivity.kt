package com.example.bleat.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bleat.ble.BleEvent
import com.example.bleat.ble.BleForegroundService
import com.example.bleat.ble.ConnState
import com.example.bleat.ble.DeviceInfo
import com.example.bleat.ble.Telemetry
import com.example.bleat.commands.CommandUiState
import com.example.bleat.commands.CommandsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private var service by mutableStateOf<BleForegroundService?>(null)
  private var bound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      service = (binder as BleForegroundService.LocalBinder).getService()
      bound = true
    }
    override fun onServiceDisconnected(name: ComponentName?) {
      bound = false
      service = null
    }
  }

  private val permissionsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { }

  private val bluetoothAdapter: BluetoothAdapter? by lazy {
    getSystemService(BluetoothManager::class.java)?.adapter
  }
  private val foundDevices = mutableStateListOf<BluetoothDevice>()

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val d = result.device
      if (foundDevices.none { it.address == d.address }) foundDevices.add(d)
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val svcIntent = Intent(this, BleForegroundService::class.java)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      startForegroundService(svcIntent)
    } else {
      startService(svcIntent)
    }
    bindService(svcIntent, connection, Context.BIND_AUTO_CREATE)

    setContent {
      val viewModel = remember { CommandsViewModel() }
      val uiState by viewModel.uiState.collectAsState()
      var telemetry by remember { mutableStateOf<Telemetry?>(null) }
      var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
      var motorWarning by remember { mutableStateOf<String?>(null) }
      var connState by remember { mutableStateOf(ConnState.DISCONNECTED) }
      var lastResponse by remember { mutableStateOf<String?>(null) }
      var keepAlive by remember { mutableStateOf(false) }

      LaunchedEffect(service) {
        val svc = service ?: return@LaunchedEffect
        viewModel.sender = { cmd -> svc.sendCommand(cmd) }
        launch {
          svc.telemetry.collect { t ->
            telemetry = t
            t?.let { viewModel.syncFromTelemetry(it) } // move controls to the device's real state
          }
        }
        launch {
          svc.deviceInfo.collect { di ->
            deviceInfo = di
            di?.mode?.let { viewModel.syncMode(it) }
          }
        }
        launch { svc.motorWarning.collect { motorWarning = it } }
        launch { svc.connectionState.collect { connState = it } }
        svc.events.collect { ev ->
          if (ev is BleEvent.CommandAck) lastResponse = ev.text
        }
      }

      MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Sleepytroll Controller") }) }) { padding ->
          Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = { requestPermissions() }) { Text("Permissions") }
              Button(onClick = { startScan() }) { Text("Scan") }
              Button(onClick = { service?.userDisconnect() }) { Text("Disconnect") }
            }

            Spacer(Modifier.height(6.dp))
            ConnectionIndicator(connState)

            Spacer(Modifier.height(8.dp))
            StatusPanel(telemetry, deviceInfo, motorWarning, lastResponse, keepAlive, onKeepAlive = {
              keepAlive = it
              service?.setKeepAlive(it)
            })

            Spacer(Modifier.height(8.dp))
            Text("Devices", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.height(110.dp)) {
              items(foundDevices) { device ->
                DeviceRow(device) { service?.connect(device) }
              }
            }

            Spacer(Modifier.height(8.dp))
            Text("Controls", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f)) {
              items(uiState) { item ->
                CommandCard(
                  item,
                  onToggle = viewModel::onToggle,
                  onSlider = viewModel::onSlider,
                  onOption = viewModel::onOption,
                  onAction = viewModel::onAction
                )
              }
            }
          }
        }
      }
    }
  }

  override fun onDestroy() {
    if (bound) { unbindService(connection); bound = false }
    super.onDestroy()
  }

  private fun requestPermissions() {
    permissionsLauncher.launch(
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
      )
    )
  }

  private fun startScan() {
    foundDevices.clear()
    val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
    scanner.startScan(scanCallback)
    window.decorView.postDelayed({ scanner.stopScan(scanCallback) }, 6000)
  }
}

@Composable
fun ConnectionIndicator(state: ConnState) {
  val (label, color) = when (state) {
    ConnState.CONNECTED -> "● Connected" to MaterialTheme.colorScheme.primary
    ConnState.CONNECTING -> "… Connecting" to MaterialTheme.colorScheme.tertiary
    ConnState.RECONNECTING -> "… Reconnecting" to MaterialTheme.colorScheme.tertiary
    ConnState.DISCONNECTED -> "○ Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun StatusPanel(
  t: Telemetry?,
  info: DeviceInfo?,
  motorWarning: String?,
  lastResponse: String?,
  keepAlive: Boolean,
  onKeepAlive: (Boolean) -> Unit
) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      motorWarning?.let {
        Text(
          "⚠ $it",
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
      }
      if (t?.lowBattery == true) {
        Text(
          "⚠ Battery low (${t.batteryPct}%)",
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
      }
      if (t == null) {
        Text("No status yet — connect to a Sleepytroll_… device", style = MaterialTheme.typography.bodyMedium)
      } else {
        val stateLabel = when {
          t.running -> "● Running"
          t.standby -> "○ Standby"
          else -> "○ Stopped"
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(stateLabel, fontWeight = FontWeight.Bold)
          Text("Battery ${t.batteryPct}%")
        }
        Spacer(Modifier.height(4.dp))
        Text("Speed ${t.speed}%   ·   timer ${t.timerText}")
        Text("Sound sens ${t.soundSensitivity}   ·   movement sens ${t.movementSensitivity}")
      }
      info?.let { di ->
        // Channel 1 (identity) + channel 3 (counters). Meanings are stable; exact values can
        // vary by firmware version (see BleForegroundService.parseCounters).
        Spacer(Modifier.height(4.dp))
        di.serial?.let { Text("Serial $it${di.version?.let { v -> "  ·  v$v" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
        val counters = buildList {
          di.motorMinutes?.let { add("motor ${it}m / 180m") }
          di.deviceTotalMin?.let { add("total ${it}m") }
          di.batteryCycles?.let { add("cycles $it") }
        }
        if (counters.isNotEmpty()) Text(counters.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
      }
      lastResponse?.let {
        Spacer(Modifier.height(4.dp))
        Text("Device reply: $it", style = MaterialTheme.typography.bodySmall)
      }
      Spacer(Modifier.height(4.dp))
      Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Switch(checked = keepAlive, onCheckedChange = onKeepAlive)
        Spacer(Modifier.width(8.dp))
        Text("3-hour keep-alive")
      }
    }
  }
}

@Composable
fun DeviceRow(device: BluetoothDevice, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(8.dp)) {
    Column {
      Text(device.name ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
      Text(device.address, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
fun CommandCard(
  item: CommandUiState,
  onToggle: (String, Boolean) -> Unit,
  onSlider: (String, Int) -> Unit,
  onOption: (String, Int) -> Unit,
  onAction: (String) -> Unit
) {
  Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Column(Modifier.padding(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(item.label, style = MaterialTheme.typography.titleMedium)
        item.lastSent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
      Spacer(Modifier.height(8.dp))
      when (item.type) {
        "toggle" -> {
          var checked by remember(item.id) { mutableStateOf(item.boolValue) }
          // Track device-reported state (channel-2 sync) without fighting user taps.
          LaunchedEffect(item.boolValue) { checked = item.boolValue }
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = { checked = it; onToggle(item.id, it) })
            Spacer(Modifier.width(8.dp))
            Text(if (checked) "On" else "Off")
          }
        }
        "slider" -> {
          var value by remember(item.id) { mutableStateOf(item.intValue) }
          // Track device-reported value (channel-2 sync) between user drags.
          LaunchedEffect(item.intValue) { value = item.intValue }
          val steps = ((item.max - item.min) / item.step - 1).coerceAtLeast(0)
          Column {
            Slider(
              value = value.toFloat(),
              onValueChange = { value = it.toInt() },
              onValueChangeFinished = { onSlider(item.id, value) },
              valueRange = item.min.toFloat()..item.max.toFloat(),
              steps = steps
            )
            Text("${value}${item.unit}")
          }
        }
        "options" -> {
          var expanded by remember(item.id) { mutableStateOf(false) }
          val selectedLabel = item.options.getOrNull(item.selectedIndex) ?: "Select"
          Box {
            OutlinedButton(onClick = { expanded = true }) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
              item.options.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                  expanded = false
                  onOption(item.id, index)
                })
              }
            }
          }
        }
        "action" -> {
          var confirming by remember(item.id) { mutableStateOf(false) }
          Button(onClick = { if (item.confirm) confirming = true else onAction(item.id) }) {
            Text(item.label)
          }
          if (confirming) {
            AlertDialog(
              onDismissRequest = { confirming = false },
              confirmButton = {
                TextButton(onClick = { confirming = false; onAction(item.id) }) { Text("Confirm") }
              },
              dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
              title = { Text(item.label) },
              text = { Text("Send ${item.label}?") }
            )
          }
        }
      }
    }
  }
}
