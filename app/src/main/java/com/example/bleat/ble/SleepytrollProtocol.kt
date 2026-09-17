package com.example.bleat.ble

import java.util.Locale

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
    get() = String.format(
      Locale.ROOT, "%02d:%02d:%02d", timerSeconds / 3600, (timerSeconds % 3600) / 60, timerSeconds % 60
    )
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
  /** The keep-alive just queued its stop/start/timer re-arm sequence. */
  data object Rearm : BleEvent()
}

/** What a channel-4 body means. Routing mirrors the official app (handleFourthPacket). */
sealed class Channel4 {
  data class Ack(val text: String) : Channel4()
  data object WillDisconnect : Channel4()
  data object MotorWarning : Channel4()
  data class Other(val text: String) : Channel4()
}

/**
 * Pure decoding of the Sleepytroll's channel-tagged telemetry lines ("N,payload") and the wire
 * strings the app sends. No Android dependencies, so it is unit-tested; BleForegroundService
 * owns the I/O and connection state. See docs/SLEEPYTROLL_PROTOCOL.md.
 */
object SleepytrollProtocol {
  const val START = "AT+BH=01;"
  const val STOP = "AT+BH=00;"
  const val HANDSHAKE = "AT+OK;"          // client-present ack the device expects after connect
  const val RUN_TIMER_PREFIX = "AT+ST="   // AT+ST=<minutes as hex byte>;

  /** Split "N,payload" into the channel digit and trimmed body; null when there is no prefix. */
  fun splitChannel(line: String): Pair<Char, String>? {
    val comma = line.indexOf(',')
    if (comma < 1) return null
    return line[0] to line.substring(comma + 1).trim()
  }

  /**
   * Channel 4 = command acks and device status:
   *  - "OK"            → plain command ack
   *  - "0000…"         → the device is about to disconnect (handleZeroResponse), not a warning
   *  - else, len ≥ 6 & byte[4:6]=="01" → 3-hour "needs to rest" motor warning (handleStatusCommand)
   *  - else            → other status frame (leading byte is a status/pop code 01–05; see
   *                      APK_ANALYSIS.md §5a).
   *
   * CAVEAT: reverse-engineered from app 1.6.7 and NOT verified against this firmware, which emits
   * 3-byte "4,xxxxxx" status frames; only "4,OK" has been seen on the wire. Offsets may differ.
   */
  fun classifyChannel4(body: String): Channel4 = when {
    body == "OK" -> Channel4.Ack(body)
    body.startsWith("0000") -> Channel4.WillDisconnect
    body.length >= 6 && body.substring(4, 6) == "01" -> Channel4.MotorWarning
    else -> Channel4.Other(body)
  }

  /** Channel 1: "serial-hwid.x.build" (e.g. 586949573-28360.0.94ffa) on this firmware. */
  fun parseIdentity(body: String, prev: DeviceInfo): DeviceInfo = prev.copy(
    serial = body.substringBefore('-', body),
    version = body.substringAfter('-', "").ifEmpty { null }
  )

  /**
   * Channel 3 usage counters. Byte layout per the official app (which reads the channel with its
   * "3," prefix, so these offsets are shifted by −2 here). Field *meanings* are stable across
   * firmware but exact values can drift between versions, so treat as informational.
   * Returns [prev] unchanged when the body is too short.
   */
  fun parseCounters(hex: String, prev: DeviceInfo): DeviceInfo {
    if (hex.length < 14) return prev
    fun u8(i: Int) = hex.substring(i, i + 2).toIntOrNull(16)
    fun u16(i: Int) = hex.substring(i, i + 4).toIntOrNull(16)
    val mode = u8(0)               // babyRockerType: 1=continuous 2=sensor 3=baby-monitor
    val batteryCycles = u16(6)     // cellCount
    val deviceTotalMin = u16(10)   // devTotal
    val motorMinutes = if (hex.length >= 18) (u8(14) ?: 0) * 60 + (u8(16) ?: 0) else null
    return prev.copy(
      mode = mode, batteryCycles = batteryCycles,
      deviceTotalMin = deviceTotalMin, motorMinutes = motorMinutes
    )
  }

  /** Channel 2 = 8 data bytes (+ optional CRC16). See PROTOCOL §4 for the byte map. */
  fun parseStatus(hex: String): Telemetry? {
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

/**
 * Reassembles "\r\n"-terminated lines from BLE notifications. A notification may carry several
 * lines, or only part of one (a line can straddle two packets), so bytes are buffered and only
 * complete lines are returned. Blank lines are dropped. If garbage accumulates past [maxBuffer]
 * characters without a terminator the buffer is discarded rather than growing forever.
 */
class LineAssembler(private val maxBuffer: Int = 512) {
  private val buffer = StringBuilder()

  fun feed(bytes: ByteArray): List<String> = feed(String(bytes, Charsets.US_ASCII))

  fun feed(text: String): List<String> {
    buffer.append(text)
    val lines = mutableListOf<String>()
    while (true) {
      val end = buffer.indexOf("\r\n")
      if (end < 0) break
      val line = buffer.substring(0, end)
      buffer.delete(0, end + 2)
      if (line.isNotBlank()) lines += line
    }
    if (buffer.length > maxBuffer) buffer.setLength(0)
    return lines
  }

  fun reset() {
    buffer.setLength(0)
  }
}
