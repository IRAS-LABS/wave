package com.wave.scanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.DeviceEntity
import com.wave.scanner.data.db.Threat
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.ui.components.Chip
import com.wave.scanner.ui.components.DeviceRow
import com.wave.scanner.ui.components.EmptyState
import com.wave.scanner.ui.components.color
import com.wave.scanner.ui.components.short
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatMedium
import com.wave.scanner.ui.theme.Void

/**
 * Everything seen, filtered.
 *
 * The filters are mutually exclusive by design rather than combinable: each maps to a
 * single indexed query, so the list stays fast at fifty thousand rows. Combining them
 * would mean building SQL at runtime and losing the index.
 */
@Composable
fun DevicesScreen(vm: WaveViewModel, onDevice: (String) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Void)) {
        SearchField(
            value = filter.query,
            onChange = vm::setQuery,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Chip(
                "ALL",
                Phosphor,
                selected = filter.band == null && filter.minThreat == null &&
                    !filter.watchedOnly && !filter.mineOnly
            ) {
                vm.setBand(null); vm.setMinThreat(null); vm.setQuery("")
                if (filter.watchedOnly) vm.toggleWatchedOnly()
                if (filter.mineOnly) vm.toggleMineOnly()
            }
            Chip("MINE", Phosphor, selected = filter.mineOnly) { vm.toggleMineOnly() }
            Chip("WATCHED", Phosphor, selected = filter.watchedOnly) { vm.toggleWatchedOnly() }
            Band.entries.forEach { b ->
                Chip(b.short(), b.color(), selected = filter.band == b) {
                    vm.setMinThreat(null)
                    vm.setBand(if (filter.band == b) null else b)
                }
            }
            listOf(Threat.MEDIUM, Threat.HIGH, Threat.CRITICAL).forEach { t ->
                Chip(t.name, t.color(), selected = filter.minThreat == t) {
                    vm.setBand(null)
                    vm.setMinThreat(if (filter.minThreat == t) null else t)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // The roll call. The whole reason for marking devices as yours is being able to
        // stop, glance, and know the set is complete - so when this filter is on, the
        // answer to "is everything with me" goes at the top rather than being something
        // you assemble by reading every row's timestamp.
        if (filter.mineOnly && devices.isNotEmpty()) {
            RollCall(devices)
            Spacer(Modifier.height(8.dp))
        }

        if (devices.isEmpty()) {
            EmptyState(
                "Nothing here",
                if (filter.mineOnly)
                    "You have not marked any devices as yours yet. Open a device you own " +
                        "and tap \"This is mine\" - your phone, watch, trackers, earbuds. " +
                        "They stop raising alerts but stay visible here as a roll call."
                else if (filter.query.isNotBlank() || filter.band != null || filter.minThreat != null)
                    "No device matches this filter yet."
                else
                    "Start a scan from the first tab and devices will appear as they are heard."
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.id }) { d -> DeviceRow(d) { onDevice(d.id) } }
            }
        }
    }
}

/**
 * "Is everything with me?", answered in one line.
 *
 * Present means heard inside [PRESENT_WINDOW_MS]. That window is a compromise: a phone or a
 * watch answers every few seconds, but an AirTag or a SmartTag in a bag can go a couple of
 * minutes between advertisements when it is stationary and not separated, so anything
 * tighter would report your keys missing while they sit in your pocket.
 */
@Composable
private fun RollCall(devices: List<DeviceEntity>) {
    val now = System.currentTimeMillis()
    val present = devices.count { now - it.lastSeen < PRESENT_WINDOW_MS }
    val all = present == devices.size
    val accent = if (all) Phosphor else ThreatMedium

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, Hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$present of ${devices.size} with you",
                color = accent,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (all) "ALL PRESENT" else "${devices.size - present} MISSING",
                color = accent,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            )
        }
        if (!all) {
            Spacer(Modifier.height(6.dp))
            Text(
                devices.filter { now - it.lastSeen >= PRESENT_WINDOW_MS }
                    .joinToString(", ") {
                        it.userLabel ?: it.displayName ?: it.address ?: "unknown"
                    },
                color = TextTertiary,
                fontSize = 11.sp
            )
        }
    }
}

/** How long a device may stay silent before the roll call stops counting it as present. */
private const val PRESENT_WINDOW_MS = 3 * 60 * 1000L

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Panel)
            .border(1.dp, Hairline, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("/", color = Phosphor, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.height(0.dp))
        Column(Modifier.padding(start = 9.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(Phosphor),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "name, MAC or vendor",
                            color = TextTertiary,
                            fontSize = 13.sp,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    inner()
                }
            )
        }
    }
}
