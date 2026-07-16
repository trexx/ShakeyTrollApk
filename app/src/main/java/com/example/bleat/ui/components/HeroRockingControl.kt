package com.example.bleat.ui.components

import android.provider.Settings
import androidx.compose.animation.core.EaseInOutSine
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.bleat.ble.ConnState
import com.example.bleat.ble.Telemetry
import com.example.bleat.commands.CommandUiState

/** Visual state of the hero circle, derived from connection + telemetry + optimistic command state. */
sealed interface HeroState {
  data object Disconnected : HeroState
  data object Connecting : HeroState
  data object AwaitingStatus : HeroState
  data class Stopped(val speed: Int) : HeroState
  data class Standby(val speed: Int) : HeroState
  data class Running(val speed: Int) : HeroState
}

/**
 * Running/speed come from the optimistic command state (bh/fr) so the hero reacts the moment the
 * user taps; the ViewModel's 1500 ms suppression window keeps stale telemetry from yanking it back,
 * and a failed write is corrected by the next real status frame — same exposure as a plain Switch.
 */
fun heroState(conn: ConnState, t: Telemetry?, bh: CommandUiState?, fr: CommandUiState?): HeroState {
  if (conn == ConnState.CONNECTING || conn == ConnState.RECONNECTING) return HeroState.Connecting
  if (conn != ConnState.CONNECTED) return HeroState.Disconnected
  if (t == null) return HeroState.AwaitingStatus
  val running = bh?.boolValue ?: t.running
  val speed = fr?.intValue ?: t.speed
  return when {
    running -> HeroState.Running(speed)
    t.standby -> HeroState.Standby(speed)
    else -> HeroState.Stopped(speed)
  }
}

@Composable
fun HeroRockingControl(
  state: HeroState,
  timerText: String?,
  onTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  // Honor the system "remove animations" setting; read once per composition is enough here.
  val reduceMotion = remember {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  }

  val sway: Float
  val glow: Float
  if (state is HeroState.Running && !reduceMotion) {
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

  val scheme = MaterialTheme.colorScheme
  // The circle stays plum in every state; running is signalled by the amber halo, border,
  // and label rather than a solid amber fill (which read as a muddy disk on the dark bg).
  val fill = when (state) {
    is HeroState.Standby -> scheme.secondaryContainer
    else -> scheme.surfaceContainerHigh
  }
  val glowColor = scheme.primary
  val description = when (state) {
    HeroState.Disconnected -> "Not connected"
    HeroState.Connecting -> "Connecting"
    HeroState.AwaitingStatus -> "Waiting for status"
    is HeroState.Stopped -> "Stopped, speed ${state.speed} percent"
    is HeroState.Standby -> "Listening for the baby, speed ${state.speed} percent"
    is HeroState.Running -> "Rocking at ${state.speed} percent"
  }

  Box(
    modifier = modifier
      .size(266.dp)
      .drawBehind {
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
      border = if (state is HeroState.Running) BorderStroke(1.5.dp, scheme.primary)
               else BorderStroke(1.dp, scheme.outlineVariant),
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
          HeroState.Disconnected -> HeroLabel(moon = true, title = "Tap to connect", caption = "Find your Sleepytroll")
          HeroState.Connecting -> HeroLabel(title = "Connecting…", caption = null)
          HeroState.AwaitingStatus -> HeroLabel(title = "Waiting for status…", caption = null)
          is HeroState.Stopped -> HeroSpeed(state.speed, "Tap to start", scheme.onSurfaceVariant)
          is HeroState.Standby -> HeroSpeed(state.speed, "Listening…", scheme.secondary)
          is HeroState.Running -> HeroSpeed(state.speed, "Rocking", scheme.primary, timerText)
        }
      }
    }
  }
}

@Composable
private fun HeroSpeed(speed: Int, label: String, labelColor: Color, timerText: String? = null) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("$speed%", style = MaterialTheme.typography.displayLarge)
    Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor)
    if (timerText != null) {
      Spacer(Modifier.height(4.dp))
      Text(
        timerText,
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
