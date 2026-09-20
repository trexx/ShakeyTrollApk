package com.trexx.shakeytroll.commands

import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.SleepytrollProtocol

/** The Sleepytroll control set and the exact AT command each one sends. */
object SleepytrollCommands {
  const val ROCKING = "bh"
  const val SPEED = "fr"
  const val MODE = "mode"
  const val SLEEP_PROGRAM = "sp"
  const val SOUND = "sh"
  const val MOVEMENT = "au"
  const val RUN_TIMER = "st"
  const val RESET = "reset"

  /** Firmware caps total motor time at 180 min (PROTOCOL §4a); the run timer can't exceed what's left. */
  const val RUN_TIMER_CAP_MIN = 180

  val models: List<CommandModel> = listOf(
    CommandModel.Toggle(
      id = ROCKING, labelRes = R.string.cmd_rocking,
      default = false, onCommand = SleepytrollProtocol.START, offCommand = SleepytrollProtocol.STOP
    ),
    CommandModel.Slider(
      id = SPEED, labelRes = R.string.cmd_speed,
      min = 0, max = 100, step = 1, default = 50, template = "AT+FR=%02x;", unit = "%"
    ),
    CommandModel.Options(
      id = MODE, labelRes = R.string.cmd_mode,
      choices = listOf(
        CommandModel.Options.Choice(R.string.cmd_mode_continuous, "AT+MODE=01;"),
        CommandModel.Options.Choice(R.string.cmd_mode_sensor, "AT+MODE=02;"),
        CommandModel.Options.Choice(R.string.cmd_mode_baby_monitor, "AT+MODE=03;")
      ),
      defaultIndex = 0
    ),
    CommandModel.Options(
      id = SLEEP_PROGRAM, labelRes = R.string.cmd_sleep_program,
      choices = listOf(
        CommandModel.Options.Choice(R.string.cmd_sleep_short, "AT+SP=S;"),
        CommandModel.Options.Choice(R.string.cmd_sleep_medium, "AT+SP=M;"),
        CommandModel.Options.Choice(R.string.cmd_sleep_long, "AT+SP=L;")
      ),
      defaultIndex = 0
    ),
    CommandModel.Slider(
      id = SOUND, labelRes = R.string.cmd_sound_sensitivity,
      min = 0, max = 4, step = 1, default = 2, template = "AT+SH=%02x;"
    ),
    CommandModel.Slider(
      id = MOVEMENT, labelRes = R.string.cmd_movement_sensitivity,
      min = 0, max = 4, step = 1, default = 2, template = "AT+AU=%02x;"
    ),
    CommandModel.Slider(
      // Official-app range: [10, 180 − motorTime]; the live max is set by syncRunTimerBudget.
      id = RUN_TIMER, labelRes = R.string.cmd_run_timer,
      min = 10, max = RUN_TIMER_CAP_MIN, step = 5, default = 30,
      template = SleepytrollProtocol.RUN_TIMER_PREFIX + "%02x;", unit = " min"
    ),
    CommandModel.Action(
      id = RESET, labelRes = R.string.cmd_reset, command = "AT+RESET", confirm = true
    )
  )
}
