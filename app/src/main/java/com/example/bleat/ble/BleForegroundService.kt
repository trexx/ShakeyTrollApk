package com.example.bleat.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

/**
 * BLE client for the Sleepytroll baby rocker.
 *
 * The device is a Microchip/ISSC "transparent UART" module bridged to an application MCU.
 * The phone writes ASCII AT commands (terminated with ';') to the write characteristic with
 * *write-without-response*, and the device streams back channel-tagged telemetry lines
 * ("N,payload\r\n") on the notify characteristic. See SLEEPYTROLL_PROTOCOL.md.
 */
class BleForegroundService : Service() {

  companion object {
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

    // Re-arm before the device's hard 3-hour (180 min) auto-off. See PROTOCOL §4a.
    private const val KEEPALIVE_INTERVAL_MS = 165L * 60 * 1000 // 2 h 45 m
  }

  private val binder = LocalBinder()
  private var bluetoothGatt: BluetoothGatt? = null
  private var writeChar: BluetoothGattCharacteristic? = null
  private var notifyChar: BluetoothGattCharacteristic? = null

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

  private val handler = Handler(Looper.getMainLooper())
  private var lastDevice: BluetoothDevice? = null
  private var retries = 0
  private var manualDisconnect = false

  // Keep-alive state
  private var keepAliveEnabled = false
  private var lastRunTimeCommand: String? = null // last AT+ST=..; so we can re-arm it

  inner class LocalBinder : Binder() {
    fun getService(): BleForegroundService = this@BleForegroundService
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startForeground(NOTIF_ID, buildNotification("BLE service starting"))
  }

  override fun onDestroy() {
    manualDisconnect = true
    handler.removeCallbacksAndMessages(null)
    disconnectGatt()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      val channel = NotificationChannel(CHANNEL_ID, "BLE Service", NotificationManager.IMPORTANCE_LOW)
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(content: String): Notification {
    val intent = packageManager.getLaunchIntentForPackage(packageName)
    val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Sleepytroll BLE")
      .setContentText(content)
      .setSmallIcon(com.example.bleat.R.drawable.ic_stat_moon)
      .setContentIntent(pending)
      .setOngoing(true)
      .build()
  }

  private fun logEvent(message: String) {
    _events.tryEmit(BleEvent.Log(message))
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIF_ID, buildNotification(message))
  }

  // ---- public API ---------------------------------------------------------

  fun connect(device: BluetoothDevice) {
    disconnectGatt()
    manualDisconnect = false
    retries = 0
    lastDevice = device
    _connectionState.value = ConnState.CONNECTING
    logEvent("Connecting ${device.address}")
    bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  /** Send an ASCII AT command (e.g. "AT+BH=01;"). Uses write-without-response. */
  fun sendCommand(cmd: String) {
    val g = bluetoothGatt ?: run { logEvent("No GATT connection"); return }
    val char = writeChar ?: run { logEvent("Write characteristic not found"); return }
    if (cmd.startsWith("AT+ST=")) lastRunTimeCommand = cmd
    val status = g.writeCharacteristic(
      char,
      cmd.toByteArray(Charsets.US_ASCII),
      BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    )
    logEvent("→ $cmd (status=$status)") // 0 = BluetoothStatusCodes.SUCCESS
  }

  /** User-initiated disconnect: stop auto-reconnect and tear down. */
  fun userDisconnect() {
    manualDisconnect = true
    handler.removeCallbacks(keepAliveRunnable)
    _connectionState.value = ConnState.DISCONNECTED
    disconnectGatt()
  }

  fun disconnectGatt() {
    bluetoothGatt?.let { it.disconnect(); it.close() }
    bluetoothGatt = null
    writeChar = null
    notifyChar = null
    _telemetry.value = null
    _deviceInfo.value = null
    _motorWarning.value = null
  }

  /**
   * Work around the device's 3-hour *runtime* auto-off by periodically re-arming while running.
   * NOTE: whether re-arming resets the firmware's minute counter must be confirmed on hardware
   * (watch channel-3 motorTime); if it does not, use AT+RESET instead. See PROTOCOL §4a.
   *
   * CAVEAT: this only targets the 3-hour runtime cap. Sensor/baby-monitor mode has its own,
   * separate per-bout and per-session duration limits (firmware counters, no AT command — see
   * FIRMWARE_ANALYSIS.md §6a) that this does NOT reset. So keep-alive is really a
   * continuous/manual-mode feature, not a way to run sensor mode indefinitely.
   */
  fun setKeepAlive(enabled: Boolean) {
    keepAliveEnabled = enabled
    handler.removeCallbacks(keepAliveRunnable)
    if (enabled) handler.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS)
    logEvent("Keep-alive ${if (enabled) "on" else "off"}")
  }

