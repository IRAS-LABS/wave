package com.wave.scanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wave.scanner.data.db.Threat
import com.wave.scanner.data.export.Exporters
import com.wave.scanner.ui.AlertPrefs
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.ui.components.Chip
import com.wave.scanner.ui.components.color
import com.wave.scanner.ui.components.InfoCard
import com.wave.scanner.ui.components.KeyValue
import com.wave.scanner.ui.components.SectionHeader
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.ThemeStore
import com.wave.scanner.ui.theme.WavePalette
import com.wave.scanner.ui.theme.WaveThemes
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatMedium
import com.wave.scanner.ui.theme.Void
import androidx.compose.ui.platform.LocalContext

/**
 * Export, provenance and the honest caveats.
 */
@Composable
fun SettingsScreen(vm: WaveViewModel, onReviewTerms: () -> Unit = {}) {
    val active by ThemeStore.current.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val alerts by AlertPrefs.current.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().background(Void),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        status?.let {
            item { InfoCard("Last action", it, accent = Phosphor) }
        }

        // ------------------------------------------------------------ alerts
        item { SectionHeader("Alerts", trailing = alerts.minSeverity.name.lowercase() + " and up") }
        item {
            Text(
                "Which detections are allowed to interrupt you. Everything Wave finds is " +
                    "recorded and listed on the Alerts tab either way - this only decides " +
                    "what buzzes the phone in your pocket.",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text("Minimum severity", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // NONE is not offered. Every sighting carries a severity and most are
                    // NONE, so allowing it would post a notification per device and the
                    // alert channel would become unusable within a minute of a scan.
                    listOf(Threat.LOW, Threat.MEDIUM, Threat.HIGH, Threat.CRITICAL).forEach { t ->
                        Chip(
                            text = t.name.lowercase(),
                            color = t.color(),
                            selected = alerts.minSeverity == t,
                            onClick = { AlertPrefs.setMinSeverity(context, t) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (alerts.minSeverity) {
                        Threat.LOW -> "Everything, including weak single-signal guesses. Expect noise."
                        Threat.MEDIUM -> "Anything with corroborating evidence behind it."
                        Threat.HIGH -> "Confident matches and confirmed follows. The default."
                        else -> "Only the strongest results. You will miss real MEDIUM and HIGH findings."
                    },
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
        items(AlertPrefs.Category.entries.size) { i ->
            val cat = AlertPrefs.Category.entries[i]
            val on = cat in alerts.enabled
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(
                        1.dp,
                        if (on) Phosphor.copy(alpha = 0.4f) else Hairline,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { AlertPrefs.setEnabled(context, cat, !on) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cat.label, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(cat.detail, color = TextSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (on) "ON" else "OFF",
                    color = if (on) Phosphor else TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
        item {
            Text(
                if (alerts.enabled.isEmpty())
                    "Every category is off. Nothing will reach you while the phone is in " +
                        "your pocket - the Alerts tab is the only place findings will appear."
                else
                    "Suppressed alerts are still saved. Nothing is discarded because of a " +
                        "setting here.",
                color = if (alerts.enabled.isEmpty()) ThreatMedium else TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item { SectionHeader("Appearance", trailing = active.name) }
        item {
            Text(
                "Twenty themes. Threat colours shift with the theme so severity stays " +
                    "readable on a white background as well as a black one - but the " +
                    "order never changes: critical always reads hotter than high.",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        item { SectionHeader("Dark") }
        items(WaveThemes.DARK.chunked(2).size) { i ->
            ThemeRow(WaveThemes.DARK.chunked(2)[i], active) { ThemeStore.select(context, it) }
        }
        item { SectionHeader("Light") }
        items(WaveThemes.LIGHT.chunked(2).size) { i ->
            ThemeRow(WaveThemes.LIGHT.chunked(2)[i], active) { ThemeStore.select(context, it) }
        }

        item { SectionHeader("Export") }
        item {
            Text(
                "Exports cover whatever the Devices tab is currently filtered to - " +
                    devices.size.toString() + " device" + (if (devices.size == 1) "" else "s") +
                    " right now.",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        items(WaveViewModel.ExportFormat.entries.size) { i ->
            val f = WaveViewModel.ExportFormat.entries[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .clickable { vm.export(f) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.label, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(f.detail, color = TextSecondary, fontSize = 11.sp)
                }
                Text("export", color = Phosphor, fontSize = 12.sp)
            }
        }
        item {
            Text(
                "Files are written to " + Exporters.outputDir(context).absolutePath,
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item { SectionHeader("Collected so far") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                KeyValue("Devices", totals.devices.toString(), mono = true)
                KeyValue("Sightings", totals.observations.toString(), mono = true)
                KeyValue("Alerts", totals.unackThreats.toString() + " unacknowledged", mono = true)
                KeyValue("Mapped ALPR cameras", totals.alprCameras.toString(), mono = true)
            }
        }

        item { SectionHeader("Where the data comes from") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                KeyValue("MAC vendors", "IEEE MA-L / MA-M / MA-S registry, " + vm.ouiCount + " blocks")
                KeyValue("Bluetooth vendors", "Bluetooth SIG assigned company IDs, " + vm.btSigCount + " entries")
                KeyValue("Sub-GHz protocols", "Ringmast4r RF-Protocol-Database, " + vm.rfProtocolCount + " devices")
                KeyValue("ALPR camera map", "OpenStreetMap surveillance nodes, fetched via Overpass on demand")
            }
        }

        item { SectionHeader("Scope") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                    .clickable(onClick = onReviewTerms)
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Read the terms again",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "What Wave is for, what it deliberately will not do, and where " +
                                "your data goes. The full text is in AUTHORIZATION.md.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Text("open", color = Phosphor, fontSize = 12.sp)
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}


/**
 * Two theme swatches side by side.
 *
 * Each swatch paints itself in ITS OWN palette rather than the active one - that is the
 * entire point, and it is why these read the WavePalette fields directly instead of the
 * LocalPalette-backed colour names the rest of the file uses. A picker that renders every
 * option in the current theme tells you nothing.
 */
@Composable
private fun ThemeRow(
    row: List<WavePalette>,
    active: WavePalette,
    onPick: (WavePalette) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        row.forEach { p ->
            ThemeSwatch(p, selected = p.id == active.id, onPick = { onPick(p) },
                modifier = Modifier.weight(1f))
        }
        // Keeps a lone trailing swatch at half width instead of stretching it.
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ThemeSwatch(
    p: WavePalette,
    selected: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(p.background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) p.accent else p.hairline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onPick)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.name,
                color = p.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (selected) Text("✓", color = p.accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        // A stand-in for a row of the real UI: accent bar, then the threat ladder.
        Box(
            Modifier
                .height(4.dp)
                .width(38.dp)
                .clip(CircleShape)
                .background(p.accent)
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(p.threatCritical, p.threatHigh, p.threatMedium, p.threatLow).forEach { c ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("device name", color = p.textSecondary, fontSize = 10.sp)
    }
}
