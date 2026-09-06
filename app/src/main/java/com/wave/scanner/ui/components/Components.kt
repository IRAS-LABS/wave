package com.wave.scanner.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.DeviceClass
import com.wave.scanner.data.db.DeviceEntity
import com.wave.scanner.data.db.Threat
import com.wave.scanner.ui.theme.BandBle
import com.wave.scanner.ui.theme.BandCell
import com.wave.scanner.ui.theme.BandSubGhz
import com.wave.scanner.ui.theme.BandWifi
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.PanelHigh
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatCritical
import com.wave.scanner.ui.theme.ThreatHigh
import com.wave.scanner.ui.theme.ThreatLow
import com.wave.scanner.ui.theme.ThreatMedium
import com.wave.scanner.ui.theme.ThreatNone
import com.wave.scanner.ui.theme.LocalPalette
import com.wave.scanner.ui.theme.WavePalette
import kotlin.math.roundToInt

/**
 * Two forms of each, deliberately.
 *
 * The no-argument form is the one every screen calls and reads the active theme itself.
 * The palette-taking form exists for Canvas draw scopes: a DrawScope lambda is not a
 * composable, so it cannot read a CompositionLocal, and the map draws several hundred
 * points inside one. The overload lets the map hoist the palette once outside the Canvas
 * instead of the colours being frozen at whatever the theme was when the file was written.
 */
fun Threat.color(p: WavePalette): Color = when (this) {
    Threat.CRITICAL -> p.threatCritical
    Threat.HIGH -> p.threatHigh
    Threat.MEDIUM -> p.threatMedium
    Threat.LOW -> p.threatLow
    Threat.NONE -> p.threatNone
}

@Composable
@ReadOnlyComposable
fun Threat.color(): Color = color(LocalPalette.current)

fun Band.color(p: WavePalette): Color = when (this) {
    Band.WIFI -> p.bandWifi
    Band.BLE, Band.BT_CLASSIC -> p.bandBle
    Band.CELL -> p.bandCell
    Band.SUBGHZ -> p.bandSubGhz
}

@Composable
@ReadOnlyComposable
fun Band.color(): Color = color(LocalPalette.current)

fun Band.short(): String = when (this) {
    Band.WIFI -> "WIFI"
    Band.BLE -> "BLE"
    Band.BT_CLASSIC -> "BT"
    Band.CELL -> "CELL"
    Band.SUBGHZ -> "RF"
}

fun DeviceClass.readable(): String = name.lowercase()
    .split('_')
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

// ---------------------------------------------------------------- primitives

@Composable
fun ThreatDot(threat: Threat, pulsing: Boolean = false, size: Int = 8) {
    val base = threat.color()
    // Only CRITICAL pulses. If everything moved, motion would stop meaning anything.
    val alpha = if (pulsing && threat == Threat.CRITICAL) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulseAlpha"
        ).value
    } else 1f

    Box(
        Modifier
            .size(size.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(base)
    )
}

@Composable
fun Chip(
    text: String,
    color: Color,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val bg by animateColorAsState(
        if (selected) color.copy(alpha = 0.18f) else Color.Transparent,
        label = "chipBg"
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, if (selected) color else Hairline, RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            color = if (selected) color else TextSecondary,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Five-bar signal meter. Thresholds are the usual dBm bands, not a linear scale. */
@Composable
fun SignalBars(rssi: Int, tint: Color = Phosphor) {
    val level = when {
        rssi >= -50 -> 5
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -95 -> 1
        else -> 0
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
        repeat(5) { i ->
            Box(
                Modifier
                    .width(2.5.dp)
                    .height((4 + i * 2.5).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < level) tint else Hairline)
            )
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Phosphor,
    caption: String? = null
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, Hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        Spacer(Modifier.height(4.dp))
        Text(value, color = accent, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        caption?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = TextTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionHeader(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TextTertiary) }
    }
}

@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = TextSecondary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            color = TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// -------------------------------------------------------------------- rows

@Composable
fun DeviceRow(device: DeviceEntity, onClick: () -> Unit) {
    val threatColor = device.threat.color()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(
                1.dp,
                if (device.threat >= Threat.HIGH) threatColor.copy(alpha = 0.45f) else Hairline,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThreatDot(device.threat, pulsing = true)
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.userLabel ?: device.displayName ?: device.address,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (device.isWatched) {
                    Spacer(Modifier.width(6.dp))
                    Text("WATCHED", style = MaterialTheme.typography.labelSmall, color = Phosphor)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                device.address,
                color = TextTertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1
            )
            device.vendor?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (device.deviceClass != DeviceClass.UNKNOWN) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Chip(device.band.short(), device.band.color())
                    Chip(device.deviceClass.readable(), threatColor)
                }
            }
        }

        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            SignalBars(device.lastRssi, device.band.color())
            Spacer(Modifier.height(4.dp))
            Text(
                device.lastRssi.toString() + " dBm",
                color = TextTertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "x" + device.timesSeen,
                color = TextTertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun KeyValue(key: String, value: String?, mono: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            key,
            color = TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(112.dp)
        )
        Text(
            value,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun InfoCard(
    title: String,
    body: String,
    accent: Color = Phosphor,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PanelHigh)
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(title, color = accent, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(5.dp))
        Text(body, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

fun formatDistance(meters: Double): String = when {
    meters < 0 -> "unknown"
    meters >= 1000 -> ((meters / 100).roundToInt() / 10.0).toString() + " km"
    else -> meters.roundToInt().toString() + " m"
}

fun formatAgo(timestamp: Long): String {
    val secs = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        secs < 60 -> secs.toString() + "s ago"
        secs < 3600 -> (secs / 60).toString() + "m ago"
        secs < 86400 -> (secs / 3600).toString() + "h ago"
        else -> (secs / 86400).toString() + "d ago"
    }
}