  private val keepAliveRunnable = object : Runnable {
    override fun run() {
      if (!keepAliveEnabled) return
      lastRunTimeCommand?.let { sendCommand(it) } // re-arm the run timer
      sendCommand("AT+BH=01;")                     // ensure still running
      handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
    }
  }

  // ---- GATT callback ------------------------------------------------------

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          logEvent("Connected, discovering services")
          retries = 0
          _connectionState.value = ConnState.CONNECTING // link up; not ready until notifications on
          g.discoverServices()
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          logEvent("Disconnected (status=$status)")
          disconnectGatt()
          maybeReconnect()
        }
      }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      if (status != BluetoothGatt.GATT_SUCCESS) {
        logEvent("Service discovery failed: $status"); return
      }
      val svc = g.services.firstOrNull { s ->
        s.characteristics.any { it.uuid == WRITE_UUID || it.uuid == NOTIFY_UUID }
      } ?: run { logEvent("Sleepytroll service not found"); return }
      writeChar = svc.getCharacteristic(WRITE_UUID)
      notifyChar = svc.getCharacteristic(NOTIFY_UUID)
      // Negotiate MTU first; notifications are enabled once it settles (matches the app).
      g.requestMtu(REQUEST_MTU)
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
      logEvent("MTU=$mtu")
      val nc = notifyChar ?: run { logEvent("Notify characteristic missing"); return }
      g.setCharacteristicNotification(nc, true)
      val desc = nc.getDescriptor(CCCD) ?: run { logEvent("CCCD missing"); return }
      g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      if (descriptor.uuid == CCCD) {
        logEvent("Notifications enabled — sending handshake")
        _connectionState.value = ConnState.CONNECTED // ready to talk
        sendCommand("AT+OK;") // client-present ack the device expects after connect
      }
    }

    override fun onCharacteristicChanged(
      g: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray
    ) {
      if (characteristic.uuid != NOTIFY_UUID) return
      val text = String(value, Charsets.US_ASCII)
      text.split("\r\n").filter { it.isNotBlank() }.forEach(::onLine)
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      if (status != BluetoothGatt.GATT_SUCCESS) logEvent("Write failed: $status")
    }
  }

  private fun maybeReconnect() {
    if (manualDisconnect) { _connectionState.value = ConnState.DISCONNECTED; return }
    val device = lastDevice ?: run { _connectionState.value = ConnState.DISCONNECTED; return }
    if (retries >= MAX_RETRIES) {
      logEvent("Giving up reconnect")
      _connectionState.value = ConnState.DISCONNECTED
      return
    }
    retries++
    _connectionState.value = ConnState.RECONNECTING
    logEvent("Reconnect attempt $retries/$MAX_RETRIES")
    handler.postDelayed({
      bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }, RETRY_INTERVAL_MS)
  }

  // ---- inbound line parsing ----------------------------------------------

  /** A line looks like "N,payload": 1 = identity, 2 = live status, 3 = counters, 4 = ack/status. */
  private fun onLine(line: String) {
    val comma = line.indexOf(',')
    if (comma < 1) { _events.tryEmit(BleEvent.Info(line)); return }
    val channel = line[0]
    val body = line.substring(comma + 1).trim()
    when (channel) {
      '1' -> parseIdentity(body)
      '2' -> parseStatus(body)?.let { t ->
        _telemetry.value = t
        if (t.running) _motorWarning.value = null // resumed → clear the rest notice
        _events.tryEmit(BleEvent.Status(t))
      }
      '3' -> parseCounters(body)
      '4' -> decodeChannel4(body)
      else -> _events.tryEmit(BleEvent.Info(line)) // 5 = light (only on .old firmware)
    }
  }

  /**
   * Channel 4 = command acks and device status. Routing mirrors the official app (handleFourthPacket):
   *  - "OK"            → plain command ack
   *  - "0000…"         → the device is about to disconnect (handleZeroResponse), not a warning
   *  - else, len ≥ 6 & byte[4:6]=="01" → 3-hour "needs to rest" motor warning (handleStatusCommand)
   *  - else            → other status frame (leading byte is a status/pop code 01–05; see
   *                      APK_ANALYSIS.md §5a). Surfaced as an ack line for now.
   *
   * CAVEAT: reverse-engineered from app 1.6.7 and NOT verified against this firmware, which emits
   * 3-byte "4,xxxxxx" status frames; only "4,OK" has been seen on the wire. Offsets may differ.
   */
  private fun decodeChannel4(body: String) {
    when {
      body == "OK" -> _events.tryEmit(BleEvent.CommandAck(body))
      body.startsWith("0000") -> logEvent("Device signalled it will disconnect")
      body.length >= 6 && body.substring(4, 6) == "01" -> {
        _motorWarning.value = "Max rocking time (3 h) reached — device is resting"
        logEvent("Motor warning: 3-hour limit reached")
      }
      else -> _events.tryEmit(BleEvent.CommandAck(body))
    }
  }

  /** Channel 1: "serial-hwid.x.build" (e.g. 586949573-28360.0.94ffa) on this firmware. */
  private fun parseIdentity(body: String) {
    val serial = body.substringBefore('-', body)
    val version = body.substringAfter('-', "").ifEmpty { null }
    _deviceInfo.value = (_deviceInfo.value ?: DeviceInfo()).copy(serial = serial, version = version)
    _events.tryEmit(BleEvent.Info("id: $body"))
  }

  /**
   * Channel 3 usage counters. Byte layout per the official app (which reads the channel with its
   * "3," prefix, so these offsets are shifted by −2 here). Field *meanings* are stable across
   * firmware but exact values can drift between versions, so treat as informational.
   */
  private fun parseCounters(hex: String) {
    if (hex.length < 14) return
    fun u8(i: Int) = hex.substring(i, i + 2).toIntOrNull(16)
    fun u16(i: Int) = hex.substring(i, i + 4).toIntOrNull(16)
    val mode = u8(0)               // babyRockerType: 1=continuous 2=sensor 3=baby-monitor
    val batteryCycles = u16(6)     // cellCount
    val deviceTotalMin = u16(10)   // devTotal
    val motorMinutes = if (hex.length >= 18) (u8(14) ?: 0) * 60 + (u8(16) ?: 0) else null
    _deviceInfo.value = (_deviceInfo.value ?: DeviceInfo()).copy(
      mode = mode, batteryCycles = batteryCycles,
      deviceTotalMin = deviceTotalMin, motorMinutes = motorMinutes
    )
  }

  /** Channel 2 = 8 data bytes (+ optional CRC16). See PROTOCOL §4 for the byte map. */
  private fun parseStatus(hex: String): Telemetry? {
    if (hex.length < 16) return null
    fun b(i: Int): Int? = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)
    val battery = b(0) ?: return null
    val state = b(1) ?: return null
    val speed = b(2) ?: return null
    val sound = b(3) ?: return null
    val move = b(4) ?: return null
    val h = b(5) ?: return null
    val m = b(6) ?: return null
    val s = b(7) ?: return null
    return Telemetry(
      batteryPct = battery,
      running = state == 1, // 1 = running, 3 = standby/stopped
      standby = state == 3,
      speed = speed,
      soundSensitivity = sound.coerceAtMost(4),
      movementSensitivity = move.coerceAtMost(4),
      timerSeconds = h * 3600 + m * 60 + s
    )
  }
}

