package com.trexx.shakeytroll.ble

/**
 * One sensor-triggered rocking bout. Times are elapsedRealtime millis; [endMs] is null while the
 * bout is still rocking.
 */
data class Bout(val startMs: Long, val endMs: Long? = null) {
  fun durationMs(nowMs: Long): Long = (endMs ?: nowMs) - startMs
}

/** Recent sensor triggers, oldest first, as tracked by [SensorActivityTracker]. */
data class SensorActivity(val bouts: List<Bout> = emptyList()) {
  val lastTriggerMs: Long? get() = bouts.lastOrNull()?.startMs
  /** The bout that is rocking right now, if the sensor started it. */
  val currentBout: Bout? get() = bouts.lastOrNull()?.takeIf { it.endMs == null }

  fun triggersSince(sinceMs: Long): Int = bouts.count { it.startMs >= sinceMs }

  /** Rocking time inside [sinceMs, nowMs], with bouts that straddle the start clipped to it. */
  fun rockedMsSince(sinceMs: Long, nowMs: Long): Long =
    bouts.sumOf { b -> ((b.endMs ?: nowMs).coerceAtMost(nowMs) - b.startMs.coerceAtLeast(sinceMs)).coerceAtLeast(0) }
}

/**
 * Infers sensor triggers from channel-2 run state: the firmware reports nothing more specific
 * (see FIRMWARE_ANALYSIS.md §6a), so a trigger is a standby → running edge while the device is in
 * sensor or baby-monitor mode, and the bout lasts until it stops rocking again.
 *
 * Starts the app caused itself (the user's start, the keep-alive's stop/start) are not triggers:
 * [noteManualStart] marks them, and a running edge within [manualGraceMs] of one is ignored.
 * The first frame after a (re)connect only seeds the state, since the edge itself wasn't seen.
 * History is pruned to [windowMs] and survives reconnects; [clear] drops it.
 *
 * Pure Kotlin, clocked by the caller, so it is unit-tested.
 */
class SensorActivityTracker(
  private val windowMs: Long = WINDOW_MS,
  private val manualGraceMs: Long = MANUAL_GRACE_MS,
) {
  companion object {
    const val WINDOW_MS = 60L * 60 * 1000
    const val MANUAL_GRACE_MS = 5_000L
  }

  var activity = SensorActivity()
    private set

  private var prevRunning: Boolean? = null
  private var manualStartAt: Long? = null
  private var lastStatusAt: Long? = null

  fun noteManualStart(nowMs: Long) {
    manualStartAt = nowMs
  }

  /** Feed every channel-2 frame. Returns true when [activity] changed. */
  fun onStatus(running: Boolean, sensorMode: Boolean, nowMs: Long): Boolean {
    val before = activity
    val wasRunning = prevRunning
    prevRunning = running
    lastStatusAt = nowMs
    var bouts = activity.bouts
    val open = activity.currentBout
    when {
      // Stopped, or left sensor mode mid-bout: the sensor bout is over.
      open != null && (!running || !sensorMode) -> bouts = bouts.dropLast(1) + open.copy(endMs = nowMs)
      sensorMode && running && wasRunning == false && !isManual(nowMs) -> bouts = bouts + Bout(nowMs)
    }
    if (running && wasRunning == false) manualStartAt = null // that start edge is spent
    activity = SensorActivity(prune(bouts, nowMs))
    return activity != before
  }

  /**
   * The link dropped: close any open bout at the last frame we saw (we can't know when it really
   * ended), and forget the run state so the first frame after reconnecting isn't read as an edge.
   */
  fun onLinkLost() {
    prevRunning = null
    manualStartAt = null
    val open = activity.currentBout ?: return
    val end = lastStatusAt ?: open.startMs
    activity = SensorActivity(activity.bouts.dropLast(1) + open.copy(endMs = end))
  }

  fun clear() {
    onLinkLost()
    activity = SensorActivity()
    lastStatusAt = null
  }

  private fun isManual(nowMs: Long) = manualStartAt?.let { nowMs - it <= manualGraceMs } == true

  private fun prune(bouts: List<Bout>, nowMs: Long): List<Bout> {
    val cutoff = nowMs - windowMs
    return if (bouts.none { (it.endMs ?: nowMs) < cutoff }) bouts else bouts.filter { (it.endMs ?: nowMs) >= cutoff }
  }
}
