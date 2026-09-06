package com.wave.scanner.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wave.scanner.MainActivity
import com.wave.scanner.R
import com.wave.scanner.data.ScanRepository
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.ThreatEventEntity
import com.wave.scanner.sdr.SdrScanner
import com.wave.scanner.ui.AlertPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns every radio for the duration of a wardrive.
 *
 * Scanning has to survive the screen going off in a pocket or a car mount, which on modern
 * Android means a foreground service or nothing. Observations arrive on several callback
 * threads and are funnelled into one unbounded channel, so a burst of BLE advertisements
 * in a crowded place never blocks the Bluetooth stack waiting on a database write.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Sightings waiting to be written, bounded and lossy on purpose.
     *
     * This was Channel.UNLIMITED and it was the retention behind an OutOfMemoryError at the
     * 256 MB heap limit. Producers are radio callbacks that fire far faster than a database
     * write completes, so an unbounded queue grows without limit, and each queued
     * RadioObservation pins its vendor-IE byte arrays - the heap filled with a backlog that
     * could never be drained.
     *
     * Dropping the oldest is the right loss, because a stale sighting of a device is
     * superseded by a newer one of the same device: the queue only ever falls behind when
     * the same emitters are being re-heard, so what gets discarded is duplicate information.
     */
    private val inbox = Channel<Pair<RadioObservation, Long>>(
        capacity = INBOX_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private lateinit var repo: ScanRepository
    private lateinit var gnss: GnssTracker
    private var sdr: SdrScanner? = null

    // Held individually as well as in the list below. bump() needs each scanner's own
    // health field, and reaching for it by list index - scanners[0] as WifiScanner - broke
    // the moment a fourth radio was added: the classic-Bluetooth lane shifted every index
    // after it, and the cast failed at runtime rather than at compile time.
    private lateinit var wifi: WifiScanner
    private lateinit var ble: BleScanner
    private lateinit var btClassic: BtClassicScanner
    private lateinit var cell: CellScanner
    private lateinit var scanners: List<Scanner>

    /** Live while a scan is running. See [watchBluetoothState]. */
    private var btStateReceiver: BroadcastReceiver? = null

    /**
     * State lives on the companion rather than the instance so the UI can observe it
     * before the service has ever been started, and keeps observing across a restart.
     */
    private val _state get() = liveState
    val state: StateFlow<ScanState> = liveState.asStateFlow()

    data class ScanState(
        val running: Boolean = false,
        val wifiSeen: Int = 0,
        val bleSeen: Int = 0,
        val btClassicSeen: Int = 0,
        val cellSeen: Int = 0,
        val subGhzSeen: Int = 0,
        val hasFix: Boolean = false,
        val wifiThrottled: Boolean = false,
        val bleError: String? = null,
        val btClassicError: String? = null,
        /** Completed inquiry cycles. A classic lane that never cycles is a stuck radio. */
        val btClassicCycles: Int = 0,
        val sdrConnected: Boolean = false,
        /** Tuner chip reported by the dongle, e.g. "R820T". Null until one is attached. */
        val sdrTuner: String? = null,
        /** What the SDR reader is doing right now - which band it is sitting on. */
        val sdrStatus: String? = null,
        val startedAt: Long = 0L
    ) {
        val total: Int get() = wifiSeen + bleSeen + btClassicSeen + cellSeen + subGhzSeen
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = ScanRepository(this)
        gnss = GnssTracker(this)
        // The service can be revived by START_STICKY without MainActivity ever running,
        // so it loads the alert settings itself rather than assuming the UI did.
        AlertPrefs.load(this)
        wifi = WifiScanner(this)
        ble = BleScanner(this)
        btClassic = BtClassicScanner(this)
        cell = CellScanner(this)
        scanners = listOf(wifi, ble, btClassic, cell)
        createChannels()
        INSTANCE = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopScanning(); stopSelf(); return START_NOT_STICKY }
        }
        startForeground(NOTIF_ID, buildNotification(_state.value))
        startScanning()
        // Restart if the system kills us mid-drive; a wardrive with a hole in it is worse
        // than one that ends cleanly.
        return START_STICKY
    }

    override fun onDestroy() {
        stopScanning()
        scope.cancel()
        INSTANCE = null
        super.onDestroy()
    }

    // --------------------------------------------------------------- lifecycle

    private fun startScanning() {
        if (_state.value.running) return

        scope.launch { repo.startSession("Session " + System.currentTimeMillis()) }

        gnss.start { _state.value = _state.value.copy(hasFix = gnss.hasUsableFix()) }

        scanners.forEach { scanner ->
            if (scanner.isAvailable()) {
                scanner.start { obs -> inbox.trySend(obs to System.currentTimeMillis()) }
            }
        }

        // A lane that never starts also never reaches bump(), so its reason for being
        // silent would never be shown. Capture it here instead, at the one moment it is
        // known: an unexplained empty band is exactly the failure this app must not have.
        refreshBluetoothStatus()
        watchBluetoothState()

        // The classic lane's health - cycles completed, why it stopped - lives in fields
        // that only bump() copies into the UI, and bump() runs only when that lane sees
        // something. So the one situation where the number matters, a lane seeing nothing,
        // is the one situation where it was never updated: the Radio screen showed
        // "0 inquiry cycles" for the whole session no matter what the radio was doing, and
        // the alert card called it wedged on that basis. Poll it instead, so what is shown
        // is what the scanner actually holds.
        scope.launch {
            while (isActive && _state.value.running) {
                delay(BT_STATUS_POLL_MS)
                refreshBluetoothStatus()
            }
        }

        // Probed on the IO dispatcher, never here. isAvailable() opens a TCP socket to
        // rtl_tcp on 127.0.0.1:1234, and onStartCommand runs on the main thread, so doing
        // this inline threw NetworkOnMainThreadException the instant Scan was pressed --
        // on every device, with or without an SDR attached. RtlTcpClient.connect() catches
        // IOException, and NetworkOnMainThreadException is a RuntimeException, so nothing
        // caught it and the app died. The rest of the scan does not depend on the SDR, so
        // there is no reason to make anyone wait on that probe either.
        scope.launch {
            val s = SdrScanner(this@ScanService)
            val ok = s.isAvailable()
            // The probe takes up to 800 ms, which is long enough for a fast stop to land
            // first. Without this check a cancelled scan could still leave a live SDR
            // reader running with nothing to shut it down.
            if (!ok || !_state.value.running) {
                runCatching { s.stop() }
                return@launch
            }
            s.start { obs -> inbox.trySend(obs to System.currentTimeMillis()) }
            sdr = s
            _state.value = _state.value.copy(
                sdrConnected = true,
                sdrTuner = s.dongle?.tunerType,
                sdrStatus = s.status
            )
            // The reader retunes across bands as it runs, so the status line has to be
            // polled to stay true rather than captured once at connection.
            while (isActive && _state.value.running) {
                delay(SDR_STATUS_POLL_MS)
                _state.value = _state.value.copy(sdrStatus = s.status)
            }
        }

        _state.value = _state.value.copy(running = true, startedAt = System.currentTimeMillis())
        consume()
    }

    private fun stopScanning() {
        btStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        btStateReceiver = null
        scanners.forEach { runCatching { it.stop() } }
        sdr?.let { runCatching { it.stop() } }
        gnss.stop()
        scope.launch { runCatching { repo.endSession() } }
        _state.value = _state.value.copy(running = false)
    }

    /**
     * Brings the two Bluetooth lanes back when the adapter is switched on mid-session.
     *
     * Scanners are probed once, at the top of [startScanning]. That is fine for Wi-Fi and
     * cell, whose radios are either on or the phone is a brick, but Bluetooth is routinely
     * off when a scan is started and turned on a minute later. Without this both LE and
     * classic stayed dead for the rest of the session, and the diagnostics kept reporting
     * "Bluetooth is off" long after it was on - a stale explanation is worse than none,
     * because it is believed.
     *
     * ACTION_STATE_CHANGED is a protected system broadcast, so nothing but the platform can
     * deliver it and the receiver is registered not-exported.
     */
    private fun watchBluetoothState() {
        if (btStateReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        if (!_state.value.running) return
                        // start() on both of these is a no-op when already running, so a
                        // duplicate STATE_ON cannot double-register anything.
                        listOf(ble, btClassic).forEach { s ->
                            if (s.isAvailable()) {
                                s.start { obs ->
                                    inbox.trySend(obs to System.currentTimeMillis())
                                }
                            }
                        }
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        runCatching { ble.stop() }
                        runCatching { btClassic.stop() }
                        // Re-probe purely for the side effect: it is what rewrites
                        // lastError back to "Bluetooth is off".
                        ble.isAvailable()
                        btClassic.isAvailable()
                    }
                }
                refreshBluetoothStatus()
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
        btStateReceiver = r
    }

    /** Copies whatever the two Bluetooth lanes currently think of themselves into the UI. */
    private fun refreshBluetoothStatus() {
        _state.value = _state.value.copy(
            bleError = ble.lastError,
            btClassicError = btClassic.lastError,
            btClassicCycles = btClassic.cycles
        )
    }

    /**
     * One sighting held back for coalescing, plus how many raw adverts it stands for.
     */
    private class Pending(var obs: RadioObservation, var count: Int, var bestRssi: Int)

    /**
     * Single consumer so ingest stays ordered and the repository mutex is never contended
     * by more than one coroutine.
     *
     * Sightings are coalesced into short windows before they reach the database. A BLE
     * device advertises several times a second by design - six real devices in one room
     * produced roughly twenty-four callbacks per second in testing - and writing a
     * row per advert meant the device table was rewritten continuously, every write woke
     * every Room query, and the list re-sorted under the user's finger. Nothing is dropped:
     * the count of collapsed adverts still lands in timesSeen and the strongest RSSI in the
     * window still wins, so the numbers are identical. Only the write rate changes.
     *
     * The window is deliberately short. This is a live scanner and it has to feel like one;
     * a second and a half is below the threshold where a person reads the display as
     * lagging, while cutting writes by more than an order of magnitude.
     */
    private fun consume() = scope.launch {
        val pending = LinkedHashMap<String, Pending>()
        var lastFlush = System.currentTimeMillis()

        while (isActive) {
            // Wait for something, then sweep up everything else already queued behind it.
            val first = inbox.receiveCatching().getOrNull() ?: break
            merge(pending, first.first)
            while (true) {
                val more = inbox.tryReceive().getOrNull() ?: break
                merge(pending, more.first)
            }

            val now = System.currentTimeMillis()
            if (now - lastFlush < FLUSH_INTERVAL_MS) continue
            lastFlush = now
            flush(pending)
        }
        flush(pending)
    }

    private fun merge(pending: MutableMap<String, Pending>, obs: RadioObservation) {
        // Cheap pre-identity key. The repository still does the real identity resolution;
        // this only has to be good enough to collapse repeats of the same emitter.
        val key = obs.band.name + '|' + (obs.address ?: "") + '|' + (obs.name ?: "")
        val existing = pending[key]
        if (existing == null) {
            pending[key] = Pending(obs, 1, obs.rssi)
        } else {
            existing.count++
            existing.bestRssi = maxOf(existing.bestRssi, obs.rssi)
            // Keep the newest advert: its payload is the current one, and a device that
            // starts advertising a name mid-window should be recorded with it.
            existing.obs = obs
        }
    }

    private suspend fun flush(pending: MutableMap<String, Pending>) {
        if (pending.isEmpty()) return
        val fix = if (gnss.hasUsableFix()) gnss.last else null
        for (p in pending.values) {
            val event = runCatching {
                repo.ingest(p.obs.copy(rssi = p.bestRssi), fix, timesSeen = p.count)
            }.getOrNull()
            bump(p.obs.band, p.count)
            event?.let { notifyThreat(it) }
        }
        pending.clear()
        manager().notify(NOTIF_ID, buildNotification(_state.value))
    }

    private fun bump(band: Band, times: Int) {
        val s = _state.value
        _state.value = when (band) {
            Band.WIFI -> s.copy(
                wifiSeen = s.wifiSeen + times,
                wifiThrottled = wifi.throttleLikely
            )
            Band.BLE -> s.copy(
                bleSeen = s.bleSeen + times,
                bleError = ble.lastError
            )
            Band.BT_CLASSIC -> s.copy(
                btClassicSeen = s.btClassicSeen + times,
                btClassicError = btClassic.lastError,
                btClassicCycles = btClassic.cycles
            )
            Band.CELL -> s.copy(cellSeen = s.cellSeen + times)
            Band.SUBGHZ -> s.copy(subGhzSeen = s.subGhzSeen + times, sdrConnected = true)
        }
    }

    // ------------------------------------------------------------ notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = manager()
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, "Scanning", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Ongoing wardrive session" }
        )
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Threat alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Trackers, surveillance devices and follow detection"
                    enableVibration(true)
                }
        )
    }

    private fun buildNotification(s: ScanState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val detail = buildString {
            append(s.wifiSeen).append(" wifi  ")
            append(s.bleSeen).append(" ble  ")
            if (s.btClassicSeen > 0) append(s.btClassicSeen).append(" bt  ")
            append(s.cellSeen).append(" cell")
            if (s.subGhzSeen > 0) append("  ").append(s.subGhzSeen).append(" rf")
            if (!s.hasFix) append("   no GPS fix")
        }

        return NotificationCompat.Builder(this, CHANNEL_SCAN)
            .setContentTitle("Wave scanning")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_scan)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_scan, "Stop", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Posts an alert only if the user has asked for that severity and that category.
     *
     * A suppressed alert is still written to the database and still appears on the Alerts
     * tab. Silencing a notification is not the same as deciding the event did not happen,
     * and a tool like this must never quietly discard a detection because of a preference.
     */
    private fun notifyThreat(event: ThreatEventEntity) {
        if (!AlertPrefs.current.value.allows(event)) return
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_THREAT_ID, event.eventId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle(event.title)
            .setContentText(event.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            .setSmallIcon(R.drawable.ic_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager().notify(event.eventId.toInt() + 1000, n)
    }

    private fun manager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_SCAN = "wave.scan"
        const val CHANNEL_ALERT = "wave.alert"
        const val ACTION_STOP = "com.wave.scanner.STOP"
        const val EXTRA_THREAT_ID = "threatId"
        private const val NOTIF_ID = 42

        /**
         * How long sightings are pooled before being written. Short enough that the display
         * still reads as live, long enough that a chatty beacon cannot dominate the DB.
         */
        private const val FLUSH_INTERVAL_MS = 1_500L

        /**
         * Deep enough to absorb a burst from a dense environment, shallow enough that the
         * backlog can never dominate the heap.
         */
        private const val INBOX_CAPACITY = 512

        /** How often the radio status line is refreshed while an SDR is attached. */
        private const val SDR_STATUS_POLL_MS = 1_000L

        /** How often the Bluetooth lanes' self-reported health is copied into the UI. */
        private const val BT_STATUS_POLL_MS = 2_000L

        @Volatile private var INSTANCE: ScanService? = null

        /** Observable from anywhere, including before the service exists. */
        val liveState = MutableStateFlow(ScanState())

        fun start(context: Context) {
            val i = Intent(context, ScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScanService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
