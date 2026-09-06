package com.wave.scanner.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.Threat
import com.wave.scanner.detect.Ranging
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.ui.components.Chip
import com.wave.scanner.ui.components.InfoCard
import com.wave.scanner.ui.components.KeyValue
import com.wave.scanner.ui.components.SectionHeader
import com.wave.scanner.ui.components.SignalBars
import com.wave.scanner.ui.components.ThreatDot
import com.wave.scanner.ui.components.color
import com.wave.scanner.ui.components.formatAgo
import com.wave.scanner.ui.components.formatDistance
import com.wave.scanner.ui.components.readable
import com.wave.scanner.ui.components.short
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.PanelHigh
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatMedium
import com.wave.scanner.ui.theme.Void
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything known about one device, plus the follow verdict.
 *
 * The order is deliberate: the verdict and the reason for the classification come first,
 * because the only question that matters here is "should I care about this thing". The
 * raw identifiers are underneath for when the answer is yes.
 */
@Composable
fun DeviceDetailScreen(vm: WaveViewModel, deviceId: String, onBack: () -> Unit) {
    LaunchedEffect(deviceId) { vm.openDevice(deviceId) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val device = detail.device

    if (device == null || device.id != deviceId) {
        Box(Modifier.fillMaxSize().background(Void), contentAlignment = Alignment.Center) {
            Text("Loading", color = TextTertiary, fontFamily = FontFamily.Monospace)
        }
        return
    }

    val stamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    var label by remember(deviceId) { mutableStateOf(device.userLabel.orEmpty()) }

    LazyColumn(
        Modifier.fillMaxSize().background(Void),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .border(1.dp, device.threat.color().copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThreatDot(device.threat, pulsing = device.threat == Threat.CRITICAL, size = 10)
                    Spacer(Modifier.padding(start = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.userLabel ?: device.displayName ?: "(no name)",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            device.address,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    SignalBars(device.lastRssi, device.threat.color())
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(device.band.short(), device.band.color())
                    Chip(device.deviceClass.readable().uppercase(), device.threat.color())
                    Chip(device.threat.name, device.threat.color())
                }
            }
        }

        device.classReason?.let { reason ->
            item {
                InfoCard("Why it is classified this way", reason, accent = device.threat.color())
            }
        }

        // ------------------------------------------------------------- follow
        detail.follow?.let { v ->
            item {
                InfoCard(
                    if (v.isFollowing) "This device appears to be following you"
                    else "Follow analysis",
                    v.explanation,
                    accent = if (v.isFollowing) v.threat.color() else TextSecondary
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("score " + v.score, if (v.isFollowing) v.threat.color() else TextTertiary)
                    Chip(v.distinctPlaces.toString() + " places", TextSecondary)
                    Chip(formatDistance(v.spreadMeters) + " apart", TextSecondary)
                    Chip(v.spanMinutes.toString() + " min", TextSecondary)
                }
            }
        }

        // -------------------------------------------------------------- label
        item { SectionHeader("Your label") }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Phosphor),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (label.isEmpty()) {
                            Text("e.g. my car, neighbour AP", color = TextTertiary, fontSize = 13.sp)
                        }
                        inner()
                    }
                )
                Text(
                    "save",
                    color = Phosphor,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { vm.setLabel(deviceId, label) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(
                    if (device.isWatched) "Watching" else "Watch",
                    if (device.isWatched) Phosphor else TextSecondary
                ) { vm.setWatched(deviceId, !device.isWatched) }
                ActionPill(
                    if (device.isMine) "Mine" else "This is mine",
                    if (device.isMine) Phosphor else TextSecondary
                ) { vm.setMine(deviceId, !device.isMine) }
                ActionPill(
                    if (device.isIgnored) "Ignored" else "Ignore",
                    if (device.isIgnored) ThreatMedium else TextSecondary
                ) { vm.setIgnored(deviceId, !device.isIgnored) }
            }
        }

        if (device.isMine) {
            item {
                Text(
                    "Marked as yours. It will not raise alerts, but it stays in every list " +
                        "and appears on the My Devices roll call so you can check it is " +
                        "still with you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        // -------------------------------------------------------------- facts
        item { SectionHeader("Identity") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                KeyValue("Address", device.address, mono = true)
                KeyValue("Broadcast name", device.displayName)
                KeyValue("Vendor", device.vendor ?: "unknown")
                KeyValue("Vendor source", device.vendorSource)
                detail.vendorDetail?.let { v ->
                    KeyValue("Registry", v.registry)
                    KeyValue("Registered block", v.oui, mono = true)
                    KeyValue("Country", v.country)
                    KeyValue("Vendor device type", v.deviceType)
                }
                KeyValue("Fingerprint", device.fingerprint, mono = true)
            }
        }

        item { SectionHeader("Radio") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                KeyValue("Band", device.band.name)
                device.frequencyKhz?.let {
                    KeyValue("Frequency", (it / 1000.0).toString() + " MHz", mono = true)
                }
                device.channel?.let { KeyValue("Channel", it.toString(), mono = true) }
                KeyValue("Best signal", device.bestRssi.toString() + " dBm", mono = true)
                KeyValue("Last signal", device.lastRssi.toString() + " dBm", mono = true)
                KeyValue("Estimated distance", Ranging.describe(detail.distanceMeters))
                if (detail.trend != Ranging.Trend.UNKNOWN) {
                    KeyValue("Over its history", detail.trend.label)
                }
                KeyValue("Capabilities", device.capabilities)
            }
        }

        // ----------------------------------------------------------- position
        detail.fix?.let { fix ->
            item { SectionHeader("Estimated position") }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Panel)
                        .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    KeyValue(
                        "Centre",
                        String.format("%.5f, %.5f", fix.lat, fix.lon),
                        mono = true
                    )
                    KeyValue("Within about", "${fix.radiusMeters.toInt()} m")
                    KeyValue("Vantage points", fix.samples.toString(), mono = true)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        fix.quality,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No phone can tell you which DIRECTION this is in - that needs a " +
                            "directional antenna. The estimate comes from hearing the same " +
                            "device from several places as you moved, so it only means " +
                            "anything if the device itself stayed put.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        }

        item { SectionHeader("History") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                KeyValue("First seen", stamp.format(Date(device.firstSeen)), mono = true)
                KeyValue(
                    "Last seen",
                    stamp.format(Date(device.lastSeen)) + "  (" + formatAgo(device.lastSeen) + ")",
                    mono = true
                )
                KeyValue("Times seen", device.timesSeen.toString(), mono = true)
                KeyValue("Positions recorded", detail.observations.count { it.lat != null }.toString(), mono = true)
            }
        }

        if (detail.observations.isNotEmpty()) {
            item { SectionHeader("Recent sightings", detail.observations.size.toString()) }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PanelHigh)
                        .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    detail.observations.sortedByDescending { it.ts }.take(40).forEach { o ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                stamp.format(Date(o.ts)),
                                color = TextTertiary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                            Text(
                                o.rssi.toString() + " dBm",
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                            Text(
                                if (o.lat != null && o.lon != null)
                                    fmt(o.lat) + ", " + fmt(o.lon)
                                else "no fix",
                                color = if (o.lat != null) TextSecondary else TextTertiary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "back",
                color = Phosphor,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.5f", v)

@Composable
private fun ActionPill(text: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(text, color = accent, fontSize = 12.sp)
    }
}
