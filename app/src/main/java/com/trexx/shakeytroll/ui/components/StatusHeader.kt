package com.trexx.shakeytroll.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trexx.shakeytroll.R
import com.trexx.shakeytroll.ble.ConnState
import com.trexx.shakeytroll.ble.DeviceInfo
import com.trexx.shakeytroll.ble.Telemetry

@Composable
fun StatusHeader(
  connState: ConnState,
  telemetry: Telemetry?,
  deviceInfo: DeviceInfo?,
  motorWarning: String?,
  onConnectionClick: () -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.headlineSmall)
        deviceInfo?.serial?.let { serial ->
          val version = deviceInfo.version?.let { stringResource(R.string.status_version_suffix, it) } ?: ""
          Text(
            stringResource(R.string.status_serial, serial, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      telemetry?.let {
        BatteryChip(it.batteryPct, it.lowBattery)
        Spacer(Modifier.width(10.dp))
      }
      ConnectionPill(connState, onClick = onConnectionClick)
    }
    motorWarning?.let {
      Spacer(Modifier.height(12.dp))
      WarningBanner(it)
    }
    if (telemetry?.lowBattery == true) {
      Spacer(Modifier.height(12.dp))
      WarningBanner(stringResource(R.string.warning_low_battery, telemetry.batteryPct))
    }
  }
}

@Composable
private fun ConnectionPill(connState: ConnState, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val (label, container, content) = when (connState) {
    ConnState.CONNECTED -> Triple(stringResource(R.string.conn_connected), scheme.secondaryContainer, scheme.onSecondaryContainer)
    ConnState.CONNECTING -> Triple(stringResource(R.string.conn_connecting), scheme.tertiaryContainer, scheme.onTertiaryContainer)
    ConnState.RECONNECTING -> Triple(stringResource(R.string.conn_reconnecting), scheme.tertiaryContainer, scheme.onTertiaryContainer)
    ConnState.DISCONNECTED -> Triple(stringResource(R.string.conn_connect), Color.Transparent, scheme.primary)
  }
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = container,
    contentColor = content,
    border = if (connState == ConnState.DISCONNECTED) BorderStroke(1.dp, scheme.outline) else null,
  ) {
    Text(
      label,
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      style = MaterialTheme.typography.labelLarge,
    )
  }
}

@Composable
private fun BatteryChip(pct: Int, low: Boolean) {
  val color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
  Row(verticalAlignment = Alignment.CenterVertically) {
    Canvas(Modifier.size(width = 20.dp, height = 11.dp)) {
      val stroke = 1.4.dp.toPx()
      val bodyWidth = size.width * 0.86f
      val inset = stroke * 1.6f
      drawRoundRect(
        color = color,
        size = Size(bodyWidth, size.height),
        cornerRadius = CornerRadius(2.5.dp.toPx()),
        style = Stroke(stroke),
      )
      drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size((bodyWidth - 2 * inset) * (pct.coerceIn(0, 100) / 100f), size.height - 2 * inset),
        cornerRadius = CornerRadius(1.5.dp.toPx()),
      )
      drawRoundRect(
        color = color,
        topLeft = Offset(bodyWidth + stroke, size.height * 0.3f),
        size = Size(size.width - bodyWidth - stroke, size.height * 0.4f),
        cornerRadius = CornerRadius(1.dp.toPx()),
      )
    }
    Spacer(Modifier.width(5.dp))
    Text(stringResource(R.string.percent, pct), style = MaterialTheme.typography.labelMedium, color = color)
  }
}

@Composable
fun WarningBanner(message: String) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      message,
      Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}
