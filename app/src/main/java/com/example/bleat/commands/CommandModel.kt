package com.example.bleat.commands

/**
 * A Sleepytroll control and how it maps to an AT command. Each model owns its exact wire
 * string(s) so the UI never has to guess (unlike the old `AT+<KEY>=...` synthesis, which
 * couldn't express hex vs decimal vs letter arguments).
 */
sealed class CommandModel(val id: String, val label: String) {

  /** On/off. e.g. start/stop rocking → AT+BH=01; / AT+BH=00;. */
  class Toggle(
    id: String,
    label: String,
    val default: Boolean,
    val onCommand: String,
    val offCommand: String
  ) : CommandModel(id, label)

  /**
   * Integer in [min,max]. [template] is a String.format template, e.g. "AT+FR=%02x;" (hex)
   * or "AT+ST=%02x;" (minutes as hex).
   */
  class Slider(
    id: String,
    label: String,
    val min: Int,
    val max: Int,
    val step: Int,
    val default: Int,
    val template: String,
    val unit: String = ""
  ) : CommandModel(id, label)

  /** One of several named choices, each mapped to a complete command. */
  class Options(
    id: String,
    label: String,
    val choices: List<Choice>,
    val defaultIndex: Int
  ) : CommandModel(id, label) {
    data class Choice(val label: String, val command: String)
  }

  /** One-shot command button. */
  class Action(
    id: String,
    label: String,
    val command: String,
    val confirm: Boolean = false
  ) : CommandModel(id, label)
}
