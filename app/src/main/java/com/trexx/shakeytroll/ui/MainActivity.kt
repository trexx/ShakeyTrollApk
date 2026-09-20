package com.trexx.shakeytroll.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
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
import androidx.core.content.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.BleEvent
import com.trexx.shakeytroll.ble.BleForegroundService
import com.trexx.shakeytroll.ble.ConnState
import com.trexx.shakeytroll.ble.DeviceInfo
import com.trexx.shakeytroll.ble.Telemetry
import com.trexx.shakeytroll.commands.CommandsViewModel
import com.trexx.shakeytroll.ui.theme.SleepytrollTheme
import kotlinx.coroutines.launch

// Every BLE call below is reached only after hasBluetoothPermissions() is true.
@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "MainActivity"
    private const val SCAN_MS = 6_000L
    // If the service-UUID filter has found nothing by then, widen to an unfiltered, name-matched
    // scan for the rest of the window, in case this device's advertisement omits the UUID.
    private const val FILTERED_SCAN_MS = 2_500L
    private const val PREFS = "sleepytroll"
    private const val PREF_LAST_ADDRESS = "last_address"
    private const val PREF_LAST_NAME = "last_name"
  }

  private var service by mutableStateOf<BleForegroundService?>(null)
  private var bound = false

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      service = (binder as BleForegroundService.LocalBinder).getService()
    }
    override fun onServiceDisconnected(name: ComponentName?) {
      service = null
    }
  }

  private var permissionsGranted by mutableStateOf(false)

  private val permissionsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) {
    permissionsGranted = hasBluetoothPermissions()
  }

  // What to do once the user turns Bluetooth on from the system dialog (scan, or connect).
  private var afterBluetoothEnabled: (() -> Unit)? = null
  private val enableBluetoothLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    if (it.resultCode == RESULT_OK) afterBluetoothEnabled?.invoke()
    afterBluetoothEnabled = null
  }

  private val bluetoothAdapter: BluetoothAdapter? by lazy {
    getSystemService(BluetoothManager::class.java)?.adapter
  }
  private val mainHandler = Handler(Looper.getMainLooper())
  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  // The ISSC/Microchip transparent-UART service the rocker exposes (PROTOCOL §2) and the 0x5553…
  // variant the official app's constants name; a filter list is OR-ed. The name check in the
  // callback stays the identity test either way: the filter only cuts down what the callback sees.
  private val serviceFilters: List<ScanFilter> = listOf(
    "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
    "55535343-FE7D-4AE5-8FA9-9FAFD205E455",
  ).map { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build() }

  // A short, user-initiated foreground scan: favour discovery speed over battery.
  private val scanSettings: ScanSettings =
    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

  private val foundDevices = mutableStateListOf<BluetoothDevice>()
  private var scanning by mutableStateOf(false)
  private var scanFiltered = false
  private var widenScanRunnable: Runnable? = null
  private var scanError by mutableStateOf<String?>(null)
  private var stopScanRunnable: Runnable? = null
  private var connectedDevice by mutableStateOf<BluetoothDevice?>(null)
  /** Address → name of the last device we connected to, for one-tap reconnect without a scan. */
  private var rememberedDevice by mutableStateOf<Pair<String, String>?>(null)

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val d = result.device
      val record = result.scanRecord
      // scanRecord carries the advertised name even before the device is bonded/cached.
      val name = record?.deviceName ?: d.name
      if (name?.startsWith("Sleepytroll_") != true) return
      if (foundDevices.none { it.address == d.address }) {
        // Logged so a capture can confirm which service UUIDs the rocker actually advertises.
        Log.d(
          TAG,
          "Found $name ${d.address} via ${if (scanFiltered) "service-UUID filter" else "unfiltered scan"}; " +
            "advertised services=${record?.serviceUuids}"
        )
        foundDevices.add(d)
      }
    }

    override fun onScanFailed(errorCode: Int) {
      stopScan()
      scanError = when (errorCode) {
        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
          getString(R.string.scan_error_registration)
        SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> getString(R.string.scan_error_too_frequent)
        else -> getString(R.string.scan_error_generic, errorCode)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Bind only. The service promotes itself to a started foreground service while a device is
    // connected (so the link survives this Activity going away) and steps back down afterwards,
    // so nothing here depends on permissions being granted yet.
    bound = bindService(Intent(this, BleForegroundService::class.java), connection, Context.BIND_AUTO_CREATE)

    permissionsGranted = hasBluetoothPermissions()
    if (!permissionsGranted) requestPermissions()

    rememberedDevice = prefs.getString(PREF_LAST_ADDRESS, null)?.let { address ->
      address to (prefs.getString(PREF_LAST_NAME, null) ?: getString(R.string.scan_default_name))
    }

    // Debug-only showcase of the connected UI on BLE-less emulators:
    //   adb shell am start -n com.trexx.shakeytroll/.ui.MainActivity --ez demo true
    val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val demo = debuggable && intent.getBooleanExtra("demo", false)
    // Debug-only: shrink the keep-alive thresholds so a re-arm can be watched in minutes:
    //   adb shell am start -n com.trexx.shakeytroll/.ui.MainActivity --ei rearm_after_min 2
    val rearmTestMin = intent.getIntExtra("rearm_after_min", 0).takeIf { debuggable && it > 0 }

    setContent {
      val viewModel: CommandsViewModel = viewModel()
      val uiState by viewModel.uiState.collectAsState()
      var telemetry by remember { mutableStateOf<Telemetry?>(null) }
      var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
      var motorWarning by remember { mutableStateOf<String?>(null) }
      var connState by remember { mutableStateOf(ConnState.DISCONNECTED) }
      var ack by remember { mutableStateOf<Pair<Int, String>?>(null) }
      var keepAlive by remember { mutableStateOf(false) }
      var keepAliveStatus by remember { mutableStateOf<String?>(null) }

      LaunchedEffect(service) {
        val svc = service ?: return@LaunchedEffect
        viewModel.sender = { cmd -> svc.sendCommand(cmd) }
        rearmTestMin?.let { svc.setRearmThresholdsForTesting(motorMin = it, elapsedMin = it) }
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
            viewModel.syncRunTimerBudget(di?.motorMinutes)
          }
        }
        launch { svc.motorWarning.collect { motorWarning = it } }
        launch {
          svc.connectionState.collect { state ->
            connState = state
            if (state == ConnState.CONNECTED) connectedDevice?.let(::rememberDevice)
          }
        }
        launch { svc.keepAliveEnabled.collect { keepAlive = it } }
        launch { svc.keepAliveStatus.collect { keepAliveStatus = it } }
        svc.events.collect { ev ->
          when (ev) {
            is BleEvent.CommandAck -> ack = (ack?.first ?: 0) + 1 to ev.text
            // The keep-alive's own stop/start must not flip the hero to "Tap to start" for a beat.
            BleEvent.Rearm -> viewModel.suppress("bh", 5_000)
            else -> {}
          }
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
          viewModel.syncRunTimerBudget(12)
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
          keepAliveStatus = keepAliveStatus,
          devices = foundDevices,
          scanning = scanning,
          scanError = scanError,
          permissionsGranted = permissionsGranted,
          connectedDevice = connectedDevice,
          rememberedDevice = rememberedDevice,
          onToggle = viewModel::onToggle,
          onSlider = viewModel::onSlider,
          onOption = viewModel::onOption,
          onAction = viewModel::onAction,
          onKeepAlive = { if (demo) keepAlive = it else service?.setKeepAlive(it) },
          onStartScan = ::startScan,
          onStopScan = ::stopScan,
          onConnect = ::connectTo,
          onConnectRemembered = { address -> bluetoothAdapter?.getRemoteDevice(address)?.let(::connectTo) },
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
        // Optional: without it the connection notification only shows in the FGS task manager.
        Manifest.permission.POST_NOTIFICATIONS,
      )
    )
  }

  private fun hasBluetoothPermissions(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
      PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
      PackageManager.PERMISSION_GRANTED

  /** True if Bluetooth is on. Otherwise asks the user to enable it and runs [then] once they do. */
  private fun ensureBluetoothOn(then: () -> Unit): Boolean {
    val adapter = bluetoothAdapter ?: run { scanError = getString(R.string.scan_error_no_bluetooth); return false }
    if (adapter.isEnabled) return true
    afterBluetoothEnabled = then
    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    return false
  }

  private fun connectTo(device: BluetoothDevice) {
    if (!ensureBluetoothOn { connectTo(device) }) return
    connectedDevice = device
    service?.connect(device)
  }

  private fun rememberDevice(device: BluetoothDevice) {
    val name = runCatching { device.name }.getOrNull()
      ?: rememberedDevice?.takeIf { it.first == device.address }?.second
      ?: getString(R.string.scan_default_name)
    prefs.edit {
      putString(PREF_LAST_ADDRESS, device.address)
      putString(PREF_LAST_NAME, name)
    }
    rememberedDevice = device.address to name
  }

  private fun startScan() {
    // startScan() needs BLUETOOTH_SCAN; if it isn't held (e.g. the user denied), request it
    // rather than let the scanner throw SecurityException.
    if (!hasBluetoothPermissions()) {
      requestPermissions()
      return
    }
    if (!ensureBluetoothOn(::startScan)) return
    stopScan() // cancel any in-flight scan and its pending stop so it can't kill this one early
    foundDevices.clear()
    scanError = null
    val scanner = bluetoothAdapter?.bluetoothLeScanner
      ?: run { scanError = getString(R.string.scan_error_no_scanner); return }
    scanner.startScan(serviceFilters, scanSettings, scanCallback)
    scanFiltered = true
    scanning = true
    widenScanRunnable = Runnable { widenScan() }.also { mainHandler.postDelayed(it, FILTERED_SCAN_MS) }
    stopScanRunnable = Runnable { stopScan() }.also { mainHandler.postDelayed(it, SCAN_MS) }
  }

  /** The UUID filter found nothing: restart unfiltered (name-matched) for the rest of the window. */
  private fun widenScan() {
    widenScanRunnable = null
    if (!scanning || foundDevices.isNotEmpty()) return
    val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
    runCatching { scanner.stopScan(scanCallback) }
    scanFiltered = false
    Log.d(TAG, "Nothing matched the service-UUID filter; widening to an unfiltered scan")
    scanner.startScan(null, scanSettings, scanCallback)
  }

  private fun stopScan() {
    stopScanRunnable?.let { mainHandler.removeCallbacks(it) }
    stopScanRunnable = null
    widenScanRunnable?.let { mainHandler.removeCallbacks(it) }
    widenScanRunnable = null
    if (scanning) {
      runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
      scanning = false
    }
  }
}
