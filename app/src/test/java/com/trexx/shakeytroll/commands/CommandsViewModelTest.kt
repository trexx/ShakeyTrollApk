package com.trexx.shakeytroll.commands

import com.trexx.shakeytroll.ble.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsViewModelTest {
  private val sent = mutableListOf<String>()
  private val vm = CommandsViewModel().apply { sender = { sent += it } }
  private fun state(id: String) = vm.uiState.value.first { it.id == id }

  @Test fun `slider values are hex-encoded and clamped`() {
    vm.onSlider(SleepytrollCommands.SPEED, 50)
    vm.onSlider(SleepytrollCommands.RUN_TIMER, 999)
    vm.onSlider(SleepytrollCommands.SOUND, -3)
    assertEquals(listOf("AT+FR=32;", "AT+ST=b4;", "AT+SH=00;"), sent)
    assertEquals(180, state(SleepytrollCommands.RUN_TIMER).intValue)
  }

  @Test fun `toggle and options send their exact wire strings`() {
    vm.onToggle(SleepytrollCommands.ROCKING, true)
    vm.onOption(SleepytrollCommands.SLEEP_PROGRAM, 2)
    vm.onAction(SleepytrollCommands.RESET)
    assertEquals(listOf("AT+BH=01;", "AT+SP=L;", "AT+RESET"), sent)
  }

  @Test fun `unknown ids and out-of-range options send nothing`() {
    vm.onToggle("nope", true)
    vm.onOption(SleepytrollCommands.MODE, 7)
    assertTrue(sent.isEmpty())
  }

  @Test fun `telemetry moves untouched controls but not one the user just changed`() {
    val t = Telemetry(
      batteryPct = 80, running = true, standby = false, speed = 70,
      soundSensitivity = 1, movementSensitivity = 4, timerSeconds = 0
    )
    vm.onSlider(SleepytrollCommands.SPEED, 20) // suppressed for a moment
    vm.syncFromTelemetry(t)
    assertEquals(20, state(SleepytrollCommands.SPEED).intValue)
    assertTrue(state(SleepytrollCommands.ROCKING).boolValue)
    assertEquals(1, state(SleepytrollCommands.SOUND).intValue)
    assertEquals(4, state(SleepytrollCommands.MOVEMENT).intValue)
  }

  @Test fun `explicit suppression holds a control against telemetry`() {
    val t = Telemetry(
      batteryPct = 80, running = false, standby = false, speed = 0,
      soundSensitivity = 0, movementSensitivity = 0, timerSeconds = 0
    )
    vm.onToggle(SleepytrollCommands.ROCKING, true)
    vm.suppress(SleepytrollCommands.ROCKING, 60_000)
    vm.syncFromTelemetry(t)
    assertTrue(state(SleepytrollCommands.ROCKING).boolValue)
    vm.suppress(SleepytrollCommands.ROCKING, 0)
    vm.syncFromTelemetry(t)
    assertFalse(state(SleepytrollCommands.ROCKING).boolValue)
  }

  @Test fun `mode syncs from channel 3 type`() {
    vm.syncMode(3)
    assertEquals(2, state(SleepytrollCommands.MODE).selectedIndex)
    vm.syncMode(0) // invalid, ignored
    assertEquals(2, state(SleepytrollCommands.MODE).selectedIndex)
  }
}
