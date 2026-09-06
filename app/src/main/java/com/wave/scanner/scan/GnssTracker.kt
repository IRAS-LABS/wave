package com.wave.scanner.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Position source built on the platform LocationManager rather than Play Services.
 *
 * This is deliberate. On de-Googled and privacy-hardened Android builds, Play Services
 * may be absent or sandboxed, and a fused-location dependency would simply fail to resolve
 * a provider. LocationManager with the GPS and network providers works everywhere.
 */
class GnssTracker(private val context: Context) {

    private val lm = context.applicationContext
        .getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile var last: Location? = null
        private set

    private var listener: LocationListener? = null
    private var onFix: ((Location) -> Unit)? = null

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun providersEnabled(): Boolean =
        runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start(onLocation: (Location) -> Unit = {}) {
        if (!hasPermission() || listener != null) return
        onFix = onLocation

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                last = location
                onFix?.invoke(location)
            }
            // Required on API < 30; harmless to keep for older OEM builds.
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l

        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1_000L, 0f, l)
                }
            }
        }

        // Seed with the last known fix so the first observations are not orphaned.
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let { last = it }
        }
    }

    fun stop() {
        listener?.let { runCatching { lm.removeUpdates(it) } }
        listener = null
        onFix = null
    }

    /** True when the fix is recent and accurate enough to anchor a sighting. */
    fun hasUsableFix(maxAgeMs: Long = 30_000, maxAccuracyM: Float = 60f): Boolean {
        val l = last ?: return false
        val age = System.currentTimeMillis() - l.time
        return age in 0..maxAgeMs && (!l.hasAccuracy() || l.accuracy <= maxAccuracyM)
    }

    val satellitesInUse: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            last?.extras?.getInt("satellites")?.takeIf { it > 0 } else null

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Haversine distance. Used all over the follow-detector, so kept allocation-free. */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
        }

        /**
         * Very rough free-space range estimate. Presented in the UI as an order of
         * magnitude only - multipath and body-blocking make anything more precise a lie.
         */
        fun estimateMeters(rssi: Int, txPowerAt1m: Int = -59, pathLossExponent: Double = 2.7): Double {
            if (rssi == 0) return -1.0
            return Math.pow(10.0, (txPowerAt1m - rssi) / (10.0 * pathLossExponent))
        }
    }
}
