package com.trexx.shakeytroll.ui.components

import android.provider.Settings
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.Bout
import com.trexx.shakeytroll.ble.ConnState
import com.trexx.shakeytroll.ble.Telemetry
import com.trexx.shakeytroll.commands.CommandUiState

/** Visual state of the hero circle, derived from connection + telemetry + optimistic command state. */
sealed interface HeroState {
  data object Disconnected : HeroState
  data object Connecting : HeroState
  data object AwaitingStatus : HeroState
  data class Stopped(val speed: Int) : HeroState
  /** Standby (run state 3) with a sensor mode armed: waiting for the baby to trip it. */
  data class Listening(val speed: Int) : HeroState
  /** [triggeredAtMs] (elapsedRealtime) is set when the sensor, not a tap, started this bout. */
  data class Running(val speed: Int, val triggeredAtMs: Long? = null) : HeroState
}

/**
 * Running/speed come from the optimistic command state (bh/fr) so the hero reacts the moment the
 * user taps; the ViewModel's 1500 ms suppression window keeps stale telemetry from yanking it back,
 * and a failed write is corrected by the next real status frame — same exposure as a plain Switch.
 *
 * Run state 3 is reported both when the device is plainly stopped and when a sensor mode is armed,
 * so it only reads as [HeroState.Listening] in sensor/baby-monitor mode.
 */
fun heroState(
  conn: ConnState,
  t: Telemetry?,
  bh: CommandUiState?,
  fr: CommandUiState?,
  sensorMode: Boolean,
  bout: Bout?,
): HeroState {
  if (conn == ConnState.CONNECTING || conn == ConnState.RECONNECTING) return HeroState.Connecting
  if (conn != ConnState.CONNECTED) return HeroState.Disconnected
  if (t == null) return HeroState.AwaitingStatus
  val running = bh?.boolValue ?: t.running
  val speed = fr?.intValue ?: t.speed
  return when {
    running -> HeroState.Running(speed, bout?.startMs)
    t.standby && sensorMode -> HeroState.Listening(speed)
    else -> HeroState.Stopped(speed)
  }
}

