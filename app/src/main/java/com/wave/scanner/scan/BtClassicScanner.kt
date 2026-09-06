package com.wave.scanner.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wave.scanner.data.db.Band

/**
 * Classic Bluetooth inquiry discovery.
 *
 * BLE and classic are two different radios sharing one antenna, and an LE scan hears
 * nothing on classic. Car head units, hands-free kits, older speakers, barcode scanners,
 * body-worn recorders and a good deal of fleet equipment are classic-only, so a scanner
 * that runs LE alone reports an empty room and is believed.
 *
 * Inquiry is bursty rather than continuous - Android runs a roughly twelve second cycle
 * and stops - so discovery is restarted every time it finishes. That is the only way to
 * get continuous coverage, and it is why this stays separate from [BleScanner]: the two
 * have different duty cycles and different failure modes, and folding them together would
 * hide which of them actually stopped working.
 *
 * Classic addresses are burned in. Unlike BLE there is no rotation to defeat, so the MAC
 * is a real identity and [RadioObservation.identityKey] can use it directly.
 */
class BtClassicScanner(private val context: Context) : Scanner {

    override val band = Band.BT_CLASSIC

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter

    private var receiver: BroadcastReceiver? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Surfaced on the Radio screen so a silent classic lane is visible rather than assumed. */
    var lastError: String? = null
        private set

    /** Completed inquiry cycles. Zero while a scan runs means the radio is wedged. */
    var cycles: Int = 0
        private set

