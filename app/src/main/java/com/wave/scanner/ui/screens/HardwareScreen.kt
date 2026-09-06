package com.wave.scanner.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wave.scanner.detect.ImsiCatcherDetector
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.scan.ScanService
import com.wave.scanner.ui.components.InfoCard
import com.wave.scanner.ui.components.KeyValue
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
 * What the phone can do alone, what needs hardware, and what nothing here can do.
 *
 * This screen exists because the failure mode of a tool like this is a user who believes
 * a silent absence of alerts means an absence of surveillance. Every limit is stated.
 */
@Composable
fun HardwareScreen(vm: WaveViewModel) {
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val problems = diagnose(scan)

    LazyColumn(
        Modifier.fillMaxSize().background(Void),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ------------------------------------------------------------- radio
        // When a dongle is attached this page is a live instrument. The setup guidance
        // only appears when there is nothing plugged in, because that is the only time
        // it is the answer to anything.
        if (scan.sdrConnected) {
            item { SectionHeader("Radio") }
            item {
                Column(panel()) {
                    KeyValue("Tuner", scan.sdrTuner ?: "connected", mono = true)
                    KeyValue("State", scan.sdrStatus ?: "receiving")
                    KeyValue("Bursts decoded", scan.subGhzSeen.toString(), mono = true)
                    KeyValue("Link", "rtl_tcp on 127.0.0.1:1234", mono = true)
                }
            }
            item {
                Column(panel()) {
                    Text(
                        "What the radio is doing",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Wave cycles between 315 MHz and 433.92 MHz, slices each burst into " +
                            "pulse timings, and runs the native decoders first. Anything they " +
                            "do not claim is matched against the protocol catalogue by bit " +
                            "length and modulation and listed as a candidate family.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // ------------------------------------------------------- diagnostics
        // Anything actually wrong goes at the very top with the button that fixes it.
        // A radio that is silently off is the failure mode that makes this whole app lie,
        // so it gets the first thing on the screen and a one-tap route to the setting.
        if (problems.isNotEmpty()) {
            item { SectionHeader("Needs attention", trailing = problems.size.toString()) }
            items(problems.size) { i ->
                val p = problems[i]
                Fix(p) { runCatching { context.startActivity(p.intent(context)) } }
            }
        }

        item { SectionHeader("Status") }
        item {
            Column(panel(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Status("Wi-Fi scanning", true, if (scan.wifiThrottled) "throttled by Android" else "full rate")
                Status("Bluetooth LE", scan.bleError == null, scan.bleError ?: "unfiltered, aggressive match")
                Status(
                    "Bluetooth classic",
                    scan.btClassicError == null,
                    scan.btClassicError
                        ?: (scan.btClassicCycles.toString() + " inquiry cycles, " +
                            scan.btClassicSeen + " seen")
                )
                Status("Cell survey", true, "neighbour cells via public API only")
                Status("GPS fix", scan.hasFix, if (scan.hasFix) "positions are being recorded" else "no usable fix")
                Status(
                    "Sub-GHz radio (SDR)",
                    scan.sdrConnected,
                    if (scan.sdrConnected) "receiving on 315 / 433.92 MHz"
                    else "no rtl_tcp server on 127.0.0.1:1234"
                )
            }
        }

        // Classic Bluetooth is the lane people do not know exists, so it is explained
        // rather than left as a row that is either green or not.
        item {
            InfoCard(
                "Why there are two Bluetooth rows",
                "LE and classic are different radios sharing one antenna, and an LE scan " +
                    "hears nothing on classic. Car head units, hands-free kits, older " +
                    "speakers, barcode scanners and body-worn recorders are classic-only. " +
                    "Classic runs as a repeating inquiry rather than continuously, so it " +
                    "finds things in bursts of about twelve seconds - if the cycle count " +
                    "above is not climbing while a scan runs, the radio is stuck and " +
                    "toggling Bluetooth off and on clears it."
            )
        }

        // ------------------------------------------------------------ sub-GHz
        if (!scan.sdrConnected) {
        item { SectionHeader("Tire pressure sensors need a radio the phone does not have") }
        item {
            InfoCard(
                "Why",
                "TPMS sensors transmit at 315 MHz in North America and 433.92 MHz in " +
                    "Europe. A phone's radios cover 2.4 GHz, 5 GHz, 6 GHz and the cellular " +
                    "bands, and none of them can be tuned anywhere near those frequencies. " +
                    "No app can work around this - it is the antenna and the transceiver, " +
                    "not the software.",
                accent = ThreatMedium
            )
        }
        item {
            Column(panel()) {
                Text("What to buy", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Buy(
                    "RTL-SDR Blog V4",
                    "about 40 USD",
                    "The one to get. R828D tuner, 500 kHz to 1.75 GHz, TCXO so it does not " +
                        "drift, and it is the reference device every decoder is tested against."
                )
                Buy(
                    "USB-C OTG adapter",
                    "about 10 USD",
                    "The dongle is USB-A. Get a powered adapter if your phone browns out - " +
                        "an SDR pulls around 300 mA and some phones will not supply it."
                )
                Buy(
                    "Telescopic dipole antenna",
                    "usually included",
                    "Set each leg to roughly 23 cm for 315 MHz or 17 cm for 433 MHz, and lay " +
                        "the dipole horizontally. Antenna length matters more than the radio."
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "An ESP32 or a Flipper can also receive these bands, but neither exposes " +
                        "raw samples to a phone over a standard protocol, so Wave could not " +
                        "decode from them without a custom firmware bridge. The RTL-SDR path " +
                        "is the one that works today.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        item { SectionHeader("Connecting the SDR") }
        item {
            Column(panel()) {
                Step(1, "Plug the dongle into the phone through the OTG adapter.")
                Step(2, "Install a USB driver app that exposes an rtl_tcp server - \"RTL-SDR Driver\" by Martin Marinov is the usual one, and it is free.")
                Step(3, "Open it, allow USB access when Android asks, and start the server on port 1234.")
                Step(4, "Come back to Wave and start a scan. This screen will show the radio as connected.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wave talks to the driver over a local socket rather than opening the " +
                        "USB device itself. That means no root, no custom kernel, and the " +
                        "driver app handles the device-specific quirks.",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
        }
        item {
            Column(panel()) {
                Text("What Wave decodes", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                KeyValue("Native decoders", "Toyota PMV-107J, Ford, Schrader")
                KeyValue("Protocol reference", vm.tpmsProtocolCount.toString() + " TPMS protocols catalogued")
                KeyValue("Manufacturers", vm.tpmsManufacturers.joinToString(", ").ifBlank { "-" })
                Spacer(Modifier.height(6.dp))
                Text(
                    "A sensor ID is stable for the life of the sensor, so a vehicle that keeps " +
                        "appearing behind you is visible in the sub-GHz list even when its " +
                        "driver never touches a phone. Bursts that do not match a native " +
                        "decoder are matched against the protocol catalogue by bit length and " +
                        "modulation and reported as a candidate, not as a fact.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // -------------------------------------------------------------- wifi
        item { SectionHeader("Get full-rate Wi-Fi scanning") }
        item {
            Column(panel()) {
                Text(
                    "Android limits an app to four scans every two minutes. At 50 km/h that " +
                        "is one scan every 400 metres, and most of a street goes unheard.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Step(1, "Settings, About phone, tap Build number seven times.")
                Step(2, "Settings, System, Developer options.")
                Step(3, "Turn off \"Wi-Fi scan throttling\".")
                Spacer(Modifier.height(6.dp))
                Text(
                    "This costs battery. It is the single biggest improvement available " +
                        "without hardware.",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }

        // -------------------------------------------------------------- cell
        item { SectionHeader("Cell-site simulators") }
        item {
            InfoCard(
                "What Wave can and cannot tell you",
                ImsiCatcherDetector.LIMITATION_NOTE,
                accent = ThreatMedium
            )
        }

        item { SectionHeader("Reference data on board") }
        item {
            Column(panel()) {
                KeyValue("IEEE OUI assignments", vm.ouiCount.toString(), mono = true)
                KeyValue("Bluetooth SIG company IDs", vm.btSigCount.toString(), mono = true)
                KeyValue("Sub-GHz protocols", vm.rfProtocolCount.toString(), mono = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    "All three ship inside the APK and are queried offline. Nothing about a " +
                        "device you see is sent anywhere to identify it.",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * One thing that is wrong, stated plainly, with the screen that fixes it.
 *
 * Every entry has to be actionable. "GPS has no fix yet" is not on this list, because the
 * answer is to go outside and wait, not to open a setting - and a diagnostics panel that
 * lists things you cannot act on trains people to ignore the ones you can.
 */
private class Problem(
    val title: String,
    val detail: String,
    val action: String,
    val intent: (Context) -> Intent
)

/**
 * Turns scan state into the actionable list. Order is by how badly it breaks the capture:
 * a Bluetooth radio that is off costs two of the four lanes.
 */
private fun diagnose(scan: ScanService.ScanState): List<Problem> {
    val out = mutableListOf<Problem>()

    val btOff = scan.bleError?.contains("off") == true ||
        scan.btClassicError?.contains("off") == true
    if (btOff) {
        out += Problem(
            "Bluetooth is switched off",
            "Two of the four radios are dark. Trackers, body cameras, earbuds, car head " +
                "units - none of it will be heard, and the device list will look " +
                "reassuringly empty.",
            "Open Bluetooth settings"
        ) { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) }
    }

    val permissionMissing = scan.bleError?.contains("permission") == true ||
        scan.btClassicError?.contains("permission") == true
    if (permissionMissing) {
        out += Problem(
            "Nearby-devices permission is not granted",
            "Android returns an empty result rather than an error when this is missing, " +
                "so the scan appears to work and finds nothing.",
            "Open app permissions"
        ) { ctx ->
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.packageName, null)
            )
        }
    }

    if (scan.wifiThrottled) {
        out += Problem(
            "Wi-Fi scan throttling is on",
            "Android is capping this app to four scans every two minutes. At 50 km/h that " +
                "is one scan every 400 metres and most of a street goes unheard. Turn off " +
                "\"Wi-Fi scan throttling\" in Developer options - the steps are further " +
                "down this screen.",
            "Open developer options"
        ) { Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
    }

    if (scan.running && scan.btClassicError == null && scan.btClassicCycles == 0) {
        out += Problem(
            "Classic Bluetooth has not completed an inquiry",
            "The scan is running but no inquiry cycle has finished. The adapter is " +
                "usually wedged by another app holding discovery; toggling Bluetooth off " +
                "and on clears it.",
            "Open Bluetooth settings"
        ) { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) }
    }

    return out
}

@Composable
private fun Fix(p: Problem, onAct: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, ThreatHigh.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("!", color = ThreatHigh, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text(
                p.title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(p.detail, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Phosphor.copy(alpha = 0.12f))
                .border(1.dp, Phosphor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable(onClick = onAct)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(p.action, color = Phosphor, fontSize = 12.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun panel(): Modifier = Modifier
    .fillMaxWidth()
    .clip(RoundedCornerShape(10.dp))
    .background(Panel)
    .border(1.dp, Hairline, RoundedCornerShape(10.dp))
    .padding(12.dp)

@Composable
private fun Status(label: String, ok: Boolean, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (ok) "+" else "-",
            color = if (ok) Phosphor else TextTertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, color = TextPrimary, fontSize = 13.sp)
            Text(detail, color = TextTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun Buy(name: String, price: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = Phosphor, fontSize = 13.sp)
            Text(price, color = TextTertiary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Spacer(Modifier.height(3.dp))
        Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Step(n: Int, text: String, accent: Color = Phosphor) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            n.toString() + ".",
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Text(
            text,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
