package com.wave.scanner.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wave.scanner.data.db.Threat
import com.wave.scanner.scan.GnssTracker
import com.wave.scanner.detect.Ranging
import com.wave.scanner.ui.WaveViewModel
import com.wave.scanner.ui.components.Chip
import com.wave.scanner.ui.components.EmptyState
import com.wave.scanner.ui.components.color
import com.wave.scanner.ui.components.formatDistance
import com.wave.scanner.ui.theme.Hairline
import com.wave.scanner.ui.theme.LocalPalette
import com.wave.scanner.ui.theme.Panel
import com.wave.scanner.ui.theme.PanelHigh
import com.wave.scanner.ui.theme.Phosphor
import com.wave.scanner.ui.theme.TextPrimary
import com.wave.scanner.ui.theme.TextSecondary
import com.wave.scanner.ui.theme.TextTertiary
import com.wave.scanner.ui.theme.ThreatCritical
import com.wave.scanner.ui.theme.Void
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.wave.scanner.ui.map.TileSource
import com.wave.scanner.ui.map.rememberTileLayer
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Sightings plotted over an OpenStreetMap basemap.
 *
 * The plot is drawn in Web Mercator so it lines up with the tiles underneath it; see
 * [TileSource] for what the basemap costs in privacy terms and why it is drawn this way.
 * Tiles are tinted for dark themes rather than swapped for a dark tile set, so all twenty
 * themes get a basemap that belongs to them without twenty tile sources to fetch.
 */