    override fun isAvailable(): Boolean {
        val a = adapter
        if (a == null) { lastError = "no Bluetooth adapter"; return false }
        if (!a.isEnabled) { lastError = "Bluetooth is off"; return false }
        if (!hasPermission()) { lastError = "nearby-devices permission not granted"; return false }
        lastError = null
        return true
    }

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-12 the manifest BLUETOOTH_ADMIN grant covers inquiry, but Android still
            // withholds the results without location, exactly as it does for Wi-Fi.
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    override fun start(onObservation: (RadioObservation) -> Unit) {
        if (receiver != null) return
        val a = adapter ?: return
        if (!a.isEnabled || !hasPermission()) return

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = deviceFrom(i) ?: return
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        runCatching { onObservation(observe(dev, rssi)) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        cycles++
                        scheduleRestart()
                    }
                }
            }
        }
        receiver = r

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Exporting is mandatory to declare on 34+. These are system broadcasts and no
        // other app has any business delivering them to us.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        restart()
    }

    /**
     * Queues the next inquiry cycle instead of starting it inline.
     *
     * Calling startDiscovery() from inside the ACTION_DISCOVERY_FINISHED callback did not
     * work: on hardware the classic lane ran exactly one twelve-second cycle per scan and
     * then went silent for the rest of the session, with no exception raised and no error
     * surfaced - the adapter is still tearing the previous cycle down and drops the
     * request. That is the worst failure this app can have: a band reporting zero while
     * looking healthy.
     *
     * A short hop back to the main looper is enough to let the stack settle. It is kept
     * well under the inquiry length so the duty cycle stays close to continuous.
     */
    private fun scheduleRestart() {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, RESTART_DELAY_MS)
    }

    private val restartRunnable = Runnable {
        // stop() may have landed while this was queued.
        if (receiver != null) restart()
    }

    @SuppressLint("MissingPermission")
    private fun restart() {
        val a = adapter ?: return
        if (!hasPermission()) return
        runCatching {
            // cancelDiscovery first: startDiscovery is a no-op while one is already
            // running, so without this a restart can silently do nothing at all.
            if (a.isDiscovering) a.cancelDiscovery()
            if (!a.startDiscovery()) lastError = "adapter refused startDiscovery"
        }.onFailure {
            lastError = it.message ?: it::class.java.simpleName
            Log.w(TAG, "classic discovery restart failed", it)
        }
    }

    private fun deviceFrom(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    /**
     * Every accessor is wrapped. A BluetoothDevice handed over by a broadcast can throw
     * SecurityException after a later permission revoke, and one bad device must not take
     * the whole inquiry down with it.
     */
    @SuppressLint("MissingPermission")
    private fun observe(dev: BluetoothDevice, rssi: Short): RadioObservation {
        val cod = runCatching { dev.bluetoothClass }.getOrNull()
        val uuids = runCatching {
            dev.uuids.orEmpty().mapNotNull { it?.uuid?.toString()?.lowercase() }
        }.getOrDefault(emptyList())

        return RadioObservation(
            band = Band.BT_CLASSIC,
            address = dev.address,
            name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() },
            // Short.MIN_VALUE is the "no RSSI in this broadcast" sentinel. -127 keeps the
            // signal-bar maths honest without inventing a plausible-looking number.
            rssi = if (rssi == Short.MIN_VALUE) -127 else rssi.toInt(),
            timestamp = System.currentTimeMillis(),
            capabilities = cod?.let { describeCod(it) },
            // SDP UUIDs arrive as full 128-bit strings rather than the 16-bit short forms
            // BLE reports, so they land as-is; the matcher handles both lengths.
            serviceUuids = uuids.toSet(),
            btCod = cod?.deviceClass
        )
    }

    /** A readable summary for the detail screen, e.g. "Audio/Video - headphones - audio". */
    private fun describeCod(c: BluetoothClass): String {
        val parts = mutableListOf<String>()
        majorName(c.majorDeviceClass)?.let { parts += it }
        minorName(c.deviceClass)?.let { parts += it }
        SERVICES.forEach { (mask, label) ->
            if (runCatching { c.hasService(mask) }.getOrDefault(false)) parts += label
        }
        return parts.joinToString(" - ")
            .ifBlank { "class 0x" + c.deviceClass.toString(16) }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        handler.removeCallbacks(restartRunnable)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { if (hasPermission()) adapter?.cancelDiscovery() }
    }

    companion object {
        private const val TAG = "Wave/BtClassic"

        /** Long enough for the adapter to finish tearing a cycle down, short enough to
         *  keep classic coverage effectively continuous. */
        private const val RESTART_DELAY_MS = 1_200L

        private val SERVICES = listOf(
            BluetoothClass.Service.AUDIO to "audio",
            BluetoothClass.Service.CAPTURE to "capture",
            BluetoothClass.Service.POSITIONING to "positioning",
            BluetoothClass.Service.NETWORKING to "networking",
            BluetoothClass.Service.TELEPHONY to "telephony",
            BluetoothClass.Service.OBJECT_TRANSFER to "file transfer",
            BluetoothClass.Service.INFORMATION to "information",
            BluetoothClass.Service.RENDER to "render"
        )

        fun majorName(major: Int): String? = when (major) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio/Video"
            BluetoothClass.Device.Major.COMPUTER -> "Computer"
            BluetoothClass.Device.Major.PHONE -> "Phone"
            BluetoothClass.Device.Major.NETWORKING -> "Networking"
            BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
            BluetoothClass.Device.Major.IMAGING -> "Imaging"
            BluetoothClass.Device.Major.WEARABLE -> "Wearable"
            BluetoothClass.Device.Major.TOY -> "Toy"
            BluetoothClass.Device.Major.HEALTH -> "Health"
            else -> null
        }

        /**
         * Only the minors worth naming. The full table runs past sixty entries and most of
         * them add nothing a person would act on.
         */
        fun minorName(deviceClass: Int): String? = when (deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "hands-free"
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "headphones"
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "headset"
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> "speaker"
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "car audio"
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "hi-fi"
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> "portable audio"
            BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> "set-top box"
            BluetoothClass.Device.AUDIO_VIDEO_VCR -> "recorder"
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA -> "video camera"
            BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER -> "camcorder"
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "monitor"
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> "display"
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING -> "conferencing"
            BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE -> "microphone"
            BluetoothClass.Device.COMPUTER_LAPTOP -> "laptop"
            BluetoothClass.Device.COMPUTER_DESKTOP -> "desktop"
            BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA -> "handheld"
            BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA -> "palmtop"
            BluetoothClass.Device.COMPUTER_SERVER -> "server"
            BluetoothClass.Device.COMPUTER_WEARABLE -> "wearable computer"
            BluetoothClass.Device.PHONE_SMART -> "smartphone"
            BluetoothClass.Device.PHONE_CELLULAR -> "cellular"
            BluetoothClass.Device.PHONE_CORDLESS -> "cordless"
            BluetoothClass.Device.PHONE_ISDN -> "ISDN"
            BluetoothClass.Device.PHONE_MODEM_OR_GATEWAY -> "gateway"
            BluetoothClass.Device.WEARABLE_WRIST_WATCH -> "watch"
            BluetoothClass.Device.WEARABLE_GLASSES -> "glasses"
            BluetoothClass.Device.WEARABLE_HELMET -> "helmet"
            BluetoothClass.Device.WEARABLE_JACKET -> "jacket"
            BluetoothClass.Device.WEARABLE_PAGER -> "pager"
            BluetoothClass.Device.HEALTH_PULSE_OXIMETER -> "pulse oximeter"
            BluetoothClass.Device.HEALTH_BLOOD_PRESSURE -> "blood pressure"
            BluetoothClass.Device.HEALTH_GLUCOSE -> "glucose meter"
            BluetoothClass.Device.HEALTH_THERMOMETER -> "thermometer"
            BluetoothClass.Device.HEALTH_WEIGHING -> "scale"
            BluetoothClass.Device.HEALTH_PULSE_RATE -> "heart rate"
            BluetoothClass.Device.HEALTH_DATA_DISPLAY -> "health display"
            BluetoothClass.Device.TOY_VEHICLE -> "toy vehicle"
            BluetoothClass.Device.TOY_ROBOT -> "robot"
            BluetoothClass.Device.TOY_CONTROLLER -> "controller"
            BluetoothClass.Device.TOY_GAME -> "game"
            else -> null
        }
    }
}
