package com.example.bleat.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.bleat.ble.BleEvent
import com.example.bleat.ble.BleForegroundService
import com.example.bleat.ble.ConnState
import com.example.bleat.ble.DeviceInfo
import com.example.bleat.ble.Telemetry
import com.example.bleat.commands.CommandsViewModel
import com.example.bleat.ui.theme.SleepytrollTheme
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

  private var permissionsGranted by mutableStateOf(false)

  private val permissionsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) {
    // The connectedDevice foreground service can only be started once a Bluetooth runtime
    // permission is held, so it's deferred until the grant lands here (see onCreate).
    permissionsGranted = hasBluetoothPermissions()
    if (permissionsGranted) startAndBindService()
  }

  private val bluetoothAdapter: BluetoothAdapter? by lazy {
    getSystemService(BluetoothManager::class.java)?.adapter
  }
  private val foundDevices = mutableStateListOf<BluetoothDevice>()
  private var scanning by mutableStateOf(false)
  private var stopScanRunnable: Runnable? = null
  private var connectedDevice by mutableStateOf<BluetoothDevice?>(null)

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val d = result.device
      // scanRecord carries the advertised name even before the device is bonded/cached.
      val name = result.scanRecord?.deviceName ?: d.name
      if (name?.startsWith("Sleepytroll_") != true) return
      if (foundDevices.none { it.address == d.address }) foundDevices.add(d)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // The BLE service runs as a connectedDevice foreground service, which the platform only
    // permits once a Bluetooth runtime permission is granted. On a fresh install those aren't
    // granted yet, so request them first and start the service from the result callback;
    // when they're already granted (later launches) start it straight away.
    permissionsGranted = hasBluetoothPermissions()
    if (permissionsGranted) {
      startAndBindService()
    } else {
      requestPermissions()
    }

    // Debug-only showcase of the connected UI on BLE-less emulators:
    //   adb shell am start -n com.example.bleat/.ui.MainActivity --ez demo true
    val demo = intent.getBooleanExtra("demo", false) &&
      (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    setContent {
      val viewModel = remember { CommandsViewModel() }
      val uiState by viewModel.uiState.collectAsState()
      var telemetry by remember { mutableStateOf<Telemetry?>(null) }
      var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
      var motorWarning by remember { mutableStateOf<String?>(null) }
      var connState by remember { mutableStateOf(ConnState.DISCONNECTED) }
      var ack by remember { mutableStateOf<Pair<Int, String>?>(null) }
      var keepAlive by remember { mutableStateOf(false) }

      LaunchedEffect(service) {
        val svc = service ?: return@LaunchedEffect
        viewModel.sender = { cmd -> svc.sendCommand(cmd) }
        if (demo) return@LaunchedEffect // demo state below must not be overwritten by real flows
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
          if (ev is BleEvent.CommandAck) ack = (ack?.first ?: 0) + 1 to ev.text
        }
      }

      if (demo) {
        LaunchedEffect(Unit) {
          val t = Telemetry(
            batteryPct = 84,
            running = true,
            standby = false,
            speed = 45,
            soundSensitivity = 2,
            movementSensitivity = 3,
            timerSeconds = 32 * 60 + 5,
          )
          telemetry = t
          connState = ConnState.CONNECTED
          deviceInfo = DeviceInfo(
            serial = "ST-2044", version = "2.4", mode = 2,
            batteryCycles = 27, deviceTotalMin = 340, motorMinutes = 12,
          )
          viewModel.syncFromTelemetry(t)
          viewModel.syncMode(2)
        }
      }

      SleepytrollTheme {
        HomeScreen(
          uiState = uiState,
          connState = connState,
          telemetry = telemetry,
          deviceInfo = deviceInfo,
          motorWarning = motorWarning,
          ack = ack,
          keepAlive = keepAlive,
          devices = foundDevices,
          scanning = scanning,
          permissionsGranted = permissionsGranted,
          connectedDevice = connectedDevice,
          onToggle = viewModel::onToggle,
          onSlider = viewModel::onSlider,
          onOption = viewModel::onOption,
          onAction = viewModel::onAction,
          onKeepAlive = {
            keepAlive = it
            service?.setKeepAlive(it)
          },
          onStartScan = ::startScan,
          onStopScan = ::stopScan,
          onConnect = {
            connectedDevice = it
            service?.connect(it)
          },
          onDisconnect = {
            connectedDevice = null
            service?.userDisconnect()
          },
          onRequestPermissions = ::requestPermissions,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // The user may have granted/revoked permissions in system settings while we were paused.
    permissionsGranted = hasBluetoothPermissions()
  }

  override fun onDestroy() {
    stopScan()
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

  private fun hasBluetoothPermissions(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
      PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
      PackageManager.PERMISSION_GRANTED

  private fun startAndBindService() {
    if (bound) return
    val svcIntent = Intent(this, BleForegroundService::class.java)
    startForegroundService(svcIntent) // minSdk 36 ⇒ always O+; safe to start foreground directly
    bindService(svcIntent, connection, Context.BIND_AUTO_CREATE)
  }

  private fun startScan() {
    // startScan() needs BLUETOOTH_SCAN; if it isn't held (e.g. the user denied), request it
    // rather than let the scanner throw SecurityException. Granting also starts the service.
    if (!hasBluetoothPermissions()) {
      requestPermissions()
      return
    }
    stopScan() // cancel any in-flight scan and its pending stop so it can't kill this one early
    foundDevices.clear()
    val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
    scanner.startScan(scanCallback)
    scanning = true
    stopScanRunnable = Runnable { stopScan() }.also { window.decorView.postDelayed(it, 6000) }
  }

  private fun stopScan() {
    stopScanRunnable?.let { window.decorView.removeCallbacks(it) }
    stopScanRunnable = null
    if (scanning) {
      runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
      scanning = false
    }
  }
}
