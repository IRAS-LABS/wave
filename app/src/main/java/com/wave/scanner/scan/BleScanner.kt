package com.wave.scanner.scan

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.wave.scanner.data.db.Band

/**
 * BLE advertising scanner.
 *
 * Runs unfiltered at low latency because the interesting devices are precisely the ones
 * you cannot name in advance. Filtering by service UUID would be cheaper on battery but
 * would also discard every unknown-vendor tracker, which is the whole point.
 */
class BleScanner(private val context: Context) : Scanner {

    override val band = Band.BLE

    private val manager = context.applicationContext
        .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    var lastError: String? = null
        private set

    override fun isAvailable(): Boolean {
        // Each refusal records why. An LE lane that is quiet because Bluetooth is switched
        // off must not read the same as one that is quiet because the room is empty.
        val adapter = manager.adapter
        if (adapter == null) { lastError = "no Bluetooth adapter"; return false }
        if (!adapter.isEnabled) { lastError = "Bluetooth is off"; return false }
        if (!hasPermission()) { lastError = "nearby-devices permission not granted"; return false }
        lastError = null
        return true
    }

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    override fun start(onObservation: (RadioObservation) -> Unit) {
        if (callback != null) return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled || !hasPermission()) return

        scanner = adapter.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Pick up long-range and high-throughput advertisers too.
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { onObservation(it.toObservation()) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onObservation(it.toObservation()) }
            }

            override fun onScanFailed(errorCode: Int) {
                lastError = describeError(errorCode)
                Log.w(TAG, "scan failed: $lastError")
            }
        }
        callback = cb

        runCatching { scanner?.startScan(emptyList<ScanFilter>(), settings, cb) }
            .onFailure { lastError = it.message }
    }

    override fun stop() {
        val cb = callback ?: return
        runCatching { scanner?.stopScan(cb) }
        callback = null
        scanner = null
    }

    private fun ScanResult.toObservation(): RadioObservation {
        val record = scanRecord
        val manufacturers = HashMap<Int, ByteArray>()
        record?.manufacturerSpecificData?.let { sparse ->
            for (i in 0 until sparse.size()) {
                manufacturers[sparse.keyAt(i)] = sparse.valueAt(i) ?: ByteArray(0)
            }
        }

        val uuids = record?.serviceUuids
            ?.mapNotNull { shortUuid(it.uuid.toString()) }
            ?.toSet()
            .orEmpty()

        val flags = buildString {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                append(if (isConnectable) "connectable" else "non-connectable")
                append(" tx=").append(txPower)
                append(" phy=").append(primaryPhy)
            }
        }.trim().ifBlank { null }

        return RadioObservation(
            band = Band.BLE,
            address = device?.address?.uppercase(),
            name = record?.deviceName?.takeIf { it.isNotBlank() },
            rssi = rssi,
            timestamp = System.currentTimeMillis(),
            capabilities = flags,
            manufacturerData = manufacturers,
            serviceUuids = uuids
        )
    }

    /** Reduce a full 128-bit UUID to its 16-bit short form when it uses the SIG base. */
    private fun shortUuid(full: String): String? {
        val lower = full.lowercase()
        return if (lower.endsWith("-0000-1000-8000-00805f9b34fb")) lower.substring(4, 8)
        else lower
    }

    private fun describeError(code: Int): String = when (code) {
        SCAN_FAILED_ALREADY_STARTED -> "already scanning"
        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed"
        SCAN_FAILED_INTERNAL_ERROR -> "internal error"
        SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported on this device"
        5 -> "out of hardware scan slots"
        6 -> "scanning too frequently - Android rate limit"
        else -> "error $code"
    }

    private companion object {
        const val TAG = "BleScanner"
        const val SCAN_FAILED_ALREADY_STARTED = 1
        const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
        const val SCAN_FAILED_INTERNAL_ERROR = 3
        const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
    }
}
