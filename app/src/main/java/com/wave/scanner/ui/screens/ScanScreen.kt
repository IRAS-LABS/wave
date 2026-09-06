package com.wave.scanner.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wave.scanner.data.db.DeviceClass
import com.wave.scanner.data.db.Threat
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.ui.components.Chip
import com.wave.scanner.ui.components.DeviceRow
import com.wave.scanner.ui.components.EmptyState
import com.wave.scanner.ui.components.InfoCard
import com.wave.scanner.ui.components.SectionHeader
import com.wave.scanner.ui.components.StatTile
import com.wave.scanner.ui.components.color
import com.wave.scanner.ui.components.readable
import com.wave.scanner.ui.theme.BandBle
import com.wave.scanner.ui.theme.BandCell
import com.wave.scanner.ui.theme.BandSubGhz
import com.wave.scanner.ui.theme.BandWifi
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatMedium
import com.wave.scanner.ui.theme.Void

/**
 * The screen the app opens on: one control, live counters, and anything alarming.
 *
 * Deliberately not a device list. When you glance at this while driving, the question is
 * "is scanning running and has anything found me", not "what is the 400th access point".
 */
@Composable
fun ScanScreen(
    vm: WaveViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDevice: (String) -> Unit
) {
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val classes by vm.classCounts.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()

    val alarming = devices.filter { it.threat >= Threat.HIGH }.take(6)

    LazyColumn(
        Modifier.fillMaxSize().background(Void),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScanControl(running = scan.running, onStart = onStart, onStop = onStop) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Devices", totals.devices.toString(),
                    Modifier.weight(1f),
                    caption = totals.observations.toString() + " sightings"
                )
                StatTile(
                    "Alerts", totals.unackThreats.toString(),
                    Modifier.weight(1f),
                    accent = if (totals.unackThreats > 0) ThreatMedium else Phosphor,
                    caption = if (totals.unackThreats > 0) "unacknowledged" else "all clear"
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                RadioTile("Wi-Fi", scan.wifiSeen, BandWifi, Modifier.weight(1f))
                RadioTile("BLE", scan.bleSeen, BandBle, Modifier.weight(1f))
                RadioTile("Cell", scan.cellSeen, BandCell, Modifier.weight(1f))
                RadioTile("Sub-GHz", scan.subGhzSeen, BandSubGhz, Modifier.weight(1f))
            }
        }

        // Conditions that silently degrade results deserve to be on the main screen,
        // not buried where the user only finds them after a wasted drive.
        if (scan.running && !scan.hasFix) {
            item {
                InfoCard(
                    "No usable GPS fix",
                    "Sightings are still being recorded but cannot be mapped, and " +
                        "follow-detection needs positions to work at all. Give it a clear " +
                        "view of the sky for a minute.",
                    accent = ThreatMedium
                )
            }
        }
        if (scan.wifiThrottled) {
            item {
                InfoCard(
                    "Wi-Fi scans are being throttled",
                    "Android limits apps to four scans every two minutes. Turn off " +
                        "\"Wi-Fi scan throttling\" in Developer Options for a full-rate scan.",
                    accent = ThreatMedium
                )
            }
        }
        scan.bleError?.let { err ->
            item { InfoCard("Bluetooth scan problem", err, accent = ThreatMedium) }
        }

        if (classes.isNotEmpty()) {
            item { SectionHeader("What is out there") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    classes.entries
                        .filter { it.key != DeviceClass.UNKNOWN }
                        .sortedByDescending { it.value }
                        .take(7)
                        .forEach { (cls, count) ->
                            ClassBar(cls, count, classes.values.maxOrNull() ?: 1)
                        }
                }
            }
        }

        if (alarming.isNotEmpty()) {
            item { SectionHeader("Needs attention", alarming.size.toString()) }
            items(alarming, key = { it.id }) { d ->
                DeviceRow(d) { onDevice(d.id) }
            }
        } else if (totals.devices > 0) {
            item {
                EmptyState(
                    "Nothing alarming",
                    "No trackers, ALPR cameras or law-enforcement equipment identified so far."
                )
            }
        }
    }
}

@Composable
private fun ScanControl(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val pulse = if (running) {
        rememberInfiniteTransition(label = "scanPulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "scanScale"
        ).value
    } else 1f

    Box(
        Modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(
                1.dp,
                if (running) Phosphor.copy(alpha = 0.5f) else Hairline,
                RoundedCornerShape(14.dp)
            )
            .clickable { if (running) onStop() else onStart() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(56.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(if (running) Phosphor.copy(alpha = 0.16f) else Color.Transparent)
                    .border(2.dp, if (running) Phosphor else TextTertiary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(if (running) 16.dp else 20.dp)
                        .clip(if (running) RoundedCornerShape(3.dp) else CircleShape)
                        .background(if (running) Phosphor else TextTertiary)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (running) "SCANNING" else "START SCAN",
                color = if (running) Phosphor else TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (running) "Tap to stop" else "Wi-Fi, Bluetooth, cell and GPS",
                color = TextTertiary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RadioTile(label: String, count: Int, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(1.dp, if (count > 0) accent.copy(alpha = 0.35f) else Hairline, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            count.toString(),
            color = if (count > 0) accent else TextTertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextTertiary, fontSize = 9.sp)
    }
}

@Composable
private fun ClassBar(cls: DeviceClass, count: Int, max: Int) {
    val fraction = (count.toFloat() / max).coerceIn(0.02f, 1f)
    val accent = when (cls) {
        DeviceClass.TRACKER, DeviceClass.ALPR_CAMERA, DeviceClass.BODY_CAMERA -> Threat.HIGH.color()
        DeviceClass.POLICE_VEHICLE, DeviceClass.SURVEILLANCE -> Threat.MEDIUM.color()
        else -> BandWifi
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(cls.readable(), color = TextSecondary, fontSize = 12.sp)
            Text(
                count.toString(),
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Hairline)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}
