package com.wave.scanner.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A street basemap, drawn from OpenStreetMap raster tiles.
 *
 * The map used to be a bare grid, on the reasoning that fetching tiles tells a tile server
 * roughly where you have been - a real cost in an app whose whole subject is being watched.
 * The owner weighed that and asked for streets, so this is the shape that concedes the
 * least: tiles are cached to disk on first sight and never re-requested, requests carry no
 * identifier beyond the mandatory User-Agent, and nothing about the scan - not a device,
 * not a track, not a count - is ever sent anywhere. What leaks is which map squares were
 * looked at, once each.
 *
 * Web Mercator, because that is the projection the tiles are cut in and any other choice
 * would leave the plotted points sliding against the streets underneath them.
 */
object TileSource {

    const val TILE_SIZE = 256

    /**
     * OSM's tile policy requires a User-Agent that identifies the application, and blocks
     * traffic that does not carry one. This is that identifier.
     */
    private const val USER_AGENT = "Wave/1.0 (offline wardriving map; contact via app store)"

    fun lonToWorldX(lon: Double, z: Int): Double =
        (lon + 180.0) / 360.0 * n(z) * TILE_SIZE

    fun latToWorldY(lat: Double, z: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - asinh(tan(rad)) / PI) / 2.0 * n(z) * TILE_SIZE
    }

    fun worldXToLon(x: Double, z: Int): Double = x / (n(z) * TILE_SIZE) * 360.0 - 180.0

    fun worldYToLat(y: Double, z: Int): Double {
        val t = PI * (1.0 - 2.0 * y / (n(z) * TILE_SIZE))
        return Math.toDegrees(atan(sinh(t)))
    }

    private fun n(z: Int): Double = 2.0.pow(z)

    /**
     * The largest zoom at which the whole span still fits on screen.
     *
     * Capped at 19 because OSM does not cut tiles deeper, and floored at 1 so a session
     * spanning continents still renders instead of failing to find a fitting zoom.
     */
    fun zoomFor(
        loLat: Double, hiLat: Double,
        loLon: Double, hiLon: Double,
        widthPx: Float, heightPx: Float
    ): Int {
        if (widthPx <= 0f || heightPx <= 0f) return 16
        for (z in MAX_ZOOM downTo MIN_ZOOM) {
            val worldW = lonToWorldX(hiLon, z) - lonToWorldX(loLon, z)
            // World Y grows southward, so the low latitude is the larger ordinate.
            val worldH = latToWorldY(loLat, z) - latToWorldY(hiLat, z)
            if (worldW <= widthPx && worldH <= heightPx) return z
        }
        return MIN_ZOOM
    }

    /** OSM does not cut tiles deeper than 19. */
    const val MAX_ZOOM = 19
    const val MIN_ZOOM = 1

    fun url(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"

    fun userAgent() = USER_AGENT
}

/**
 * Tiles held in memory, backed by a disk cache that survives the process.
 *
 * A tile is immutable for practical purposes - OSM re-cuts them on a scale of weeks - so a
 * file that exists is always served rather than revalidated. That makes a revisited area
 * free and offline-capable, which matters for an app used in a car.
 */
class TileStore(context: Context) {

    private val dir = File(context.cacheDir, "tiles").apply { mkdirs() }

    /**
     * Sized in bytes rather than entries. A 256x256 ARGB_8888 tile is 256 KB, so a screen
     * of them is a few megabytes and this holds roughly three screens' worth.
     */
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Caps concurrent network fetches. OSM asks for no more than two connections at a
     * time, and a fast pan can otherwise queue a hundred at once.
     */
    private val permits = Semaphore(2)

    /** Tiles already attempted and failed, so a dead area is not retried on every frame. */
    private val failed = HashSet<String>()

    fun cached(z: Int, x: Int, y: Int): Bitmap? = memory.get(key(z, x, y))

    suspend fun load(z: Int, x: Int, y: Int): Bitmap? {
        val k = key(z, x, y)
        memory.get(k)?.let { return it }
        synchronized(failed) { if (k in failed) return null }

        val file = File(dir, "$z-$x-$y.png")
        if (file.exists()) {
            decode(file)?.let { memory.put(k, it); return it }
        }

        return permits.withPermit {
            memory.get(k)?.let { return@withPermit it }
            val ok = withContext(Dispatchers.IO) { fetch(z, x, y, file) }
            if (!ok) {
                synchronized(failed) { failed.add(k) }
                null
            } else {
                decode(file)?.also { memory.put(k, it) }
            }
        }
    }

    private suspend fun decode(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun fetch(z: Int, x: Int, y: Int, dest: File): Boolean = try {
        val conn = (URL(TileSource.url(z, x, y)).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", TileSource.userAgent())
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        conn.use { c ->
            if (c.responseCode != 200) {
                Log.w(TAG, "tile $z/$x/$y -> HTTP ${c.responseCode}")
                false
            } else {
                // Write beside the target then rename, so a fetch interrupted halfway
                // cannot leave a truncated PNG that the decoder will reject forever.
                val tmp = File(dest.absolutePath + ".part")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(dest)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "tile $z/$x/$y failed: ${e.message}")
        false
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    private companion object { const val TAG = "TileStore" }
}

/**
 * The tiles currently available to draw, as Compose state.
 *
 * [request] is called from the draw pass for every tile the viewport needs. Ones already in
 * memory are returned immediately; the rest are fetched off-thread and land in the state
 * map, which recomposes the map so they appear. That means the first frame of a new area
 * draws the plot over empty space and streets fade in a moment later, rather than the map
 * blocking on the network.
 */
class TileLayer(
    private val store: TileStore,
    private val scope: CoroutineScope,
    private val loaded: SnapshotStateMap<String, Bitmap>
) {
    private val inFlight = HashSet<String>()

    fun request(z: Int, x: Int, y: Int): Bitmap? {
        val k = "$z/$x/$y"
        loaded[k]?.let { return it }
        store.cached(z, x, y)?.let { loaded[k] = it; return it }
        synchronized(inFlight) { if (!inFlight.add(k)) return null }
        scope.launch {
            val bmp = store.load(z, x, y)
            synchronized(inFlight) { inFlight.remove(k) }
            if (bmp != null) loaded[k] = bmp
        }
        return null
    }
}

@Composable
fun rememberTileLayer(context: Context, scope: CoroutineScope): TileLayer {
    val store = remember(context) { TileStore(context) }
    val loaded = remember { mutableStateMapOf<String, Bitmap>() }
    return remember(store, scope) { TileLayer(store, scope, loaded) }
}
