package com.wave.scanner.scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.wave.scanner.data.db.Band

/**
 * Wi-Fi scanning, including the vendor information elements that make model fingerprinting
 * possible.
 *
 * Android throttles foreground apps to four scans per two minutes and there is no
 * permission that lifts it - only the "Wi-Fi scan throttling" toggle in Developer Options.
 * That toggle is a readable global setting, so rather than guess at the platform's mood the
 * scanner reads it and adapts: when throttling is off it scans six times faster, and it
 * stops telling the user to change a setting they have already changed.
 */
class WifiScanner(private val context: Context) : Scanner {

    override val band = Band.WIFI

    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var receiver: BroadcastReceiver? = null
    private var callback: ((RadioObservation) -> Unit)? = null

    /**
     * Completion times of the last four scans, as a fixed ring of primitives.
     *
     * This was an ArrayDeque<Long> and it crashed the app. [harvest] appends on the main
     * thread from a BroadcastReceiver while [throttleLikely] is read from a coroutine
     * worker, and ArrayDeque is not thread-safe: a clear() or removeFirst() landing between
     * the reader's size check and its first() read hands back a nulled-out slot, and
     * unboxing that null threw NullPointerException on Number.longValue().
     *
     * A LongArray cannot yield null no matter how the two threads interleave, which is the
     * entire point of using one here. The remaining race is benign - a reader can see a
     * half-updated ring and compute the throttle hint from a stale timestamp, which shows
     * the user a slightly wrong advisory rather than killing the process.
     */
    private val scanTimes = LongArray(4)
    @Volatile private var scanCount = 0

    /** When we last actually asked the platform to scan. See [requestScan]. */
    private var lastScanRequest = 0L

    /**
     * Whether the platform's scan throttle is switched off, read from Developer Options.
     *
     * Global settings are world-readable, so this needs no permission. It is sampled at
     * [start] rather than on every request because it is a user-facing toggle that changes
     * once in a blue moon, and a ContentResolver round trip per scan would be paying a real
     * cost to detect an event that effectively never happens mid-session.
     */
    @Volatile private var throttlingDisabled = false

    /**
     * How often we are willing to ask the platform to scan.
     *
     * Thirty seconds is the throttled budget - four scans per two minutes, exactly - and
     * asking faster than that only earns silent refusals. With throttling off there is no
     * budget to respect, and five seconds is roughly how long a full dual-band sweep takes,
     * so it is the fastest rate that is actually scanning rather than queueing.
     */
    private val scanIntervalMs: Long
        get() = if (throttlingDisabled) UNTHROTTLED_INTERVAL_MS else MIN_SCAN_INTERVAL_MS

    /**
     * Per-BSSID timestamp of the last radio-level sighting we forwarded, in the units
     * ScanResult itself uses (microseconds since boot). The cached result list is
     * re-delivered in full on every broadcast, so without this the same access point is
     * re-ingested on every wake and one AP standing still generates thousands of writes.
     */
    private val forwarded = HashMap<String, Long>()

    var lastResultCount: Int = 0
        private set

    val throttleLikely: Boolean
        get() {
            // The setting is the ground truth. When it says throttling is off, no amount of
            // inference from scan timing should second-guess it into warning the user again.
            if (throttlingDisabled) return false
            val n = scanCount
            if (n < scanTimes.size) return false
            // Oldest of the four is the slot the next write will overwrite.
            val oldest = scanTimes[n % scanTimes.size]
            return System.currentTimeMillis() - oldest < 2 * 60 * 1000
        }

    override fun isAvailable(): Boolean =
        wifi.isWifiEnabled && hasPermission()

    private fun hasPermission(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        return fine || nearby
    }

    override fun start(onObservation: (RadioObservation) -> Unit) {
        if (receiver != null) return
        callback = onObservation
        throttlingDisabled = readThrottleSetting()
        Log.i(TAG, "scan throttling ${if (throttlingDisabled) "off" else "on"}, " +
            "interval ${scanIntervalMs}ms")
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                harvest()
                requestScan()
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        // Deliver whatever the system already has so the first screen is never empty.
        harvest()
        requestScan()
    }

