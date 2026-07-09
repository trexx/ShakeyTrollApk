package com.example.bleat.commands

import androidx.lifecycle.ViewModel
import com.example.bleat.ble.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display + current-value state for one control. */
data class CommandUiState(
  val id: String,
  val label: String,
  val type: String, // toggle | slider | options | action
  val boolValue: Boolean = false,
  val intValue: Int = 0,
  val min: Int = 0,
  val max: Int = 100,
  val step: Int = 1,
  val unit: String = "",
  val options: List<String> = emptyList(),
  val selectedIndex: Int = 0,
  val confirm: Boolean = false,
  val lastSent: String? = null
)

/**
 * Holds the Sleepytroll control set and turns UI interactions into the exact AT commands.
 * Command building lives here (not in the UI) so encodings (hex/decimal/letter) are correct.
 * The caller wires [sender] to the BLE service.
 */
class CommandsViewModel : ViewModel() {

  /** Set by the Activity once the BLE service is bound. */
  var sender: (String) -> Unit = {}

  // After the user touches a control we ignore incoming telemetry for it briefly, so a ~1 Hz
  // status frame that still reflects the old value doesn't yank the control back.
  private val suppressUntil = mutableMapOf<String, Long>()

  private val models: List<CommandModel> = listOf(
    CommandModel.Toggle(
      id = "bh", label = "Rocking",
      default = false, onCommand = "AT+BH=01;", offCommand = "AT+BH=00;"
    ),
    CommandModel.Slider(
      id = "fr", label = "Speed",
      min = 0, max = 100, step = 1, default = 50, template = "AT+FR=%02x;", unit = "%"
    ),
    CommandModel.Options(
      id = "mode", label = "Mode",
      choices = listOf(
        CommandModel.Options.Choice("Continuous", "AT+MODE=01;"),
        CommandModel.Options.Choice("Sensor", "AT+MODE=02;"),
        CommandModel.Options.Choice("Baby monitor", "AT+MODE=03;")
      ),
      defaultIndex = 0
    ),
    CommandModel.Options(
      id = "sp", label = "Sleep program",
      choices = listOf(
        CommandModel.Options.Choice("Short", "AT+SP=S;"),
        CommandModel.Options.Choice("Medium", "AT+SP=M;"),
        CommandModel.Options.Choice("Long", "AT+SP=L;")
      ),
      defaultIndex = 0
    ),
    CommandModel.Slider(
      id = "sh", label = "Sound sensitivity",
      min = 0, max = 4, step = 1, default = 2, template = "AT+SH=%02x;"
    ),
    CommandModel.Slider(
      id = "au", label = "Movement sensitivity",
      min = 0, max = 4, step = 1, default = 2, template = "AT+AU=%02x;"
    ),
    CommandModel.Slider(
      id = "st", label = "Run timer",
      min = 0, max = 180, step = 5, default = 30, template = "AT+ST=%02x;", unit = " min"
    ),
    CommandModel.Action(
      id = "reset", label = "Reset device", command = "AT+RESET", confirm = true
    )
  )

  private val _uiState = MutableStateFlow(models.map { it.toUiState() })
  val uiState: StateFlow<List<CommandUiState>> = _uiState.asStateFlow()

  private fun CommandModel.toUiState(): CommandUiState = when (this) {
    is CommandModel.Toggle -> CommandUiState(id, label, "toggle", boolValue = default)
    is CommandModel.Slider -> CommandUiState(id, label, "slider", intValue = default, min = min, max = max, step = step, unit = unit)
    is CommandModel.Options -> CommandUiState(id, label, "options", options = choices.map { it.label }, selectedIndex = defaultIndex)
    is CommandModel.Action -> CommandUiState(id, label, "action", confirm = confirm)
  }

  private fun send(cmd: String, id: String, update: (CommandUiState) -> CommandUiState) {
    sender(cmd)
    suppressUntil[id] = System.currentTimeMillis() + SUPPRESS_MS
    _uiState.value = _uiState.value.map { if (it.id == id) update(it).copy(lastSent = cmd) else it }
  }

  /**
   * Move the interactive controls to match what the device actually reports (channel 2), so the
   * UI reflects reality on connect instead of showing defaults. Only the fields channel 2 carries
   * are synced; mode/sleep-program (channel 3) and the run-timer setpoint (channel 2 reports the
   * live countdown, not the setpoint) are left alone. Controls the user just touched are skipped.
   */
  fun syncFromTelemetry(t: Telemetry) {
    val now = System.currentTimeMillis()
    _uiState.value = _uiState.value.map { s ->
      if ((suppressUntil[s.id] ?: 0L) > now) return@map s
      when (s.id) {
        "bh" -> if (s.boolValue != t.running) s.copy(boolValue = t.running) else s
        "fr" -> s.copy(intValue = t.speed.coerceIn(s.min, s.max))
        "sh" -> s.copy(intValue = t.soundSensitivity.coerceIn(s.min, s.max))
        "au" -> s.copy(intValue = t.movementSensitivity.coerceIn(s.min, s.max))
        else -> s
      }
    }
  }

  fun onToggle(id: String, value: Boolean) {
    val m = models.filterIsInstance<CommandModel.Toggle>().firstOrNull { it.id == id } ?: return
    send(if (value) m.onCommand else m.offCommand, id) { it.copy(boolValue = value) }
  }

  fun onSlider(id: String, value: Int) {
    val m = models.filterIsInstance<CommandModel.Slider>().firstOrNull { it.id == id } ?: return
    val clamped = value.coerceIn(m.min, m.max)
    send(m.template.format(clamped), id) { it.copy(intValue = clamped) }
  }

  fun onOption(id: String, index: Int) {
    val m = models.filterIsInstance<CommandModel.Options>().firstOrNull { it.id == id } ?: return
    val choice = m.choices.getOrNull(index) ?: return
    send(choice.command, id) { it.copy(selectedIndex = index) }
  }

  fun onAction(id: String) {
    val m = models.filterIsInstance<CommandModel.Action>().firstOrNull { it.id == id } ?: return
    send(m.command, id) { it }
  }

  /** Sync the Mode dropdown from channel-3 (babyRockerType 1/2/3 → option index 0/1/2). */
  fun syncMode(mode: Int) {
    val index = mode - 1
    if (index < 0) return
    val now = System.currentTimeMillis()
    _uiState.value = _uiState.value.map { s ->
      if (s.id == "mode" && (suppressUntil[s.id] ?: 0L) <= now && index < s.options.size)
        s.copy(selectedIndex = index) else s
    }
  }

  companion object {
    private const val SUPPRESS_MS = 1500L
  }
}
