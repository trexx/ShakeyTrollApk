package com.trexx.shakeytroll.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorActivityTrackerTest {
  private val tracker = SensorActivityTracker(windowMs = 60_000, manualGraceMs = 5_000)
  private fun bouts() = tracker.activity.bouts

  @Test fun `standby to running in sensor mode is a trigger and ends when it stops`() {
    tracker.onStatus(running = false, sensorMode = true, nowMs = 1_000)
    assertTrue(tracker.onStatus(running = true, sensorMode = true, nowMs = 2_000))
    assertEquals(listOf(Bout(2_000)), bouts())
    assertEquals(Bout(2_000), tracker.activity.currentBout)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 3_000) // still the same bout
    tracker.onStatus(running = false, sensorMode = true, nowMs = 9_000)
    assertEquals(listOf(Bout(2_000, 9_000)), bouts())
    assertNull(tracker.activity.currentBout)
    assertEquals(2_000L, tracker.activity.lastTriggerMs)
  }

  @Test fun `continuous mode never records triggers`() {
    tracker.onStatus(running = false, sensorMode = false, nowMs = 1_000)
    assertFalse(tracker.onStatus(running = true, sensorMode = false, nowMs = 2_000))
    assertTrue(bouts().isEmpty())
  }

  @Test fun `first frame after connecting only seeds the state`() {
    tracker.onStatus(running = true, sensorMode = true, nowMs = 1_000)
    assertTrue(bouts().isEmpty())
  }

  @Test fun `a start we sent is not a trigger, but a later edge is`() {
    tracker.onStatus(running = false, sensorMode = true, nowMs = 1_000)
    tracker.noteManualStart(1_500)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 2_000)
    assertTrue(bouts().isEmpty())
    tracker.onStatus(running = false, sensorMode = true, nowMs = 3_000)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 4_000) // inside the grace, but spent
    assertEquals(listOf(Bout(4_000)), bouts())
  }

  @Test fun `an old manual start does not mask a trigger`() {
    tracker.onStatus(running = false, sensorMode = true, nowMs = 1_000)
    tracker.noteManualStart(1_000)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 20_000)
    assertEquals(listOf(Bout(20_000)), bouts())
  }

  @Test fun `leaving sensor mode mid-bout closes it`() {
    tracker.onStatus(running = false, sensorMode = true, nowMs = 1_000)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 2_000)
    tracker.onStatus(running = true, sensorMode = false, nowMs = 5_000)
    assertEquals(listOf(Bout(2_000, 5_000)), bouts())
  }

  @Test fun `link loss closes the bout at the last frame and the reconnect frame is not an edge`() {
    tracker.onStatus(running = false, sensorMode = true, nowMs = 1_000)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 2_000)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 6_000)
    tracker.onLinkLost()
    assertEquals(listOf(Bout(2_000, 6_000)), bouts())
    tracker.onStatus(running = true, sensorMode = true, nowMs = 30_000)
    assertEquals(1, bouts().size)
  }

  @Test fun `bouts that ended before the window are pruned, straddling ones are clipped`() {
    tracker.onStatus(running = false, sensorMode = true, nowMs = 0)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 1_000)
    tracker.onStatus(running = false, sensorMode = true, nowMs = 11_000)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 40_000)
    tracker.onStatus(running = false, sensorMode = true, nowMs = 70_000)
    // At 70 s the window starts at 10 s: the first bout (1–11 s) still overlaps it.
    assertEquals(2, bouts().size)
    assertEquals(1_000L + 30_000L, tracker.activity.rockedMsSince(10_000, 70_000))
    assertEquals(1, tracker.activity.triggersSince(10_000))
    tracker.onStatus(running = false, sensorMode = true, nowMs = 80_000)
    assertEquals(listOf(Bout(40_000, 70_000)), bouts())
  }

  @Test fun `an open bout counts up to now`() {
    val a = SensorActivity(listOf(Bout(10_000)))
    assertEquals(5_000L, a.rockedMsSince(0, 15_000))
    assertEquals(5_000L, Bout(10_000).durationMs(15_000))
  }

  @Test fun `clear forgets everything`() {
    tracker.onStatus(running = false, sensorMode = true, nowMs = 1_000)
    tracker.onStatus(running = true, sensorMode = true, nowMs = 2_000)
    tracker.clear()
    assertTrue(bouts().isEmpty())
  }

  @Test fun `mode helpers`() {
    assertTrue(SleepytrollProtocol.isSensorMode(2))
    assertTrue(SleepytrollProtocol.isSensorMode(3))
    assertFalse(SleepytrollProtocol.isSensorMode(1))
    assertFalse(SleepytrollProtocol.isSensorMode(null))
    assertEquals(2, SleepytrollProtocol.modeOf("AT+MODE=02;"))
    assertNull(SleepytrollProtocol.modeOf("AT+FR=32;"))
  }
}
