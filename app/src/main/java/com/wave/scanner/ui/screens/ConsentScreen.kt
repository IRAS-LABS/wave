package com.wave.scanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wave.scanner.ui.components.SectionHeader
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatHigh
import com.wave.scanner.ui.theme.ThreatMedium
import com.wave.scanner.ui.theme.Void

/**
 * The scope gate. Shown before anything else on first run, and again whenever the terms
 * are versioned forward.
 *
 * This is not a legal fig leaf and it is not a EULA. It is the shortest honest statement of
 * what the tool is for and what it refuses to do, put in front of the user once, before
 * they have any detections to be excited about. A scope document nobody reads is worth
 * nothing; the acknowledgement below is deliberately a single explicit tap on a claim about
 * authority, not a pre-ticked box.
 *
 * The full text lives in AUTHORIZATION.md. This screen is its summary, and the two must be
 * kept in step - if this changes materially, bump ConsentStore.VERSION.
 */
@Composable
fun ConsentScreen(onAccept: () -> Unit) {
    var authorised by remember { mutableStateOf(false) }
    var understood by remember { mutableStateOf(false) }
    val ready = authorised && understood

    LazyColumn(
        Modifier.fillMaxSize().background(Void),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 18.dp,
            vertical = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("WAVE", color = TextPrimary, fontSize = 26.sp, letterSpacing = 6.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Before you start",
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
        }

        item {
            Text(
                "Wave is a passive receiver. It listens to what the radios around you are " +
                    "already broadcasting into open air. It never transmits, connects, " +
                    "pairs, jams or decrypts anything.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        item { SectionHeader("What it will not do") }
        item {
            Column(panelMod()) {
                Wont("Follow people or vehicles", "There is no movement history for anything you do not own.")
                Wont("Point at anything", "No bearing, no direction finding, no hotter/colder targeting.")
                Wont("Route you around police", "Mapped cameras are public OpenStreetMap data about fixed infrastructure.")
                Wont("Profile strangers over time", "Identity keys exist to stop one tracker looking like forty, not to recognise a stranger tomorrow.")
            }
        }

        item { SectionHeader("Where your data goes") }
        item {
            Column(panelMod()) {
                Does("Nowhere.", "Detections stay in a database on this phone. No account, no telemetry, no crash reporting.")
                Does("Identification is offline.", "Vendor and protocol lookups run against databases inside the app.")
                Does(
                    "Two network calls exist, both yours to trigger.",
                    "OpenStreetMap map tiles, and the Overpass camera-map import. Neither sends anything you have detected."
                )
            }
        }

        item { SectionHeader("Your responsibility") }
        item {
            Column(panelMod(accent = ThreatMedium)) {
                Text(
                    "Radio law varies. Receiving unencrypted transmissions is broadly lawful " +
                        "in many places, but recording and retaining what you receive can be " +
                        "regulated separately, and some jurisdictions treat a MAC address as " +
                        "personal data. Complying with the law where you are is on you.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Exports contain the places you were when you heard things. A wardrive " +
                        "log is a log of your own movements. Think before sharing one.",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }

        item { Spacer(Modifier.height(6.dp)) }

        item {
            Check(
                checked = authorised,
                label = "I have authority over what I am scanning",
                detail = "My own devices and surroundings, or something I have written " +
                    "permission to test.",
                onToggle = { authorised = !authorised }
            )
        }
        item {
            Check(
                checked = understood,
                label = "I will not use this to track people",
                detail = "Passers-by did not consent to anything. Presence and " +
                    "identification is the scope; following is not.",
                onToggle = { understood = !understood }
            )
        }

        item { Spacer(Modifier.height(6.dp)) }

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (ready) Phosphor.copy(alpha = 0.14f) else Panel
                    )
                    .border(
                        1.dp,
                        if (ready) Phosphor else Hairline,
                        RoundedCornerShape(10.dp)
                    )
                    .then(if (ready) Modifier.clickable(onClick = onAccept) else Modifier)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (ready) "CONTINUE" else "TICK BOTH TO CONTINUE",
                    color = if (ready) Phosphor else TextTertiary,
                    letterSpacing = 2.sp,
                    fontSize = 13.sp
                )
            }
        }

        item {
            Text(
                "The full text is in AUTHORIZATION.md, and this screen can be reopened " +
                    "from the Data tab.",
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

/** Composable because the palette colours it reads are CompositionLocal-backed. */
@Composable
private fun panelMod(accent: Color? = null): Modifier = Modifier
    .fillMaxWidth()
    .clip(RoundedCornerShape(10.dp))
    .background(Panel)
    .border(1.dp, accent?.copy(alpha = 0.32f) ?: Hairline, RoundedCornerShape(10.dp))
    .padding(12.dp)

@Composable
private fun Wont(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text("x", color = ThreatHigh, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = TextPrimary, fontSize = 13.sp)
            Text(detail, color = TextTertiary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Does(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text("+", color = Phosphor, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = TextPrimary, fontSize = 13.sp)
            Text(detail, color = TextTertiary, fontSize = 11.sp)
        }
    }
}

/**
 * Deliberately not a Material Checkbox: nothing here may start pre-ticked, and a bare box
 * that has to be tapped makes that obvious at a glance.
 */
@Composable
private fun Check(
    checked: Boolean,
    label: String,
    detail: String,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(
                1.dp,
                if (checked) Phosphor.copy(alpha = 0.55f) else Hairline,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Phosphor else Color.Transparent)
                .border(1.dp, if (checked) Phosphor else TextTertiary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", color = Void, fontSize = 11.sp)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(detail, color = TextSecondary, fontSize = 11.sp)
        }
    }
}