    override fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        callback = null
        scanCount = 0
        forwarded.clear()
        lastScanRequest = 0L
    }

    /**
     * Ask for a fresh scan, but no more often than the platform will honour.
     *
     * This used to fire on every SCAN_RESULTS_AVAILABLE broadcast, which is a feedback
     * loop: the broadcast is system-wide, so any other app's scan completing woke us to
     * request another, which completed, which woke us again. In testing it ran at over
     * ninety startScan calls per second, of which nearly all were rejected with "Scan
     * request throttled" - burning CPU in WifiService to accomplish nothing.
     *
     * Android's budget for a foreground app is four scans per two minutes, so one request
     * every thirty seconds is exactly the sustainable rate. Results still arrive
     * continuously, because other apps' scans and the system's own keep refreshing the
     * cache that [harvest] reads.
     */
    @Suppress("DEPRECATION")
    /**
     * Reads the Developer Options throttle toggle, defaulting to "throttled".
     *
     * Unset reads back as absent rather than as a value, and absent means the platform
     * default, which is throttling ON - so the safe default here is the pessimistic one.
     * A device too old to have the setting also lands on that branch, correctly.
     */
    private fun readThrottleSetting(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, KEY_SCAN_THROTTLE, 1) == 0
    }.getOrDefault(false)

    private fun requestScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRequest < scanIntervalMs) return
        lastScanRequest = now
        runCatching { wifi.startScan() }
            .onFailure { Log.w(TAG, "startScan refused: ${it.message}") }
    }

    private fun harvest() {
        val cb = callback ?: return
        if (!hasPermission()) return
        val results = runCatching { wifi.scanResults }.getOrNull() ?: return

        lastResultCount = results.size
        val now = System.currentTimeMillis()
        scanTimes[scanCount % scanTimes.size] = now
        scanCount++

        // Forward only access points the radio has genuinely re-heard since last time.
        // ScanResult.timestamp is set by the driver when the beacon was received, so it is
        // the one field that distinguishes "seen again" from "still in the cache".
        results.forEach { r ->
            val bssid = r.BSSID?.uppercase() ?: return@forEach
            val heardAt = r.timestamp
            if (forwarded.put(bssid, heardAt) == heardAt) return@forEach
            cb(toObservation(r, now))
        }
        if (forwarded.size > MAX_TRACKED_BSSIDS) forwarded.clear()
    }

    @Suppress("DEPRECATION")
    private fun toObservation(r: ScanResult, now: Long): RadioObservation {
        val ssid = r.SSID?.takeIf { it.isNotBlank() }
        return RadioObservation(
            band = Band.WIFI,
            address = r.BSSID?.uppercase(),
            name = ssid,
            rssi = r.level,
            timestamp = now,
            frequencyMhz = r.frequency,
            channel = channelFor(r.frequency),
            capabilities = r.capabilities,
            vendorIes = vendorIes(r)
        )
    }

    /**
     * Vendor-specific IEs are the payload that survives MAC randomisation. They are only
     * exposed from API 30, so on older devices fingerprinting degrades to the capability
     * string alone - which the classifier handles, just less precisely.
     */
    private fun vendorIes(r: ScanResult): Map<Int, ByteArray> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        val elements = runCatching { r.informationElements }.getOrNull() ?: return emptyMap()
        val out = HashMap<Int, ByteArray>()
        elements.forEach { ie ->
            runCatching {
                val buf = ie.bytes
                val arr = ByteArray(buf.remaining())
                buf.duplicate().get(arr)
                // Keep vendor-specific (221) and the HT/VHT/HE capability elements, which
                // together pin down chipset generation.
                if (ie.id == 221 || ie.id in intArrayOf(45, 191, 255)) {
                    out[ie.id] = arr
                }
            }
        }
        return out
    }

    private fun channelFor(freq: Int): Int? = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2412) / 5 + 1
        freq in 5170..5825 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5955) / 5 + 1
        else -> null
    }

    private companion object {
        const val TAG = "WifiScanner"

        /** Four scans per two minutes is the platform budget; this is that rate exactly. */
        const val MIN_SCAN_INTERVAL_MS = 30_000L

        /** The rate used when Developer Options has lifted the budget. */
        const val UNTHROTTLED_INTERVAL_MS = 5_000L

        /**
         * The Developer Options toggle, by its framework key. The constant on
         * Settings.Global is @hide, so the string is spelled out; it has been stable since
         * the throttle was introduced in Android 9.
         */
        const val KEY_SCAN_THROTTLE = "wifi_scan_throttle_enabled"

        /** Bound on the dedup map so a long drive through dense areas cannot grow it forever. */
        const val MAX_TRACKED_BSSIDS = 20_000
    }
}
