package com.trexx.shakeytroll.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ui.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * BLE client for the Sleepytroll baby rocker.
 *
 * The device is a Microchip/ISSC "transparent UART" module bridged to an application MCU.
 * The phone writes ASCII AT commands (terminated with ';') to the write characteristic with
 * *write-without-response*, and the device streams back channel-tagged telemetry lines
 * ("N,payload\r\n") on the notify characteristic. Decoding lives in [SleepytrollProtocol];
 * this class owns the connection, the write queue and the notification. See SLEEPYTROLL_PROTOCOL.md.
 *
 * Threading: every GATT callback is re-posted to the main looper, so all mutable state below is
 * touched on the main thread only. The public API is called from the Activity (main thread).
 */
// MainActivity only reaches connect()/scan once BLUETOOTH_CONNECT and BLUETOOTH_SCAN are granted.
@SuppressLint("MissingPermission")
class BleForegroundService : Service() {

  companion object {
    private const val TAG = "BleService"
    /** Notification action: disconnect and let the service stop. */
    const val ACTION_DISCONNECT = "com.trexx.shakeytroll.action.DISCONNECT"
    val WRITE_UUID: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
    val NOTIFY_UUID: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val CHANNEL_ID = "ble_foreground_channel"
    private const val NOTIF_ID = 1

    // The official app negotiates MTU 128 and truncates writes above it.
    private const val REQUEST_MTU = 128

    // Auto-reconnect, matching the official app (3 tries, 3 s apart).
    private const val MAX_RETRIES = 3
    private const val RETRY_INTERVAL_MS = 3_000L

    // The stack allows one outstanding write per connection (even without response); if its
    // callback never arrives, release the queue after this long rather than wedge forever.
    private const val WRITE_TIMEOUT_MS = 2_000L

    /** Channel 2 arrives ~1/s; this long without a frame means the shown state may be out of date. */
    const val STATUS_STALE_MS = 5_000L
  }

  private enum class Origin { USER, HANDSHAKE }

  private val binder = LocalBinder()
  private val handler = Handler(Looper.getMainLooper())
  private var bluetoothGatt: BluetoothGatt? = null
  private var writeChar: BluetoothGattCharacteristic? = null
  private var notifyChar: BluetoothGattCharacteristic? = null
  private val lines = LineAssembler()

  private val writeQueue = ArrayDeque<String>()
  private var writeInFlight = false

  private val _events = MutableSharedFlow<BleEvent>(replay = 0, extraBufferCapacity = 32)
  val events = _events.asSharedFlow()

  private val _telemetry = MutableStateFlow<Telemetry?>(null)
  val telemetry = _telemetry.asStateFlow()

  private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
  val deviceInfo = _deviceInfo.asStateFlow()

  // Latched notice when the device reports the 3-hour cap on channel 4; cleared when it resumes.
  private val _motorWarning = MutableStateFlow<String?>(null)
  val motorWarning = _motorWarning.asStateFlow()

  private val _connectionState = MutableStateFlow(ConnState.DISCONNECTED)
  val connectionState = _connectionState.asStateFlow()

  /** elapsedRealtime of the last channel-2 frame, so the UI can say when the status is old. */
  private val _lastStatusAt = MutableStateFlow<Long?>(null)
  val lastStatusAt = _lastStatusAt.asStateFlow()
  private var statusStale = false

  // ---- sensor-mode activity --------------------------------------------------
  private val sensorTracker = SensorActivityTracker()
  private val _sensorActivity = MutableStateFlow(SensorActivity())
  val sensorActivity = _sensorActivity.asStateFlow()

  // Last mode the device reported (channel 3, 1..3) or the user selected, whichever came last.
  // Channel 3's first byte may carry other stage values on some firmware; those don't count.
  private var knownMode: Int? = null

  private var lastDevice: BluetoothDevice? = null
  private var retries = 0
  private var manualDisconnect = false

