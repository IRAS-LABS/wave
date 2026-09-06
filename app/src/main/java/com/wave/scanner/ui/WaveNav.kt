package com.wave.scanner.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wave.scanner.ui.screens.DeviceDetailScreen
import com.wave.scanner.ui.screens.DevicesScreen
import com.wave.scanner.ui.screens.HardwareScreen
import com.wave.scanner.ui.screens.MapScreen
import com.wave.scanner.ui.screens.ScanScreen
import com.wave.scanner.ui.screens.SettingsScreen
import com.wave.scanner.ui.screens.ThreatsScreen
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatCritical
import com.wave.scanner.ui.theme.Void

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    SCAN("scan", "Scan", Icons.Filled.Radar),
    DEVICES("devices", "Devices", Icons.Filled.Wifi),
    THREATS("threats", "Alerts", Icons.Filled.Warning),
    MAP("map", "Map", Icons.Filled.Map),
    HARDWARE("hardware", "Radio", Icons.Filled.Memory),
    SETTINGS("settings", "Data", Icons.Filled.Settings)
}

@Composable
fun WaveNav(
    vm: WaveViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    /** Clears the stored acceptance and sends the user back to the scope screen. */
    onReviewTerms: () -> Unit,
    nav: NavHostController = rememberNavController()
) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val totals by vm.totals.collectAsStateWithLifecycle()
    val onDetail = route?.startsWith("device/") == true

    Scaffold(
        containerColor = Void,
        topBar = { TopBar(route, onDetail) { nav.popBackStack() } },
        bottomBar = {
            if (!onDetail) {
                BottomBar(route, totals.unackThreats) { tab ->
                    nav.navigate(tab.route) {
                        // The tabs are peers, not a stack: going Scan -> Map -> Scan should
                        // not leave two Scan entries behind the back button.
                        popUpTo(Tab.SCAN.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            NavHost(nav, startDestination = Tab.SCAN.route) {
                composable(Tab.SCAN.route) {
                    ScanScreen(vm, onStart, onStop) { nav.navigate("device/" + it) }
                }
                composable(Tab.DEVICES.route) {
                    DevicesScreen(vm) { nav.navigate("device/" + it) }
                }
                composable(Tab.THREATS.route) {
                    ThreatsScreen(vm) { nav.navigate("device/" + it) }
                }
                composable(Tab.MAP.route) {
                    MapScreen(vm) { nav.navigate("device/" + it) }
                }
                composable(Tab.HARDWARE.route) { HardwareScreen(vm) }
                composable(Tab.SETTINGS.route) { SettingsScreen(vm, onReviewTerms) }
                composable("device/{id}") { backStack ->
                    val id = backStack.arguments?.getString("id").orEmpty()
                    DeviceDetailScreen(vm, id) { nav.popBackStack() }
                }
            }
        }
    }
}

@Composable
private fun TopBar(route: String?, showBack: Boolean, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Void)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Phosphor,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onBack)
            )
            Spacer(Modifier.padding(start = 12.dp))
            Text(
                "DEVICE",
                color = TextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        } else {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(Phosphor)
            )
            Text(
                "WAVE",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 4.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                Tab.entries.firstOrNull { it.route == route }?.label?.uppercase().orEmpty(),
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun BottomBar(route: String?, unread: Int, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(top = 1.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Tab.entries.forEach { tab ->
            val active = route == tab.route
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) Phosphor else TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    if (tab == Tab.THREATS && unread > 0) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(ThreatCritical)
                                .border(1.dp, Panel, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    color = if (active) Phosphor else TextTertiary,
                    fontSize = 9.sp
                )
            }
        }
    }
}
