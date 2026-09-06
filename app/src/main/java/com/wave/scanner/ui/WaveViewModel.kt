package com.wave.scanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wave.scanner.data.ScanRepository
import com.wave.scanner.data.alpr.AlprImporter
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.DeviceEntity
import com.wave.scanner.data.db.DeviceClass
import com.wave.scanner.data.db.ObservationEntity
import com.wave.scanner.data.db.Threat
import com.wave.scanner.data.db.ThreatEventEntity
import com.wave.scanner.data.export.Exporters
import com.wave.scanner.data.oui.BtSigRepository
import com.wave.scanner.data.oui.OuiRepository
import com.wave.scanner.data.oui.RfProtocolCatalog
import com.wave.scanner.detect.FollowDetector
import com.wave.scanner.scan.GnssTracker
import com.wave.scanner.scan.ScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.wave.scanner.detect.Ranging
import com.wave.scanner.detect.rangeBand
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single view model for the whole app.
 *
 * The device list is deliberately assembled with flatMapLatest over the active filter
 * rather than by filtering an in-memory list: a long drive produces tens of thousands of
 * rows, and paging that through Compose recomposition is the difference between a list
 * that scrolls and one that stutters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaveViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScanRepository(app)
    private val oui = OuiRepository.get(app)
    private val btSig = BtSigRepository.get(app)
    private val rf = RfProtocolCatalog.get(app)

    // ------------------------------------------------------------------ filter

    data class Filter(
        val band: Band? = null,
        val minThreat: Threat? = null,
        val query: String = "",
        val watchedOnly: Boolean = false,
        val mineOnly: Boolean = false
    )

    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    fun setBand(b: Band?) { _filter.value = _filter.value.copy(band = b) }
    fun setMinThreat(t: Threat?) { _filter.value = _filter.value.copy(minThreat = t) }
    fun setQuery(q: String) { _filter.value = _filter.value.copy(query = q) }
    fun toggleWatchedOnly() {
        _filter.value = _filter.value.copy(
            watchedOnly = !_filter.value.watchedOnly, mineOnly = false
        )
    }

    // Mine and Watched are opposite intentions - "ignore this, it's me" versus "tell me
    // more about this" - so selecting one clears the other rather than intersecting to an
    // empty list the user has to work out how to escape from.
    fun toggleMineOnly() {
        _filter.value = _filter.value.copy(
            mineOnly = !_filter.value.mineOnly, watchedOnly = false
        )
    }

    val devices: StateFlow<List<DeviceEntity>> = _filter
        .flatMapLatest { f ->
            when {
                f.mineOnly -> repo.myDevices()
                f.watchedOnly -> repo.watchedDevices()
                f.query.isNotBlank() -> repo.searchDevices(f.query)
                f.minThreat != null -> repo.devicesByMinThreat(f.minThreat)
                f.band != null -> repo.devicesByBand(f.band)
                else -> repo.recentDevices()
            }
        }
        // Room re-emits the whole list on every write to the table. Even with writes
        // coalesced in the service, a dense environment still produces emissions faster
        // than a person can read, and each one recomposes several hundred rows. Sampling
        // caps the repaint rate at a readable one without holding anything back: the list
        // still shows live state, it just stops redrawing between blinks.
        .sample(UI_REFRESH_MS)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------ counts

    data class Totals(
        val devices: Int = 0,
        val observations: Int = 0,
        val unackThreats: Int = 0,
        val alprCameras: Int = 0
    )

    val totals: StateFlow<Totals> = combine(
        repo.deviceCount(),
        repo.observationCount(),
        repo.unackCount(),
        repo.alprCount()
    ) { d, o, t, a -> Totals(d, o, t, a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Totals())

    val threats: StateFlow<List<ThreatEventEntity>> = repo.recentThreats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The owner's own devices, for the roll-call screen.
     *
     * Sampled like the main list so a pocketful of trackers re-announcing themselves cannot
     * make the roll call strobe.
     */
    val myDevices: StateFlow<List<DeviceEntity>> = repo.myDevices()
        .sample(UI_REFRESH_MS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scanState: StateFlow<ScanService.ScanState> = ScanService.liveState

    // -------------------------------------------------------------- selection

    data class DetailState(
        val device: DeviceEntity? = null,
        val observations: List<ObservationEntity> = emptyList(),
        val follow: FollowDetector.Verdict? = null,
        val vendorDetail: OuiRepository.Vendor? = null,
        /** Best guess at where it is, with its own honesty attached. Null without GPS. */
        val fix: Ranging.Fix? = null,
        /** Estimated distance right now, from the most recent sighting. */
        val distanceMeters: Double? = null,
        /** Whether the gap has been closing or opening across this device's history. */
        val trend: Ranging.Trend = Ranging.Trend.UNKNOWN
    )

    private val _detail = MutableStateFlow(DetailState())
    val detail: StateFlow<DetailState> = _detail.asStateFlow()

    fun openDevice(id: String) = viewModelScope.launch {
        val d = repo.device(id)
        val obs = repo.observationsFor(id)
        val verdict = repo.evaluateFollow(id)
        val vendor = d?.address?.let { withContext(Dispatchers.IO) { oui.lookup(it) } }
        // Estimation runs off the main thread: a device with thousands of sightings makes
        // distinctPositions an O(n*k) scan, and that is not a thing to do during a frame.
        val (fix, distance, trend) = withContext(Dispatchers.Default) {
            if (d == null) Triple(null, null, Ranging.Trend.UNKNOWN)
            else {
                val band = d.rangeBand()
                Triple(
                    Ranging.estimatePosition(obs, band),
                    Ranging.distanceMeters(d.lastRssi, band),
                    Ranging.trend(obs)
                )
            }
        }
        _detail.value = DetailState(d, obs, verdict, vendor, fix, distance, trend)
    }

    fun setWatched(id: String, v: Boolean) = viewModelScope.launch {
        repo.setWatched(id, v)
        openDevice(id)
    }

    fun setIgnored(id: String, v: Boolean) = viewModelScope.launch { repo.setIgnored(id, v) }

    fun setMine(id: String, v: Boolean) = viewModelScope.launch {
        repo.setMine(id, v)
        openDevice(id)
    }

    fun setLabel(id: String, label: String?) = viewModelScope.launch {
        repo.setLabel(id, label?.ifBlank { null })
        openDevice(id)
    }

    fun acknowledge(eventId: Long) = viewModelScope.launch { repo.acknowledge(eventId) }
    fun acknowledgeAll() = viewModelScope.launch { repo.acknowledgeAll() }

    // ----------------------------------------------------------------- status

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun clearStatus() { _status.value = null }
    private fun say(message: String) { _status.value = message }

    // ------------------------------------------------------------------ alpr

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /**
     * Imports mapped cameras around the current position. Radius is capped because
     * Overpass refuses very large areas and a refusal reads to the user as a bug.
     */
    fun importAlprAround(lat: Double, lon: Double, radiusKm: Double = 25.0) =
        viewModelScope.launch {
            if (_importing.value) return@launch
            _importing.value = true
            try {
                val (cameras, result) = AlprImporter.fetchAround(lat, lon, radiusKm.coerceAtMost(60.0))
                if (cameras.isNotEmpty()) repo.alprDao.upsertAll(cameras)
                say(
                    if (result.error != null) "Camera import failed: " + result.error
                    else "Imported " + result.imported + " mapped ALPR cameras"
                )
            } finally {
                _importing.value = false
            }
        }

    // ------------------------------------------------------------------- map

    /**
     * Everything with a position, flattened for the plot.
     *
     * Held as one immutable snapshot rather than a live flow: the canvas renders the whole
     * set every frame, and re-projecting tens of thousands of points on each new sighting
     * would make the map the most expensive thing in the app.
     */
    data class MapPoint(
        val lat: Double,
        val lon: Double,
        val threat: Threat,
        val deviceId: String,
        val deviceClass: DeviceClass,
        /**
         * Radius in metres of the area the device is probably inside.
         *
         * Drawn as a ring rather than folded away, because a dot on a map is a claim of
         * precision and this estimate does not have any. The ring is the honest shape of
         * the answer: somewhere in here, probably.
         */
        val radiusMeters: Double = 0.0,
        /** True when several vantage points went into the estimate rather than one. */
        val triangulated: Boolean = false
    )

    data class MapState(
        val points: List<MapPoint> = emptyList(),
        val cameras: List<Pair<Double, Double>> = emptyList(),
        val me: Pair<Double, Double>? = null,
        val loading: Boolean = false
    )

    private val _map = MutableStateFlow(MapState())
    val mapState: StateFlow<MapState> = _map.asStateFlow()

    fun refreshMap(windowHours: Int = 24, me: Pair<Double, Double>? = null) = viewModelScope.launch {
        _map.value = _map.value.copy(loading = true)
        val since = System.currentTimeMillis() - windowHours * 3600_000L
        val snapshot = withContext(Dispatchers.IO) {
            val obs = repo.locatedSince(since)
            // One point per device, at its strongest sighting: a plot of every raw
            // observation is a solid smear along the route and shows nothing.
            val byDevice = obs.groupBy { it.deviceId }
            val meta = byDevice.keys.mapNotNull { repo.device(it) }.associateBy { it.id }
            val points = byDevice.mapNotNull { (id, list) ->
                val d = meta[id] ?: return@mapNotNull null
                // Where the DEVICE probably is, not where the phone was standing when it
                // heard it loudest. Those are the same point only if you walked right up to
                // it; the rest of the time the old plot was drawing your own route back at
                // you and calling it a device location.
                val fix = Ranging.estimatePosition(list, d.rangeBand())
                if (fix != null) {
                    MapPoint(
                        fix.lat, fix.lon, d.threat, id, d.deviceClass,
                        radiusMeters = fix.radiusMeters,
                        triangulated = fix.samples >= 3
                    )
                } else {
                    // No usable estimate - fall back to the loudest sighting so the device
                    // still appears somewhere, with a ring wide enough to say so.
                    val best = list.maxByOrNull { it.rssi } ?: return@mapNotNull null
                    val la = best.lat ?: return@mapNotNull null
                    val lo = best.lon ?: return@mapNotNull null
                    MapPoint(la, lo, d.threat, id, d.deviceClass, radiusMeters = 100.0)
                }
            }

            val cams = if (points.isEmpty()) emptyList() else {
                val lats = points.map { it.lat }
                val lons = points.map { it.lon }
                repo.alprDao.inBox(lats.min(), lats.max(), lons.min(), lons.max())
                    .map { it.lat to it.lon }
            }
            points to cams
        }
        _map.value = MapState(snapshot.first, snapshot.second, me, loading = false)
    }

    suspend fun camerasNear(lat: Double, lon: Double, radiusM: Double) =
        repo.alprDao.inBox(
            lat - radiusM / 111_320.0, lat + radiusM / 111_320.0,
            lon - radiusM / 111_320.0, lon + radiusM / 111_320.0
        ).filter { GnssTracker.distanceMeters(lat, lon, it.lat, it.lon) <= radiusM }

    // ---------------------------------------------------------------- exports

    fun export(format: ExportFormat) = viewModelScope.launch {
        val app = getApplication<Application>()
        val list = devices.value
        if (list.isEmpty()) { say("Nothing to export yet"); return@launch }

        val file = withContext(Dispatchers.IO) {
            val tracks = list.associate { it.id to repo.observationsFor(it.id) }
            when (format) {
                ExportFormat.WIGLE_CSV -> {
                    val best = tracks.mapNotNull { (id, obs) ->
                        obs.filter { it.lat != null }.maxByOrNull { it.rssi }?.let { id to it }
                    }.toMap()
                    Exporters.wigleCsv(app, list, best)
                }
                ExportFormat.FULL_CSV -> Exporters.fullCsv(app, list)
                ExportFormat.KML -> Exporters.kml(app, list, tracks)
                ExportFormat.JSON -> Exporters.json(app, list, tracks)
            }
        }
        say("Wrote " + file.name + " to " + file.parentFile?.name)
    }

    enum class ExportFormat(val label: String, val detail: String) {
        WIGLE_CSV("WiGLE CSV", "Standard wardrive interchange format"),
        FULL_CSV("Full CSV", "Every field including classification and reason"),
        KML("KML", "Google Earth, with per-device tracks"),
        JSON("JSON", "Lossless, including all observations")
    }

    // -------------------------------------------------------------- reference

    val ouiCount: Int by lazy { oui.rowCount() }
    val btSigCount: Int get() = btSig.size
    val rfProtocolCount: Int get() = rf.protocols.size
    val tpmsProtocolCount: Int get() = rf.tpms.size
    val tpmsManufacturers: List<String> by lazy { rf.tpmsManufacturers() }

    fun vendorSearch(term: String) = oui.searchManufacturer(term)

    /** Class breakdown for the scan dashboard. */
    val classCounts: StateFlow<Map<DeviceClass, Int>> = repo.recentDevices(2000)
        .map { list -> list.groupingBy { it.deviceClass }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private companion object {
        /** Fast enough to read as live, slow enough to read at all. */
        const val UI_REFRESH_MS = 400L
    }

}
