package com.wave.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wave.scanner.scan.ScanService
import com.wave.scanner.ui.AlertPrefs
import com.wave.scanner.ui.ConsentStore
import com.wave.scanner.ui.WaveNav
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.ui.screens.ConsentScreen
import com.wave.scanner.ui.theme.ThemeStore
import com.wave.scanner.ui.theme.WaveTheme
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatMedium
import com.wave.scanner.ui.theme.Void

/**
 * Single activity.
 *
 * Permissions are handled as a gate rather than sprinkled through the screens: without
 * location and Bluetooth, Android returns empty scan results instead of an error, so an
 * app that starts scanning without them looks like it is working and finds nothing. The
 * gate makes the requirement explicit before that can happen.
 */
class MainActivity : ComponentActivity() {

    private val required: Array<String> by lazy {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            // BLUETOOTH_SCAN and BLUETOOTH_CONNECT do not exist before Android 12.
            // Requesting them there is not merely useless: the result comes back DENIED,
            // and since the gate below used to insist on BLUETOOTH_SCAN, an Android 10 or
            // 11 phone could never get past the permission screen no matter what the user
            // tapped. Pre-12, Bluetooth scanning is authorised by location instead, which
            // is already in this list.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    private var granted by mutableStateOf(false)
    private var asked by mutableStateOf(false)
    private var consented by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        asked = true
        granted = hasCore()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // Read the stored theme before the first frame so the app never paints once in
        // the default theme and then flips to the chosen one.
        ThemeStore.load(this)
        granted = hasCore()
        // Read before the first frame, alongside the theme, so the app never shows the
        // scanner for a frame to somebody who has not seen the scope terms.
        consented = ConsentStore.accepted(this)
        AlertPrefs.load(this)

        setContent {
            val palette by ThemeStore.current.collectAsStateWithLifecycle()
            WaveTheme(palette = palette) {
                Surface(Modifier.fillMaxSize(), color = Void) {
                    // Consent first, permissions second. Asking for location and Bluetooth
                    // before saying what the app does with them is the wrong order: the
                    // system dialog is the point at which somebody decides, and they should
                    // already know what they are deciding about.
                    if (!consented) {
                        ConsentScreen(onAccept = {
                            ConsentStore.accept(this)
                            consented = true
                        })
                    } else if (granted) {
                        val vm: WaveViewModel = viewModel()
                        WaveNav(
                            vm = vm,
                            onStart = { ScanService.start(this) },
                            onStop = { ScanService.stop(this) },
                            onReviewTerms = {
                                ConsentStore.revoke(this)
                                consented = false
                            }
                        )
                    } else {
                        PermissionGate(
                            asked = asked,
                            onGrant = { requestPermissions.launch(required) },
                            onSettings = { openAppSettings() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings screen is the other way permissions change.
        granted = hasCore()
        consented = ConsentStore.accepted(this)
    }

    /**
     * Location and Bluetooth scan are the two that decide whether the app can work at all.
     * Notifications and phone state degrade features rather than break them, so they do
     * not hold the gate shut.
     */
    private fun hasCore(): Boolean =
        has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                has(Manifest.permission.BLUETOOTH_SCAN))

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}

@Composable
private fun PermissionGate(asked: Boolean, onGrant: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Void)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("WAVE", color = TextPrimary, fontSize = 26.sp, letterSpacing = 6.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Wardriving and counter-surveillance",
            color = TextTertiary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(28.dp))

        Need(
            "Location",
            "Android requires it before it will return any Wi-Fi or Bluetooth scan result " +
                "at all, and the wardrive trail and follow detection are built on positions."
        )
        Need(
            "Nearby devices",
            "Bluetooth LE scanning. This is how trackers, body cameras and vehicle " +
                "electronics are seen."
        )
        Need(
            "Phone state",
            "Reads the cell towers your phone can see. Optional - everything else still " +
                "works without it."
        )
        Need(
            "Notifications",
            "So an alert reaches you while the phone is in a pocket or on a mount. " +
                "Optional."
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Nothing collected here leaves the phone. Vendor lookups run against databases " +
                "inside the APK, and the only network request Wave ever makes is the " +
                "camera-map import you trigger yourself.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Phosphor.copy(alpha = 0.14f))
                .border(1.dp, Phosphor, RoundedCornerShape(10.dp))
                .clickable(onClick = onGrant)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("GRANT PERMISSIONS", color = Phosphor, letterSpacing = 2.sp, fontSize = 13.sp)
        }

        if (asked) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Still blocked? Open app settings and allow Location and Nearby devices " +
                    "by hand.",
                color = ThreatMedium,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .clickable(onClick = onSettings)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Open app settings", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Need(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Phosphor)
                .padding(horizontal = 2.dp)
        ) { Spacer(Modifier.height(4.dp)) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
