package com.example.bleat.commands

import com.example.bleat.ble.SleepytrollProtocol

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

  val models: List<CommandModel> = listOf(
    CommandModel.Toggle(
      id = ROCKING, label = "Rocking",
      default = false, onCommand = SleepytrollProtocol.START, offCommand = SleepytrollProtocol.STOP
    ),
    CommandModel.Slider(
      id = SPEED, label = "Speed",
      min = 0, max = 100, step = 1, default = 50, template = "AT+FR=%02x;", unit = "%"
    ),
    CommandModel.Options(
      id = MODE, label = "Mode",
      choices = listOf(
        CommandModel.Options.Choice("Continuous", "AT+MODE=01;"),
        CommandModel.Options.Choice("Sensor", "AT+MODE=02;"),
        CommandModel.Options.Choice("Baby monitor", "AT+MODE=03;")
      ),
      defaultIndex = 0
    ),
    CommandModel.Options(
      id = SLEEP_PROGRAM, label = "Sleep program",
      choices = listOf(
        CommandModel.Options.Choice("Short", "AT+SP=S;"),
        CommandModel.Options.Choice("Medium", "AT+SP=M;"),
        CommandModel.Options.Choice("Long", "AT+SP=L;")
      ),
      defaultIndex = 0
    ),
    CommandModel.Slider(
      id = SOUND, label = "Sound sensitivity",
      min = 0, max = 4, step = 1, default = 2, template = "AT+SH=%02x;"
    ),
    CommandModel.Slider(
      id = MOVEMENT, label = "Movement sensitivity",
      min = 0, max = 4, step = 1, default = 2, template = "AT+AU=%02x;"
    ),
    CommandModel.Slider(
      id = RUN_TIMER, label = "Run timer",
      min = 0, max = 180, step = 5, default = 30,
      template = SleepytrollProtocol.RUN_TIMER_PREFIX + "%02x;", unit = " min"
    ),
    CommandModel.Action(
      id = RESET, label = "Reset device", command = "AT+RESET", confirm = true
    )
  )
}
