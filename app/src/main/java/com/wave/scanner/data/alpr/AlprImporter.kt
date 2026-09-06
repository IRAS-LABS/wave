package com.wave.scanner.data.alpr

import android.util.Log
import com.wave.scanner.data.db.AlprCameraEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Imports mapped ALPR camera positions from OpenStreetMap via the Overpass API.
 *
 * This is the crowdsourced layer, and it is kept strictly separate from anything Wave
 * hears on the air. A camera on this map is somebody's report that a camera exists at a
 * location; a camera in the device list is a radio Wave actually received. Merging the
 * two would make the app feel more capable and be considerably less trustworthy.
 *
 * The DeFlock project maintains most of this data in OSM, tagged
 * man_made=surveillance + surveillance:type=ALPR, with operator=Flock Safety on the
 * majority of US entries.
 */
object AlprImporter {

    /** Public mirrors, tried in order. The main endpoint rate-limits aggressively. */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    data class Result(val imported: Int, val source: String, val error: String? = null)

    /**
     * Downloads every mapped ALPR inside a bounding box.
     *
     * Callers should keep boxes modest. A whole-country query is tens of thousands of
     * nodes and Overpass will drop it rather than serve it.
     */
    suspend fun fetchBox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double
    ): Pair<List<AlprCameraEntity>, Result> = withContext(Dispatchers.IO) {
        val bbox = "$minLat,$minLon,$maxLat,$maxLon"
        val query = """
            [out:json][timeout:90];
            (
              node["man_made"="surveillance"]["surveillance:type"="ALPR"]($bbox);
              way["man_made"="surveillance"]["surveillance:type"="ALPR"]($bbox);
            );
            out center tags;
        """.trimIndent()

        var lastError: String? = null
        for (endpoint in ENDPOINTS) {
            try {
                val body = post(endpoint, query)
                val cameras = parse(body)
                return@withContext cameras to Result(cameras.size, endpoint)
            } catch (e: IOException) {
                lastError = e.message
                Log.w(TAG, "overpass $endpoint failed: ${e.message}")
            }
        }
        emptyList<AlprCameraEntity>() to Result(0, "none", lastError ?: "all endpoints failed")
    }

    /** Convenience wrapper: a square box of the given radius around a point. */
    suspend fun fetchAround(lat: Double, lon: Double, radiusKm: Double) = fetchBox(
        minLat = lat - radiusKm / 111.0,
        minLon = lon - radiusKm / 111.0,
        maxLat = lat + radiusKm / 111.0,
        maxLon = lon + radiusKm / 111.0
    )

    private fun post(endpoint: String, query: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            // Overpass asks for an identifying agent and throttles anonymous clients harder.
            setRequestProperty("User-Agent", "Wave/1.0 (offline surveillance scanner)")
        }
        conn.outputStream.use {
            it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
        }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("HTTP $code ${err.take(200)}")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parse(body: String): List<AlprCameraEntity> {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<AlprCameraEntity>(elements.length())

        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            // Ways carry their position under "center" rather than at the top level.
            val lat = e.optDouble("lat", Double.NaN)
                .takeIf { !it.isNaN() }
                ?: e.optJSONObject("center")?.optDouble("lat", Double.NaN)
                    ?.takeIf { !it.isNaN() } ?: continue
            val lon = e.optDouble("lon", Double.NaN)
                .takeIf { !it.isNaN() }
                ?: e.optJSONObject("center")?.optDouble("lon", Double.NaN)
                    ?.takeIf { !it.isNaN() } ?: continue

            val tags = e.optJSONObject("tags")
            out.add(
                AlprCameraEntity(
                    id = "osm:" + e.optString("type", "node") + ":" + e.optLong("id"),
                    lat = lat,
                    lon = lon,
                    operator = tags?.optString("operator")?.ifBlank { null },
                    cameraType = tags?.optString("surveillance:type")?.ifBlank { null },
                    direction = tags?.optString("direction")?.ifBlank { null }
                        ?: tags?.optString("camera:direction")?.ifBlank { null },
                    source = "OpenStreetMap / DeFlock",
                    importedAt = now
                )
            )
        }
        return out
    }

    private const val TAG = "AlprImporter"
}
