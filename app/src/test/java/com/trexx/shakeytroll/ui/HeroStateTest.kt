package com.trexx.shakeytroll.ui

import com.trexx.shakeytroll.ble.Bout
import com.trexx.shakeytroll.ble.ConnState
import com.trexx.shakeytroll.ble.SleepytrollProtocol
import com.trexx.shakeytroll.ui.components.HeroState
import com.trexx.shakeytroll.ui.components.formatDuration
import com.trexx.shakeytroll.ui.components.heroState
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroStateTest {
  private val standby = SleepytrollProtocol.parseStatus("5d03320203000000")!!
  private val running = SleepytrollProtocol.parseStatus("5d01320203000000")!!

  private fun hero(t: com.trexx.shakeytroll.ble.Telemetry, sensor: Boolean, bout: Bout? = null) =
    heroState(ConnState.CONNECTED, t, bh = null, fr = null, sensorMode = sensor, bout = bout)

  @Test fun `standby only reads as listening in a sensor mode`() {
    assertEquals(HeroState.Listening(50), hero(standby, sensor = true))
    assertEquals(HeroState.Stopped(50), hero(standby, sensor = false))
  }

  @Test fun `a sensor bout marks the running state as triggered`() {
    assertEquals(HeroState.Running(50, 1_234), hero(running, sensor = true, bout = Bout(1_234)))
    assertEquals(HeroState.Running(50, null), hero(running, sensor = false))
  }

  @Test fun `durations format as minutes and seconds, hours when needed`() {
    assertEquals("0:05", formatDuration(5_000))
    assertEquals("2:13", formatDuration(133_000))
    assertEquals("1:02:03", formatDuration(3_723_000))
    assertEquals("0:00", formatDuration(-1))
  }
}
