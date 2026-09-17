package com.example.bleat.ble

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
import com.example.bleat.R
import com.example.bleat.ui.MainActivity
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
 * this class owns the connection, the write queue and the keep-alive. See SLEEPYTROLL_PROTOCOL.md.
 *
 * Threading: every GATT callback is re-posted to the main looper, so all mutable state below is
 * touched on the main thread only. The public API is called from the Activity (main thread).
 */
// MainActivity only reaches connect()/scan once BLUETOOTH_CONNECT and BLUETOOTH_SCAN are granted.
@SuppressLint("MissingPermission")
class BleForegroundService : Service() {

  companion object {
    private const val TAG = "BleService"
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

    // Keep-alive: re-arm before the device's hard 3-hour (180 min) auto-off. See PROTOCOL §4a.
    private const val REARM_MOTOR_MIN_DEFAULT = 165
    private const val REARM_ELAPSED_MS_DEFAULT = 165L * 60 * 1000
    private const val REARM_GRACE_MS = 10_000L          // our own stop/start reads as "stopped" briefly
    private const val REARM_MIN_GAP_MS = 5L * 60 * 1000 // never re-arm more often than this
    private const val REARM_VERIFY_TIMEOUT_MS = 90_000L // channel 3 is ~1 Hz; the counter should drop fast
  }

  private enum class Origin { USER, KEEPALIVE, HANDSHAKE }
  private data class Write(val cmd: String, val origin: Origin)

  private val binder = LocalBinder()
  private val handler = Handler(Looper.getMainLooper())
  private var bluetoothGatt: BluetoothGatt? = null
  private var writeChar: BluetoothGattCharacteristic? = null
  private var notifyChar: BluetoothGattCharacteristic? = null
  private val lines = LineAssembler()

  private val writeQueue = ArrayDeque<Write>()
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

  private var lastDevice: BluetoothDevice? = null
  private var retries = 0
  private var manualDisconnect = false

  // ---- keep-alive state ----------------------------------------------------
  private val _keepAliveEnabled = MutableStateFlow(false)
  val keepAliveEnabled = _keepAliveEnabled.asStateFlow()

  /** Human-readable outcome of the last re-arm, shown under the switch. */
  private val _keepAliveStatus = MutableStateFlow<String?>(null)
  val keepAliveStatus = _keepAliveStatus.asStateFlow()

  private var lastRunTimeCommand: String? = null // last AT+ST=..; the user sent, re-issued on re-arm
  private var runStartedAt: Long? = null         // elapsedRealtime when the device reported running
  private var userStoppedAt: Long? = null        // elapsedRealtime of the user's last stop command
  private var rearmAt: Long? = null              // elapsedRealtime of the last re-arm
  private var motorMinutesAtRearm: Int? = null   // channel-3 counter when we re-armed, for verification
  private var rearmMotorMin = REARM_MOTOR_MIN_DEFAULT
  private var rearmElapsedMs = REARM_ELAPSED_MS_DEFAULT