/** Decoded channel-2 status. */
data class Telemetry(
  val batteryPct: Int,
  val running: Boolean,
  val standby: Boolean,
  val speed: Int,
  val soundSensitivity: Int,
  val movementSensitivity: Int,
  val timerSeconds: Int
) {
  val timerText: String
    get() = "%02d:%02d:%02d".format(timerSeconds / 3600, (timerSeconds % 3600) / 60, timerSeconds % 60)
  /** Low-battery threshold matches the official app (electricQuantity < 6). */
  val lowBattery: Boolean get() = batteryPct in 0 until 6
}

/** BLE connection lifecycle for the UI. */
enum class ConnState { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED }

/** Slower-changing info from channels 1 (identity) and 3 (usage counters). */
data class DeviceInfo(
  val serial: String? = null,
  val version: String? = null,
  val mode: Int? = null,           // 1=continuous, 2=sensor, 3=baby-monitor
  val batteryCycles: Int? = null,
  val deviceTotalMin: Int? = null,
  val motorMinutes: Int? = null    // counts toward the 180-min (3 h) cap
)

sealed class BleEvent {
  data class Log(val message: String) : BleEvent()
  data class CommandAck(val text: String) : BleEvent()   // channel 4, e.g. "OK"
  data class Status(val telemetry: Telemetry) : BleEvent() // channel 2
  data class Info(val line: String) : BleEvent()           // channels 1/3/5
}
