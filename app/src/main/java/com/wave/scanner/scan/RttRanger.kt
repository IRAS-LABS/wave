package com.wave.scanner.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Real distance measurement, by timing the signal instead of guessing from its loudness.
 *
 * 802.11mc Fine Timing Measurement asks an access point to bounce a frame back and measures
 * the round trip. Because the answer comes from the speed of light rather than from a path
 * loss model, a wall in the way delays the signal by a knowable amount instead of corrupting
 * the estimate beyond recovery: this lands within a metre or two, where [Ranging]'s
 * signal-strength estimate is doing well to land within a factor of two.
 *
 * The catch is consent. FTM is a conversation, and the access point has to answer. Most do
 * not - the responder side is optional and manufacturers largely skipped it - so this
 * applies to a minority of the access points in any given scan. [ScanResult.is80211mcResponder]
 * says which, and there is no way to range the rest.
 *
 * A second thing worth knowing before reading anything into the results: ranging is not
 * passive. Signal-strength estimates come from packets that were being broadcast anyway,
 * but an FTM request transmits, and the access point on the other end sees a device asking
 * it for its distance. For most of what this app looks at that is unremarkable; for
 * deliberately hostile infrastructure it is not nothing, which is why this never runs on its
 * own and only measures what it is explicitly asked to.
 */
class RttRanger(private val context: Context) {

    private val manager: WifiRttManager? by lazy {
        if (!supported()) null
        else runCatching {
            context.applicationContext
                .getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as WifiRttManager
        }.getOrNull()
    }

    /** Whether this phone has the hardware at all. Checked once; it cannot change. */
    fun supported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)

    /** Whether ranging is available right now - the user can switch it off in settings. */
    fun available(): Boolean = manager?.isAvailable == true

    /** One measured distance. Millimetres because that is the unit the radio reports in. */
    data class Measurement(
        val bssid: String,
        val distanceMm: Int,
        /**
         * The radio's own estimate of its error, in millimetres.
         *
         * Worth surfacing rather than hiding: it is the difference between "3.2 m, give or
         * take 40 cm" and "3.2 m, give or take 6 m", and those two readings support
         * completely different conclusions about whether something is in the car behind you.
         */
        val stdDevMm: Int,
        val rssi: Int,
        val successfulMeasurements: Int,
        val attemptedMeasurements: Int
    ) {
        val distanceMeters: Double get() = distanceMm / 1000.0

        /**
         * True when the spread is small enough that the number means something.
         *
         * A measurement whose standard deviation rivals the distance itself has been
         * scattered by reflections - common in a street, where the signal that arrives may
         * have come off a building rather than straight from the source. Such a reading is
         * always an OVER-estimate, since the bounced path is longer, so it is dropped
         * rather than shown.
         */
        val trustworthy: Boolean
            get() = stdDevMm < 2_000 && stdDevMm < distanceMm / 2 && successfulMeasurements >= 2
    }

    /**
     * Ranges the access points in [targets] that will answer.
     *
     * Filtering to responders first is not an optimisation - a request containing a
     * non-responder wastes one of the very few slots the platform allows per request and
     * comes back as a failure that says nothing.
     */
    suspend fun range(targets: List<ScanResult>): List<Measurement> {
        val mgr = manager ?: return emptyList()
        if (!mgr.isAvailable) return emptyList()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val responders = targets.filter { canRange(it) }
        if (responders.isEmpty()) return emptyList()

        // The platform caps peers per request, and exceeding it throws rather than
        // truncating. Taking the strongest is the right truncation: they are the ones close
        // enough for a precise distance to change any decision.
        val batch = responders.sortedByDescending { it.level }.take(maxPeers())

        return suspendCancellableCoroutine { cont ->
            val callback = object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    Log.w(TAG, "ranging failed, code $code")
                    if (cont.isActive) cont.resume(emptyList())
                }

                override fun onRangingResults(results: List<RangingResult>) {
                    val out = results.mapNotNull { r ->
                        if (r.status != RangingResult.STATUS_SUCCESS) null
                        else Measurement(
                            bssid = r.macAddress?.toString().orEmpty(),
                            distanceMm = r.distanceMm,
                            stdDevMm = r.distanceStdDevMm,
                            rssi = r.rssi,
                            successfulMeasurements = r.numSuccessfulMeasurements,
                            attemptedMeasurements = r.numAttemptedMeasurements
                        )
                    }
                    if (cont.isActive) cont.resume(out)
                }
            }
            runCatching {
                mgr.startRanging(
                    RangingRequest.Builder().addAccessPoints(batch).build(),
                    context.mainExecutor,
                    callback
                )
            }.onFailure {
                Log.w(TAG, "startRanging refused: ${it.message}")
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    /** Whether this particular access point will answer a ranging request. */
    fun canRange(result: ScanResult): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && result.is80211mcResponder

    private fun maxPeers(): Int =
        runCatching { RangingRequest.getMaxPeers() }.getOrDefault(DEFAULT_MAX_PEERS)

    private companion object {
        const val TAG = "RttRanger"

        /** The platform minimum, used when the real cap cannot be read. */
        const val DEFAULT_MAX_PEERS = 4
    }
}