  inner class LocalBinder : Binder() {
    fun getService(): BleForegroundService = this@BleForegroundService
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    ServiceCompat.startForeground(
      this, NOTIF_ID, buildNotification("BLE service starting"),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    )
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
      NotificationChannel(CHANNEL_ID, "BLE Service", NotificationManager.IMPORTANCE_LOW)
    )
  }

  private fun buildNotification(content: String): Notification {
    val intent = Intent(this, MainActivity::class.java)
    val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Sleepytroll BLE")
      .setContentText(content)
      .setSmallIcon(R.drawable.ic_stat_moon)
      .setContentIntent(pending)
      .setOngoing(true)
      .build()
  }

  private fun logEvent(message: String) {
    Log.d(TAG, message)
    _events.tryEmit(BleEvent.Log(message))
    val nm = getSystemService(NotificationManager::class.java)
    nm.notify(NOTIF_ID, buildNotification(message))
  }

  // ---- public API ---------------------------------------------------------

  fun connect(device: BluetoothDevice) {
    handler.removeCallbacks(reconnectRunnable)
    disconnectGatt()
    manualDisconnect = false
    retries = 0
    lastDevice = device
    _connectionState.value = ConnState.CONNECTING
    logEvent("Connecting ${device.address}")
    bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  /** Queue an ASCII AT command (e.g. "AT+BH=01;"). Writes go out one at a time, in order. */
  fun sendCommand(cmd: String) = enqueue(cmd, Origin.USER)

  /** User-initiated disconnect: stop auto-reconnect and tear down. */
  fun userDisconnect() {
    manualDisconnect = true
    handler.removeCallbacks(reconnectRunnable)
    _connectionState.value = ConnState.DISCONNECTED
    disconnectGatt()
  }

  /**
   * Work around the device's 3-hour *runtime* auto-off.
   *
   * While enabled and the device reports that it is rocking, the service re-arms the firmware's
   * minute counter shortly before the cap by sending stop → start → the last run-timer command
   * (PROTOCOL §4a, option 1), then confirms on the following channel-3 frames that the motor-minute
   * counter dropped. The trigger is evaluated on every telemetry frame (~1 Hz) rather than by a
   * wall-clock timer, so it needs no alarms, survives Doze, and cannot fire while the device is
   * stopped: nothing here ever starts a motor that telemetry doesn't already report as running, and
   * a user stop cancels any queued re-arm. If the counter does not drop, keep-alive switches itself
   * off and says so, because on that firmware the sequence doesn't work.
   *
   * CAVEAT: this only targets the 3-hour runtime cap. Sensor/baby-monitor mode has its own,
   * separate per-bout and per-session duration limits (firmware counters, no AT command — see
   * FIRMWARE_ANALYSIS.md §6a) that this does NOT reset. So keep-alive is really a
   * continuous/manual-mode feature, not a way to run sensor mode indefinitely.
   */
  fun setKeepAlive(enabled: Boolean) {
    _keepAliveEnabled.value = enabled
    if (!enabled) {
      cancelPendingRearm(reason = null)
      _keepAliveStatus.value = null
    }
    logEvent("Keep-alive ${if (enabled) "on" else "off"}")
  }

  /** Debug builds only: shrink the thresholds so a re-arm can be watched in minutes, not hours. */
  fun setRearmThresholdsForTesting(motorMin: Int, elapsedMin: Int) {
    rearmMotorMin = motorMin
    rearmElapsedMs = elapsedMin * 60_000L
    logEvent("Keep-alive test thresholds: motor ≥ $motorMin min or running ≥ $elapsedMin min")
  }

  // ---- write queue --------------------------------------------------------

  private fun enqueue(cmd: String, origin: Origin) {
    if (bluetoothGatt == null || writeChar == null) {
      logEvent("Not connected, dropped $cmd")
      return
    }
    if (origin == Origin.USER) {
      when {
        cmd.startsWith(SleepytrollProtocol.RUN_TIMER_PREFIX) -> lastRunTimeCommand = cmd
        // The user pressing stop wins over any re-arm that is queued or about to be evaluated.
        cmd == SleepytrollProtocol.STOP -> {
          userStoppedAt = SystemClock.elapsedRealtime()
          cancelPendingRearm(reason = "user stopped rocking")
        }
        cmd == SleepytrollProtocol.START -> userStoppedAt = null
      }
    }
    writeQueue.addLast(Write(cmd, origin))
    drainWrites()
  }

  private fun drainWrites() {
    if (writeInFlight) return
    val g = bluetoothGatt ?: return
    val ch = writeChar ?: return
    while (true) {
      val w = writeQueue.removeFirstOrNull() ?: return
      val status = g.writeCharacteristic(
        ch, w.cmd.toByteArray(Charsets.US_ASCII), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      )
      if (status == BluetoothStatusCodes.SUCCESS) {
        writeInFlight = true
        handler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
        logEvent("→ ${w.cmd} (status=$status)")
        return
      }
      logEvent("→ ${w.cmd} rejected (status=$status)") // 201 = ERROR_GATT_WRITE_REQUEST_BUSY
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
    runStartedAt = null
    rearmAt = null
    motorMinutesAtRearm = null
  }

  // ---- keep-alive ---------------------------------------------------------

  private fun cancelPendingRearm(reason: String?) {
    val removed = writeQueue.removeAll { it.origin == Origin.KEEPALIVE }
    runStartedAt = null
    if (removed && reason != null) logEvent("Keep-alive: dropped queued re-arm ($reason)")
  }

  /** Called after every channel-2 and channel-3 frame. */
  private fun evaluateKeepAlive() {
    val now = SystemClock.elapsedRealtime()
    val t = _telemetry.value ?: return
    val motor = _deviceInfo.value?.motorMinutes
    val rearm = rearmAt

    // Verify the last re-arm: the motor counter should drop on the next channel-3 frames.
    val before = motorMinutesAtRearm
    if (rearm != null && before != null) {
      if (motor != null && motor < before) {
        _keepAliveStatus.value = "Re-arm verified ${clock()} · motor counter $before → $motor min"
        logEvent("Keep-alive: re-arm verified, motor counter $before → $motor")
        motorMinutesAtRearm = null
      } else if (now - rearm > REARM_VERIFY_TIMEOUT_MS) {
        _keepAliveStatus.value =
          "Re-arm at ${clock(rearm)} did not reset the motor counter (still ${motor ?: "?"} min). " +
            "Keep-alive switched off: it may not work on this firmware."
        logEvent("Keep-alive: re-arm NOT verified, motor counter still $motor; disabling")
        motorMinutesAtRearm = null
        _keepAliveEnabled.value = false
        return
      }
    }

    if (!t.running) {
      // Our own stop/start reads as "stopped" for a frame or two; keep the run anchor through it.
      if (rearm == null || now - rearm > REARM_GRACE_MS) runStartedAt = null
      return
    }
    val startedAt = runStartedAt ?: now.also { runStartedAt = it }
    if (!_keepAliveEnabled.value) return
    userStoppedAt?.let { if (now - it < REARM_GRACE_MS) return } // a stop is in flight
    if (rearm != null && now - rearm < REARM_MIN_GAP_MS) return
    val due = (motor != null && motor >= rearmMotorMin) || (now - startedAt >= rearmElapsedMs)
    if (due) performRearm(now, motor)
  }

  private fun performRearm(now: Long, motor: Int?) {
    logEvent("Keep-alive: re-arming (motor counter ${motor ?: "?"} min)")
    enqueue(SleepytrollProtocol.STOP, Origin.KEEPALIVE)
    enqueue(SleepytrollProtocol.START, Origin.KEEPALIVE)
    lastRunTimeCommand?.let { enqueue(it, Origin.KEEPALIVE) }
    rearmAt = now
    runStartedAt = now
    motorMinutesAtRearm = motor?.takeIf { it > 0 } // a zero counter can't be seen to drop
    _keepAliveStatus.value =
      if (motorMinutesAtRearm != null) "Re-armed ${clock()}, verifying…"
      else "Re-armed ${clock()} (no motor counter to verify against)"
    _events.tryEmit(BleEvent.Rearm)
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
            _connectionState.value = ConnState.CONNECTING // link up; not ready until notifications on
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
        _connectionState.value = ConnState.CONNECTED // ready to talk
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
    bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  private fun maybeReconnect() {
    if (manualDisconnect) { _connectionState.value = ConnState.DISCONNECTED; return }
    if (lastDevice == null) { _connectionState.value = ConnState.DISCONNECTED; return }
    if (retries >= MAX_RETRIES) {
      logEvent("Giving up reconnect")
      _connectionState.value = ConnState.DISCONNECTED
      return
    }
    retries++
    _connectionState.value = ConnState.RECONNECTING
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
        _telemetry.value = t
        if (t.running) _motorWarning.value = null // resumed → clear the rest notice
        _events.tryEmit(BleEvent.Status(t))
        evaluateKeepAlive()
      }
      '3' -> {
        _deviceInfo.value = SleepytrollProtocol.parseCounters(body, _deviceInfo.value ?: DeviceInfo())
        evaluateKeepAlive()
      }
      '4' -> when (val c = SleepytrollProtocol.classifyChannel4(body)) {
        is Channel4.Ack -> _events.tryEmit(BleEvent.CommandAck(c.text))
        Channel4.WillDisconnect -> logEvent("Device signalled it will disconnect")
        Channel4.MotorWarning -> {
          _motorWarning.value = "Max rocking time (3 h) reached — device is resting"
          logEvent("Motor warning: 3-hour limit reached")
        }
        is Channel4.Other -> _events.tryEmit(BleEvent.CommandAck(c.text))
      }
      else -> _events.tryEmit(BleEvent.Info(line)) // 5 = light (only on .old firmware)
    }
  }
}
