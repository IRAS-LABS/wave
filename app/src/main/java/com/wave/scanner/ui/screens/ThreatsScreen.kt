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
import androidx.compose.foundation.lazy.items
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
import com.wave.scanner.data.db.ThreatEventEntity
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.ui.components.EmptyState
import com.wave.scanner.ui.components.SectionHeader
import com.wave.scanner.ui.components.ThreatDot
import com.wave.scanner.ui.components.color
import com.wave.scanner.ui.components.formatAgo
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.Void

/**
 * The alert log.
 *
 * Unacknowledged events sit at the top with a filled border; acknowledging is a deliberate
 * tap rather than an automatic side effect of opening the screen, because "I have seen and
 * judged this" is different information from "this scrolled past my eyes".
 */
@Composable
fun ThreatsScreen(vm: WaveViewModel, onDevice: (String) -> Unit) {
    val events by vm.threats.collectAsStateWithLifecycle()
    val unack = events.filter { !it.acknowledged }
    val seen = events.filter { it.acknowledged }

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Void), contentAlignment = Alignment.Center) {
            EmptyState(
                "No alerts",
                "Wave raises an alert when it identifies a tracker, an ALPR camera, " +
                    "law-enforcement equipment, or a device that has followed you across " +
                    "several separate places."
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Void),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (unack.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "UNACKNOWLEDGED",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Text(
                        "acknowledge all",
                        color = Phosphor,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { vm.acknowledgeAll() }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            items(unack, key = { it.eventId }) { e ->
                ThreatCard(e, onAck = { vm.acknowledge(e.eventId) }, onDevice = onDevice)
            }
        }

        if (seen.isNotEmpty()) {
            item { SectionHeader("Earlier", seen.size.toString()) }
            items(seen, key = { it.eventId }) { e ->
                ThreatCard(e, onAck = null, onDevice = onDevice)
            }
        }
    }
}

@Composable
private fun ThreatCard(
    event: ThreatEventEntity,
    onAck: (() -> Unit)?,
    onDevice: (String) -> Unit
) {
    val accent = event.severity.color()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(
                1.dp,
                if (onAck != null) accent.copy(alpha = 0.55f) else Hairline,
                RoundedCornerShape(10.dp)
            )
            .clickable { event.deviceId?.let(onDevice) }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThreatDot(event.severity, pulsing = onAck != null)
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                event.title,
                color = if (onAck != null) TextPrimary else TextSecondary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            Text(
                formatAgo(event.ts),
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(event.detail, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                event.type.lowercase().replace('_', ' '),
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            onAck?.let {
                Text(
                    "acknowledge",
                    color = accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = it)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