@Composable
fun HeroRockingControl(
  state: HeroState,
  modeLabel: String?,
  timerText: String?,
  nowMs: Long,
  stale: Boolean,
  onTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  // Honor the system "remove animations" setting; read once per composition is enough here.
  val reduceMotion = remember {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  }

  // Motion means "live": with no recent status frame the hero holds still and dims instead.
  val animate = !reduceMotion && !stale
  val sway: Float
  val glow: Float
  if (state is HeroState.Running && animate) {
    val transition = rememberInfiniteTransition(label = "rock")
    sway = transition.animateFloat(
      initialValue = -2.5f,
      targetValue = 2.5f,
      animationSpec = infiniteRepeatable(tween(4000, easing = EaseInOutSine), RepeatMode.Reverse),
      label = "sway",
    ).value
    glow = transition.animateFloat(
      initialValue = 0.12f,
      targetValue = 0.25f,
      animationSpec = infiniteRepeatable(tween(4000, easing = EaseInOutSine), RepeatMode.Reverse),
      label = "glow",
    ).value
  } else {
    sway = 0f
    glow = if (state is HeroState.Running) 0.18f else 0f
  }

  // Listening: a slow ripple leaving the circle, like a sonar ping — armed and waiting.
  val ripple: Float? = when {
    state !is HeroState.Listening || stale -> null
    reduceMotion -> 0.25f // a still ring, drawn at a fixed point of the ripple
    else -> rememberInfiniteTransition(label = "listen").animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(2800, easing = LinearOutSlowInEasing), RepeatMode.Restart),
      label = "ripple",
    ).value
  }

  val scheme = MaterialTheme.colorScheme
  // The circle stays plum in every state; running is signalled by the amber halo, border,
  // and label rather than a solid amber fill (which read as a muddy disk on the dark bg).
  val fill = when (state) {
    is HeroState.Listening -> scheme.secondaryContainer
    else -> scheme.surfaceContainerHigh
  }
  val glowColor = scheme.primary
  val rippleColor = scheme.secondary
  val baseDescription = when (state) {
    HeroState.Disconnected -> stringResource(R.string.hero_desc_disconnected)
    HeroState.Connecting -> stringResource(R.string.hero_desc_connecting)
    HeroState.AwaitingStatus -> stringResource(R.string.hero_desc_awaiting)
    is HeroState.Stopped -> stringResource(R.string.hero_desc_stopped, state.speed)
    is HeroState.Listening -> stringResource(R.string.hero_desc_listening, state.speed)
    is HeroState.Running ->
      if (state.triggeredAtMs != null) stringResource(R.string.hero_desc_triggered, state.speed)
      else stringResource(R.string.hero_desc_running, state.speed)
  }
  val description = if (stale) stringResource(R.string.hero_desc_stale, baseDescription) else baseDescription

  Box(
    modifier = modifier
      .size(266.dp)
      .alpha(if (stale) 0.55f else 1f)
      .drawBehind {
        if (ripple != null) {
          // From just outside the 230 dp circle out to the edge of this 266 dp box, fading as it goes.
          val inner = 117.dp.toPx()
          val outer = size.minDimension / 2f - 1.dp.toPx()
          drawCircle(
            color = rippleColor.copy(alpha = 0.5f * (1f - ripple)),
            radius = inner + (outer - inner) * ripple,
            style = Stroke(2.dp.toPx()),
          )
        }
        // The night-light: a soft amber halo behind the circle while rocking.
        if (glow > 0f) {
          val radius = size.minDimension * 0.5f
          drawCircle(
            brush = Brush.radialGradient(
              colors = listOf(glowColor.copy(alpha = glow), Color.Transparent),
              center = Offset(size.width / 2f, size.height / 2f),
              radius = radius,
            ),
            radius = radius,
          )
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    if (state is HeroState.Connecting || state is HeroState.AwaitingStatus) {
      CircularProgressIndicator(
        modifier = Modifier.size(244.dp),
        color = scheme.secondary,
        strokeWidth = 2.dp,
      )
    }
    Surface(
      onClick = onTap,
      enabled = state !is HeroState.Connecting && state !is HeroState.AwaitingStatus,
      shape = CircleShape,
      color = fill,
      border = when (state) {
        is HeroState.Running -> BorderStroke(1.5.dp, scheme.primary)
        is HeroState.Listening -> BorderStroke(1.5.dp, scheme.secondary)
        else -> BorderStroke(1.dp, scheme.outlineVariant)
      },
      modifier = Modifier
        .size(230.dp)
        .graphicsLayer {
          rotationZ = sway
          // Pivot above the circle's center: it swings like a hanging cradle, not a dial.
          transformOrigin = TransformOrigin(0.5f, 0.1f)
        }
        .semantics { stateDescription = description },
    ) {
      Box(contentAlignment = Alignment.Center) {
        when (state) {
          HeroState.Disconnected -> HeroLabel(moon = true, title = stringResource(R.string.hero_tap_to_connect), caption = stringResource(R.string.hero_find_caption))
          HeroState.Connecting -> HeroLabel(title = stringResource(R.string.hero_connecting), caption = null)
          HeroState.AwaitingStatus -> HeroLabel(title = stringResource(R.string.hero_awaiting), caption = null)
          // Stopped dims the number too, so idle reads differently from armed at a glance.
          is HeroState.Stopped -> HeroSpeed(
            state.speed, modeLabel, stringResource(R.string.hero_tap_to_start), scheme.onSurfaceVariant,
            speedColor = scheme.onSurfaceVariant,
          )
          is HeroState.Listening -> HeroSpeed(state.speed, modeLabel, stringResource(R.string.hero_listening), scheme.secondary)
          is HeroState.Running -> HeroSpeed(
            state.speed, modeLabel, stringResource(R.string.hero_rocking), scheme.primary,
            caption = state.triggeredAtMs
              ?.let { stringResource(R.string.hero_triggered_for, formatDuration(nowMs - it)) }
              ?: timerText,
          )
        }
      }
    }
  }
}

@Composable
private fun HeroSpeed(
  speed: Int,
  modeLabel: String?,
  label: String,
  labelColor: Color,
  caption: String? = null,
  speedColor: Color = Color.Unspecified,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    if (modeLabel != null) {
      Text(
        modeLabel,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(stringResource(R.string.percent, speed), style = MaterialTheme.typography.displayLarge, color = speedColor)
    Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor)
    if (caption != null) {
      Spacer(Modifier.height(4.dp))
      Text(
        caption,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun HeroLabel(title: String, caption: String?, moon: Boolean = false) {
  val scheme = MaterialTheme.colorScheme
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    if (moon) {
      val fill = scheme.surfaceContainerHigh
      val amber = scheme.primary
      Canvas(Modifier.size(52.dp)) {
        drawCircle(amber)
        // Overlay circle in the fill color carves the crescent.
        drawCircle(fill, radius = size.minDimension * 0.44f, center = Offset(size.width * 0.66f, size.height * 0.38f))
      }
      Spacer(Modifier.height(14.dp))
    }
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (caption != null) {
      Spacer(Modifier.height(2.dp))
      Text(
        caption,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