  inner class LocalBinder : Binder() {
    fun getService(): BleForegroundService = this@BleForegroundService
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  /**
   * The Activity only binds. [connect] starts the service and puts it in the foreground so the
   * link outlives the Activity; [userDisconnect] (or giving up on reconnecting) steps it back
   * down, after which it lives only as long as something is bound to it.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_DISCONNECT) userDisconnect()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    manualDisconnect = true
    handler.removeCallbacksAndMessages(null)
    disconnectGatt()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
    )
  }

  private fun buildNotification(content: String): Notification {
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
    val disconnect = PendingIntent.getService(
      this, 1, Intent(this, BleForegroundService::class.java).setAction(ACTION_DISCONNECT),
      PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.notif_title))
      .setContentText(content)
      .setSmallIcon(R.drawable.ic_stat_moon)
      .setContentIntent(open)
      .addAction(0, getString(R.string.notif_action_disconnect), disconnect)
      .setOngoing(true)
      .setSilent(true)
      .build()
  }

  private var notifiedText: String? = null

  /** Reflect connection + rocking state on the notification; only re-posts when the text changes. */
  private fun updateNotification() {
    val name = lastDevice?.let { d -> runCatching { d.name }.getOrNull() ?: d.address } ?: getString(R.string.notif_default_name)
    val text = when (_connectionState.value) {
      ConnState.DISCONNECTED -> return // not in the foreground; nothing to show
      ConnState.CONNECTING -> getString(R.string.notif_connecting, name)
      ConnState.RECONNECTING -> getString(R.string.notif_reconnecting, name)
      ConnState.CONNECTED -> {
        val t = _telemetry.value
        val sensor = SleepytrollProtocol.isSensorMode(knownMode)
        val activity = _sensorActivity.value
        // Absolute clock times, not "N min ago": the text stays true without re-posting.
        val bout = activity.currentBout
        val lastTrigger = activity.lastTriggerMs
        when {
          t == null -> getString(R.string.notif_connected, name)
          statusStale -> getString(R.string.notif_connected_stale, name)
          t.running && bout != null -> getString(R.string.notif_connected_triggered, name, t.speed, clock(bout.startMs))
          t.running -> getString(R.string.notif_connected_rocking, name, t.speed)
          // Run state 3 only means "listening" when a sensor mode is armed; otherwise it's stopped.
          t.standby && sensor && lastTrigger != null ->
            getString(R.string.notif_connected_listening_last, name, clock(lastTrigger))
          t.standby && sensor -> getString(R.string.notif_connected_listening, name)
          else -> getString(R.string.notif_connected_stopped, name)
        }
      }
    }
    if (text == notifiedText) return
    notifiedText = text
    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
  }

  private fun setState(state: ConnState) {
    _connectionState.value = state
    updateNotification()
  }

  private fun leaveForeground() {
    notifiedText = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf() // no-op while still bound; destroyed once the Activity unbinds
  }

  private fun logEvent(message: String) {
    Log.d(TAG, message)
    _events.tryEmit(BleEvent.Log(message))
  }

  // ---- public API ---------------------------------------------------------

  fun connect(device: BluetoothDevice) {
    handler.removeCallbacks(reconnectRunnable)
    disconnectGatt()
    if (device.address != lastDevice?.address) {
      // Another rocker: its triggers and mode have nothing to do with the last one's.
      sensorTracker.clear()
      _sensorActivity.value = sensorTracker.activity
      knownMode = null
    }
    manualDisconnect = false
    retries = 0
    lastDevice = device
    // Become a started foreground service so the link survives the Activity unbinding.
    ContextCompat.startForegroundService(this, Intent(this, BleForegroundService::class.java))
    ServiceCompat.startForeground(
      this, NOTIF_ID, buildNotification(getString(R.string.notif_connecting_initial)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    )
    setState(ConnState.CONNECTING)
    logEvent("Connecting ${device.address}")
    bluetoothGatt = openGatt(device)
  }

  /** Queue an ASCII AT command (e.g. "AT+BH=01;"). Writes go out one at a time, in order. */
  fun sendCommand(cmd: String) = enqueue(cmd, Origin.USER)

  /** User-initiated disconnect: stop auto-reconnect and tear down. */
  fun userDisconnect() {
    manualDisconnect = true
    handler.removeCallbacks(reconnectRunnable)
    setState(ConnState.DISCONNECTED)
    disconnectGatt()
    leaveForeground()
  }

  // ---- write queue --------------------------------------------------------

  private fun enqueue(cmd: String, origin: Origin) {
    if (bluetoothGatt == null || writeChar == null) {
      logEvent("Not connected, dropped $cmd")
      return
    }
    if (origin == Origin.USER) SleepytrollProtocol.modeOf(cmd)?.let { knownMode = it }
    // A start we send is not the sensor tripping.
    if (cmd == SleepytrollProtocol.START) sensorTracker.noteManualStart(SystemClock.elapsedRealtime())
    writeQueue.addLast(cmd)
    drainWrites()
  }

  private fun drainWrites() {
    if (writeInFlight) return
    val g = bluetoothGatt ?: return
    val ch = writeChar ?: return
    while (true) {
      val cmd = writeQueue.removeFirstOrNull() ?: return
      val status = g.writeCharacteristic(
        ch, cmd.toByteArray(Charsets.US_ASCII), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      )
      if (status == BluetoothStatusCodes.SUCCESS) {
        writeInFlight = true
        handler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
        logEvent("→ $cmd (status=$status)")
        return
      }
      logEvent("→ $cmd rejected (status=$status)") // 201 = ERROR_GATT_WRITE_REQUEST_BUSY
    }
  }

  private fun onWriteDone(status: Int) {
    handler.removeCallbacks(writeTimeoutRunnable)
    writeInFlight = false
    if (status != BluetoothGatt.GATT_SUCCESS) logEvent("Write failed: $status")
    drainWrites()
  }

  private val writeTimeoutRunnable = Runnable {
    if (!writeInFlight) return@Runnable
    Log.w(TAG, "Write callback never arrived; releasing the queue")
    writeInFlight = false
    drainWrites()
  }

  private fun disconnectGatt() {
    bluetoothGatt?.let { it.disconnect(); it.close() }
    bluetoothGatt = null
    writeChar = null
    notifyChar = null
    writeQueue.clear()
    writeInFlight = false
    handler.removeCallbacks(writeTimeoutRunnable)
    lines.reset()
    _telemetry.value = null
    _deviceInfo.value = null
    _motorWarning.value = null
    handler.removeCallbacks(staleRunnable)
    statusStale = false
    _lastStatusAt.value = null
    sensorTracker.onLinkLost()
    _sensorActivity.value = sensorTracker.activity
  }

  private val staleRunnable = Runnable {
    statusStale = true
    updateNotification()
  }

  private fun clock(elapsedRealtime: Long = SystemClock.elapsedRealtime()): String {
    val wallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedRealtime)
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wallMs))
  }

  // ---- GATT callback (all bodies re-posted to the main thread) --------------

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      handler.post {
        if (g !== bluetoothGatt) return@post // a GATT we already closed
        when {
          newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
            logEvent("Connected, discovering services")
            setState(ConnState.CONNECTING) // link up; not ready until notifications on
            g.discoverServices()
          }
          newState == BluetoothProfile.STATE_CONNECTED -> {
            logEvent("Connected with error status=$status, dropping")
            disconnectGatt()
            maybeReconnect()
          }
          newState == BluetoothProfile.STATE_DISCONNECTED -> {
            logEvent("Disconnected (status=$status)")
            disconnectGatt()
            maybeReconnect()
          }
        }
      }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      handler.post {
        if (g !== bluetoothGatt) return@post
        if (status != BluetoothGatt.GATT_SUCCESS) {
          logEvent("Service discovery failed: $status"); return@post
        }
        val svc = g.services.firstOrNull { s ->
          s.characteristics.any { it.uuid == WRITE_UUID || it.uuid == NOTIFY_UUID }
        } ?: run { logEvent("Sleepytroll service not found"); return@post }
        writeChar = svc.getCharacteristic(WRITE_UUID)
        notifyChar = svc.getCharacteristic(NOTIFY_UUID)
        // Negotiate MTU first; notifications are enabled once it settles (matches the app).
        g.requestMtu(REQUEST_MTU)
      }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
      handler.post {
        if (g !== bluetoothGatt) return@post
        logEvent("MTU=$mtu")
        val nc = notifyChar ?: run { logEvent("Notify characteristic missing"); return@post }
        g.setCharacteristicNotification(nc, true)
        val desc = nc.getDescriptor(CCCD) ?: run { logEvent("CCCD missing"); return@post }
        g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
      }
    }

    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      handler.post {
        if (g !== bluetoothGatt || descriptor.uuid != CCCD) return@post
        if (status != BluetoothGatt.GATT_SUCCESS) {
          logEvent("Enabling notifications failed: $status")
          disconnectGatt()
          maybeReconnect()
          return@post
        }
        logEvent("Notifications enabled — sending handshake")
        retries = 0 // a fully working link resets the reconnect budget
        setState(ConnState.CONNECTED) // ready to talk
        enqueue(SleepytrollProtocol.HANDSHAKE, Origin.HANDSHAKE)
      }
    }

    override fun onCharacteristicChanged(
      g: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray
    ) {
      if (characteristic.uuid != NOTIFY_UUID) return
      val bytes = value.copyOf()
      handler.post {
        if (g !== bluetoothGatt) return@post
        lines.feed(bytes).forEach(::onLine)
      }
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      handler.post {
        if (g === bluetoothGatt) onWriteDone(status)
      }
    }
  }

  private val reconnectRunnable = Runnable {
    if (manualDisconnect) return@Runnable
    val device = lastDevice ?: return@Runnable
    bluetoothGatt = openGatt(device)
  }

  // API 37 deprecates every Context-based connectGatt overload in favour of
  // connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback), which does not
  // exist on API 36 (minSdk). The old overload still works on 37, so keep one call path until
  // minSdk reaches 37 rather than branching the BLE connect flow per API level.
  @Suppress("DEPRECATION")
  private fun openGatt(device: BluetoothDevice): BluetoothGatt? =
    device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

  private fun maybeReconnect() {
    if (manualDisconnect) { setState(ConnState.DISCONNECTED); return }
    if (lastDevice == null) { setState(ConnState.DISCONNECTED); leaveForeground(); return }
    if (retries >= MAX_RETRIES) {
      logEvent("Giving up reconnect")
      setState(ConnState.DISCONNECTED)
      leaveForeground()
      return
    }
    retries++
    setState(ConnState.RECONNECTING)
    logEvent("Reconnect attempt $retries/$MAX_RETRIES")
    handler.removeCallbacks(reconnectRunnable)
    handler.postDelayed(reconnectRunnable, RETRY_INTERVAL_MS)
  }

  // ---- inbound lines ------------------------------------------------------

  /** A line looks like "N,payload": 1 = identity, 2 = live status, 3 = counters, 4 = ack/status. */
  private fun onLine(line: String) {
    val (channel, body) = SleepytrollProtocol.splitChannel(line)
      ?: run { _events.tryEmit(BleEvent.Info(line)); return }
    when (channel) {
      '1' -> {
        _deviceInfo.value = SleepytrollProtocol.parseIdentity(body, _deviceInfo.value ?: DeviceInfo())
        _events.tryEmit(BleEvent.Info("id: $body"))
      }
      '2' -> SleepytrollProtocol.parseStatus(body)?.let { t ->
        val now = SystemClock.elapsedRealtime()
        _telemetry.value = t
        _lastStatusAt.value = now
        statusStale = false
        handler.removeCallbacks(staleRunnable)
        handler.postDelayed(staleRunnable, STATUS_STALE_MS)
        if (sensorTracker.onStatus(t.running, SleepytrollProtocol.isSensorMode(knownMode), now)) {
          _sensorActivity.value = sensorTracker.activity
        }
        if (t.running) _motorWarning.value = null // resumed → clear the rest notice
        _events.tryEmit(BleEvent.Status(t))
        updateNotification()
      }
      '3' -> {
        _deviceInfo.value = SleepytrollProtocol.parseCounters(body, _deviceInfo.value ?: DeviceInfo())
        _deviceInfo.value?.mode?.takeIf { it in 1..3 }?.let { knownMode = it }
      }
      '4' -> when (val c = SleepytrollProtocol.classifyChannel4(body)) {
        is Channel4.Ack -> _events.tryEmit(BleEvent.CommandAck(c.text))
        Channel4.WillDisconnect -> logEvent("Device signalled it will disconnect")
        Channel4.MotorWarning -> {
          _motorWarning.value = getString(R.string.warning_motor_rest)
          logEvent("Motor warning: 3-hour limit reached")
        }
        is Channel4.Other -> _events.tryEmit(BleEvent.CommandAck(c.text))
      }
      else -> _events.tryEmit(BleEvent.Info(line)) // 5 = light (only on .old firmware)
    }
  }
}
