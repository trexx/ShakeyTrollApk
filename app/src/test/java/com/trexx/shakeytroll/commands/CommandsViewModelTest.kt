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
    vm.onSlider(SleepytrollCommands.RUN_TIMER, 3) // below the official 10-min minimum
    assertEquals(listOf("AT+FR=32;", "AT+ST=b4;", "AT+SH=00;", "AT+ST=0a;"), sent)
    assertEquals(10, state(SleepytrollCommands.RUN_TIMER).intValue)
  }

  @Test fun `run timer max follows the motor budget`() {
    val st = SleepytrollCommands.RUN_TIMER
    vm.syncRunTimerBudget(12)   // 168 left → rounded down to the 5-min step
    assertEquals(165, state(st).max)
    assertEquals(30, state(st).intValue) // the default is still inside the range
    vm.syncRunTimerBudget(170)  // 10 left → the floor of the range
    assertEquals(10, state(st).max)
    assertEquals(10, state(st).intValue)
    vm.syncRunTimerBudget(179)  // less than the minimum still clamps to it, never below
    assertEquals(10, state(st).max)
    vm.syncRunTimerBudget(null) // no channel-3 frame yet → full range
    assertEquals(180, state(st).max)
  }

  @Test fun `run timer never sends more than the remaining budget`() {
    vm.syncRunTimerBudget(120) // 60 left
    vm.onSlider(SleepytrollCommands.RUN_TIMER, 120)
    assertEquals(listOf("AT+ST=3c;"), sent)
    assertEquals(60, state(SleepytrollCommands.RUN_TIMER).intValue)
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