@Composable
fun MapScreen(vm: WaveViewModel, onDevice: (String) -> Unit) {
    val context = LocalContext.current
    val state by vm.mapState.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()

    var windowHours by remember { mutableStateOf(24) }
    var selected by remember { mutableStateOf<WaveViewModel.MapPoint?>(null) }

    LaunchedEffect(windowHours) { vm.refreshMap(windowHours, lastKnown(context)) }

    Column(Modifier.fillMaxSize().background(Void)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(1, 6, 24, 168).forEach { h ->
                Chip(
                    if (h < 24) h.toString() + "h" else (h / 24).toString() + "d",
                    Phosphor,
                    selected = windowHours == h
                ) { windowHours = h }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (importing) "importing" else "import cameras",
                color = if (importing) TextTertiary else Phosphor,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !importing) {
                        lastKnown(context)?.let { (la, lo) -> vm.importAlprAround(la, lo) }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (state.points.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    "Nothing to plot",
                    "No sightings with a GPS position in this window. Positions are only " +
                        "recorded while a scan is running and the phone has a fix - and " +
                        "indoors, especially in a basement, there may never be one. The " +
                        "devices are still being heard and recorded; they just have no " +
                        "coordinates to sit on. Step outside and the same scan starts " +
                        "drawing."
                )
            }
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            ) {
                Plot(state, onSelect = { selected = it })
            }
        }

        selected?.let { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelHigh)
                    .border(1.dp, p.threat.color().copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .clickable { onDevice(p.deviceId) }
                    .padding(12.dp)
            ) {
                Text(
                    p.deviceClass.name.lowercase().replace('_', ' '),
                    color = p.threat.color(),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    p.deviceId,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(4.dp))
                Text("Tap to open this device", color = TextTertiary, fontSize = 11.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Legend("device", TextSecondary)
            Legend("threat", Threat.HIGH.color())
            Legend("mapped ALPR", ThreatCritical)
            Spacer(Modifier.weight(1f))
            Text(
                totals.alprCameras.toString() + " cameras",
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Recolours the basemap to sit under the active theme.
 *
 * OSM tiles are a light, saturated cartography, and dropping them unmodified under a dark
 * theme produces a glowing white slab with the scan plotted invisibly on top. Rather than
 * source a second tile set, dark themes get an inversion with the hue rotated back, which
 * turns paper-white into near-black while leaving roads and labels legible. Light themes
 * get a desaturation instead, so the map reads as background and the sightings drawn over
 * it stay the most saturated thing on screen.
 */
@Composable
private fun basemapColorFilter(): ColorFilter {
    val pal = LocalPalette.current
    return remember(pal.id) {
        if (pal.isDark) {
            // Invert, then pull most of the saturation out. A raw inversion turns OSM's
            // green parks magenta, which reads as an alert colour on a threat map.
            val inverted = ColorMatrix(
                floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 225f,
                    0f, -0.85f, 0f, 0f, 225f,
                    0f, 0f, -0.85f, 0f, 225f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            inverted.timesAssign(ColorMatrix().apply { setToSaturation(0.4f) })
            ColorFilter.colorMatrix(inverted)
        } else {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.45f) })
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = TextTertiary, fontSize = 9.sp, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun Plot(state: WaveViewModel.MapState, onSelect: (WaveViewModel.MapPoint?) -> Unit) {
    val lats = state.points.map { it.lat }
    val lons = state.points.map { it.lon }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()

    // Pad by a tenth so points never sit on the frame, and never let the span collapse to
    // zero - a stationary scan would divide by zero and put everything in one corner.
    val padLat = max((maxLat - minLat) * 0.1, 0.0005)
    val padLon = max((maxLon - minLon) * 0.1, 0.0005)
    val loLat = minLat - padLat
    val hiLat = maxLat + padLat
    val loLon = minLon - padLon
    val hiLon = maxLon + padLon

    val midLon = (loLon + hiLon) / 2.0
    val midLat = (loLat + hiLat) / 2.0
    val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.05)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tiles = rememberTileLayer(context, scope)
    // Same reason as the palette below: a DrawScope cannot read a CompositionLocal.
    val basemapFilter = basemapColorFilter()

    // Read once, here, because a DrawScope lambda is not a composable and cannot reach a
    // CompositionLocal. Hoisting also means the theme is read once per frame instead of
    // once per plotted point.
    val pal = LocalPalette.current

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(state.points) {
                detectTapGestures { tap ->
                    val w = this.size.width.toFloat()
                    val h = this.size.height.toFloat()
                    val z = TileSource.zoomFor(loLat, hiLat, loLon, hiLon, w, h)
                    val originX = TileSource.lonToWorldX(midLon, z) - w / 2
                    val originY = TileSource.latToWorldY(midLat, z) - h / 2
                    fun x(lon: Double) = (TileSource.lonToWorldX(lon, z) - originX).toFloat()
                    fun y(lat: Double) = (TileSource.latToWorldY(lat, z) - originY).toFloat()
                    val hit = state.points.minByOrNull { p ->
                        abs(x(p.lon) - tap.x) + abs(y(p.lat) - tap.y)
                    }
                    val ok = hit != null &&
                        abs(x(hit.lon) - tap.x) < 40f && abs(y(hit.lat) - tap.y) < 40f
                    onSelect(if (ok) hit else null)
                }
            }
    ) {
        val w = this.size.width
        val h = this.size.height

        val z = TileSource.zoomFor(loLat, hiLat, loLon, hiLon, w, h)
        val originX = TileSource.lonToWorldX(midLon, z) - w / 2
        val originY = TileSource.latToWorldY(midLat, z) - h / 2

        fun px(lon: Double) = (TileSource.lonToWorldX(lon, z) - originX).toFloat()
        fun py(lat: Double) = (TileSource.latToWorldY(lat, z) - originY).toFloat()

        // Basemap. Tiles are laid out on the world grid, so the range covering the
        // viewport is just the origin and the far corner divided by the tile size.
        val ts = TileSource.TILE_SIZE
        val maxIndex = (1 shl z) - 1
        val firstX = floor(originX / ts).toInt()
        val firstY = floor(originY / ts).toInt()
        val lastX = floor((originX + w) / ts).toInt()
        val lastY = floor((originY + h) / ts).toInt()

        for (tx in firstX..lastX) {
            for (ty in firstY..lastY) {
                if (ty < 0 || ty > maxIndex) continue
                // X wraps at the antimeridian; Y does not, because the world ends there.
                val wrappedX = ((tx % (maxIndex + 1)) + maxIndex + 1) % (maxIndex + 1)
                val bmp = tiles.request(z, wrappedX, ty) ?: continue
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset(
                        (tx * ts - originX).roundToInt(),
                        (ty * ts - originY).roundToInt()
                    ),
                    dstSize = IntSize(ts, ts),
                    colorFilter = basemapFilter
                )
            }
        }

        // A faint graticule over the streets, so position is still readable where no tile
        // has arrived yet and the scale bar has something to sit against.
        val steps = 6
        for (i in 0..steps) {
            val gx = w * i / steps
            val gy = h * i / steps
            drawLine(pal.hairline, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            drawLine(pal.hairline, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }

        // Mapped cameras first, so a real detection always draws on top of a map marker.
        state.cameras.forEach { (la, lo) ->
            val c = Offset(px(lo), py(la))
            drawCircle(pal.threatCritical.copy(alpha = 0.5f), radius = 5f, center = c, style = Stroke(1.5f))
            drawLine(
                pal.threatCritical.copy(alpha = 0.5f),
                Offset(c.x - 7f, c.y), Offset(c.x + 7f, c.y), strokeWidth = 1f
            )
        }

        // Metres per pixel at this latitude, so an uncertainty radius in metres can be drawn
        // at the size it actually is on the ground instead of a fixed number of pixels.
        val metersPerPx = run {
            val lonSpan = TileSource.worldXToLon(originX + 100.0, z) -
                TileSource.worldXToLon(originX, z)
            Ranging.metersBetween(midLat, 0.0, midLat, lonSpan) / 100.0
        }

        state.points.sortedBy { it.threat.ordinal }.forEach { p ->
            val c = Offset(px(p.lon), py(p.lat))
            val threatening = p.threat >= Threat.MEDIUM
            val colour = if (threatening) p.threat.color(pal) else pal.textTertiary

            // The uncertainty ring. Drawn only when it is big enough on screen to read as a
            // ring rather than a smudge, and clamped at the top so a wild estimate does not
            // wash the whole canvas in one translucent disc.
            if (p.radiusMeters > 0 && metersPerPx > 0) {
                val rPx = (p.radiusMeters / metersPerPx).toFloat()
                if (rPx > 6f) {
                    val shown = rPx.coerceAtMost(w / 2.5f)
                    drawCircle(
                        colour.copy(alpha = if (threatening) 0.10f else 0.05f),
                        radius = shown, center = c
                    )
                    drawCircle(
                        colour.copy(alpha = if (p.triangulated) 0.45f else 0.20f),
                        radius = shown, center = c,
                        // Dashed while the estimate rests on too few vantage points, so the
                        // difference between "we triangulated this" and "we guessed from one
                        // spot" is visible without opening anything.
                        style = Stroke(
                            width = 1f,
                            pathEffect = if (p.triangulated) null
                            else PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        )
                    )
                }
            }

            if (threatening) {
                drawCircle(colour.copy(alpha = 0.15f), radius = 12f, center = c)
                drawCircle(colour, radius = 4.5f, center = c)
            } else {
                drawCircle(colour.copy(alpha = 0.6f), radius = 2.2f, center = c)
            }
        }

        state.me?.let { (la, lo) ->
            val c = Offset(px(lo), py(la))
            drawCircle(pal.accent, radius = 5f, center = c)
            drawCircle(pal.accent, radius = 14f, center = c, style = Stroke(1.5f))
        }

        // Scale bar: a quarter of the canvas width, converted back to real metres by
        // asking the projection what longitude that many pixels east actually is.
        val barLon = TileSource.worldXToLon(originX + w / 4.0, z) -
            TileSource.worldXToLon(originX, z)
        val quarterSpanM =
            GnssTracker.distanceMeters(midLat, midLon, midLat, midLon + barLon)
        drawLine(
            pal.textSecondary,
            Offset(16f, h - 20f), Offset(16f + w / 4f, h - 20f),
            strokeWidth = 2f
        )
        drawLine(pal.textSecondary, Offset(16f, h - 25f), Offset(16f, h - 15f), strokeWidth = 2f)
        drawLine(
            pal.textSecondary,
            Offset(16f + w / 4f, h - 25f), Offset(16f + w / 4f, h - 15f),
            strokeWidth = 2f
        )
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                // Was a hardcoded slate grey, which vanished against the pale themes.
                color = pal.textSecondary.toArgb()
                textSize = 26f
                isAntiAlias = true
            }
            drawText(formatDistance(quarterSpanM), 20f, h - 30f, paint)
        }
    }
}

@SuppressLint("MissingPermission")
private fun lastKnown(context: Context): Pair<Double, Double>? {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    return providers
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { it.latitude to it.longitude }
}
