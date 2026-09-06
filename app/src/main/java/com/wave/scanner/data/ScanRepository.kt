package com.wave.scanner.data

import android.content.Context
import android.location.Location
import com.wave.scanner.data.db.Band
import com.wave.scanner.data.db.DeviceClass
import com.wave.scanner.data.db.DeviceEntity
import com.wave.scanner.data.db.WaveDatabase
import com.wave.scanner.data.db.ObservationEntity
import com.wave.scanner.data.db.SessionEntity
import com.wave.scanner.data.db.Threat
import com.wave.scanner.data.db.ThreatEventEntity
import com.wave.scanner.data.db.TpmsReadingEntity
import com.wave.scanner.detect.Classifier
import com.wave.scanner.detect.FollowDetector
import com.wave.scanner.detect.ImsiCatcherDetector
import com.wave.scanner.scan.GnssTracker
import com.wave.scanner.scan.CellFacts
import com.wave.scanner.scan.RadioObservation
import com.wave.scanner.scan.SubGhzFacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single write path from the radios into storage.
 *
 * Two things here matter more than they look. First, ingest is serialised through a mutex:
 * four scanners on four threads hitting the same device row otherwise lose timesSeen
 * counts. Second, the follow-check is rate-limited per device, because it is a quadratic
 * distance computation over that device's history and running it on every advertisement
 * from a chatty beacon would flatten the battery for no new information.
 */
class ScanRepository(context: Context) {

    private val db = WaveDatabase.get(context)
    private val devices = db.devices()
    private val observations = db.observations()
    private val sessions = db.sessions()
    private val threats = db.threats()
    val alprDao = db.alpr()
    val tpmsDao = db.tpms()

    private val classifier = Classifier.create(context)
    private val mutex = Mutex()

    /** Last follow-evaluation time per device id. */
    private val lastFollowCheck = HashMap<String, Long>()

    @Volatile var sessionId: Long = 0L
        private set

    // ---------------------------------------------------------------- sessions

    suspend fun startSession(label: String): Long {
        val existing = sessions.openSession()
        sessionId = existing?.sessionId
            ?: sessions.insert(SessionEntity(label = label, startedAt = System.currentTimeMillis()))
        return sessionId
    }

    suspend fun endSession() {
        val s = sessions.byId(sessionId) ?: return
        sessions.update(s.copy(endedAt = System.currentTimeMillis()))
        sessionId = 0L
    }

    // ------------------------------------------------------------------ ingest

    /**
     * Union of what a device has ever been heard advertising, capped and order-stable.
     *
     * Bounded because facts are keyed on protocol identifiers, and a misbehaving beacon
     * that rotates service UUIDs would otherwise grow one row's text without limit.
     */
    private fun mergeFacts(existing: String?, fresh: List<String>): String? {
        if (fresh.isEmpty()) return existing
        val out = LinkedHashSet<String>()
        existing?.lineSequence()?.filter { it.isNotBlank() }?.forEach { out.add(it) }
        fresh.forEach { out.add(it) }
        return out.take(MAX_FACTS).joinToString("\n")
    }

    /**
     * Returns a threat event when this sighting newly crossed an alerting threshold.
     *
     * @param timesSeen how many raw advertisements this call stands for. The service pools
     *   sightings into short windows before writing, so one call can represent a dozen
     *   adverts; the counter must reflect what the radio actually heard, not how often we
     *   chose to write.
     */
    suspend fun ingest(
        obs: RadioObservation,
        fix: Location?,
        timesSeen: Int = 1
    ): ThreatEventEntity? = mutex.withLock {
        val result = classifier.classify(obs)
        val id = obs.identityKey(result.payloadKey)
        val now = obs.timestamp

        val existing = devices.byId(id)
        // Identity that did not come from the address means the address is disposable.
        val rotates = obs.address != null && id != obs.address
        val newAddress = existing != null && obs.address != null && obs.address != existing.address
        val entity = if (existing == null) {
            DeviceEntity(
                id = id,
                band = obs.band,
                address = obs.address ?: id,
                displayName = result.displayName ?: obs.name,
                vendor = result.vendor,
                vendorSource = result.vendorSource,
                deviceClass = result.deviceClass,
                threat = result.threat,
                classReason = result.classReason,
                firstSeen = now,
                lastSeen = now,
                timesSeen = timesSeen,
                bestRssi = obs.rssi,
                lastRssi = obs.rssi,
                addressRotates = rotates,
                addressCount = 1,
                vendorDeviceType = result.vendorDeviceType,
                frequencyKhz = obs.frequencyMhz?.times(1000),
                channel = obs.channel,
                capabilities = obs.capabilities,
                facts = result.facts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                fingerprint = result.fingerprint
            )
        } else {
            existing.copy(
                // Never downgrade a classification. A device that once identified itself
                // as an Axon does not become innocent because a later beacon was sparser.
                displayName = result.displayName ?: obs.name ?: existing.displayName,
                vendor = result.vendor ?: existing.vendor,
                vendorSource = result.vendorSource ?: existing.vendorSource,
                deviceClass = if (existing.deviceClass == DeviceClass.UNKNOWN)
                    result.deviceClass else existing.deviceClass,
                threat = maxOf(existing.threat, result.threat),
                // The reason has to travel with the classification it explains. Keeping the
                // first reason forever meant a device that started as an anonymous beacon
                // and later announced a Heart Rate service still read "silent beacon".
                classReason = if (existing.deviceClass == DeviceClass.UNKNOWN)
                    result.classReason else existing.classReason ?: result.classReason,
                // Union, not replacement: a device does not advertise everything it can do
                // in every packet, so facts accumulate across sightings.
                facts = mergeFacts(existing.facts, result.facts),
                lastSeen = now,
                timesSeen = existing.timesSeen + timesSeen,
                bestRssi = maxOf(existing.bestRssi, obs.rssi),
                lastRssi = obs.rssi,
                capabilities = obs.capabilities ?: existing.capabilities,
                addressRotates = existing.addressRotates || rotates,
                // A device keyed on its payload will present a different address after each
                // rotation. Counting them is what turns "this MAC" into "this device, which
                // has worn eleven addresses" - the honest description of a rotating emitter.
                addressCount = if (newAddress) existing.addressCount + 1 else existing.addressCount,
                address = obs.address ?: existing.address,
                vendorDeviceType = result.vendorDeviceType ?: existing.vendorDeviceType
            )
        }
        devices.upsert(entity)

        observations.insert(
            ObservationEntity(
                deviceId = id,
                sessionId = sessionId,
                ts = now,
                rssi = obs.rssi,
                lat = fix?.latitude,
                lon = fix?.longitude,
                altitude = fix?.altitude,
                accuracy = fix?.accuracy,
                speedMps = fix?.speed,
                bearing = fix?.bearing
            )
        )

        obs.subGhz?.let { recordTpms(it, now, fix) }
        obs.cell?.let { cellAnomaly(it, entity.id, fix)?.let { e -> return@withLock e } }

        return@withLock evaluateThreat(entity, existing == null, fix)
    }

    // -------------------------------------------------------------------- tpms

    /**
     * Sub-GHz sightings are kept twice on purpose: once as a device row so a sensor that
     * keeps reappearing shows up alongside everything else, and once here with the pressure
     * and temperature, which are the fields that tell you whether two sightings are really
     * the same wheel on the same car.
     */
    private suspend fun recordTpms(facts: SubGhzFacts, ts: Long, fix: Location?) {
        tpmsDao.insert(
            TpmsReadingEntity(
                sensorId = facts.sensorId,
                protocol = facts.protocol,
                ts = ts,
                pressureKpa = facts.pressureKpa,
                temperatureC = facts.temperatureC,
                batteryLow = facts.batteryLow,
                frequencyMhz = facts.frequencyMhz,
                lat = fix?.latitude,
                lon = fix?.longitude
            )
        )
    }

    // -------------------------------------------------------------------- cell

    /** Cells seen before, so an unfamiliar serving cell can be recognised as unfamiliar. */
    private val knownCells = HashSet<Long>()
    private val recentTechnologies = ArrayDeque<String>()
    private var lastCellCheck = 0L

    /**
     * Cell anomalies are scored at most once a minute. The tower list barely changes
     * between polls, and re-alerting on every poll would bury the one that mattered.
     */
    private suspend fun cellAnomaly(
        facts: CellFacts,
        deviceId: String,
        fix: Location?
    ): ThreatEventEntity? {
        recentTechnologies.addLast(facts.technology)
        while (recentTechnologies.size > 40) recentTechnologies.removeFirst()

        val now = System.currentTimeMillis()
        if (now - lastCellCheck < CELL_CHECK_INTERVAL_MS) {
            facts.cid?.let { knownCells.add(it) }
            return null
        }
        lastCellCheck = now

        val anomalies = ImsiCatcherDetector.evaluate(
            current = facts,
            knownCells = knownCells,
            recentTechnologies = recentTechnologies.toList()
        )
        facts.cid?.let { knownCells.add(it) }

        val worst = anomalies.maxByOrNull { it.severity.ordinal } ?: return null
        if (worst.severity < Threat.MEDIUM) return null

        return emit(
            deviceId = deviceId,
            type = "cell_" + worst.type,
            severity = worst.severity,
            title = worst.title,
            detail = worst.detail,
            fix = fix,
            cooldownMs = CELL_ALERT_COOLDOWN_MS
        )
    }

    // ----------------------------------------------------------------- threats

    private suspend fun evaluateThreat(
        device: DeviceEntity,
        firstSighting: Boolean,
        fix: Location?
    ): ThreatEventEntity? {
        if (device.isIgnored) return null

        // A device the owner has claimed cannot be following them in any sense they care
        // about - it is in their pocket. Its sightings still land, so the roll call on the
        // My Devices screen stays live; what is suppressed is only the alarm.
        if (device.isMine) return null

        // Anything at HIGH or above is worth announcing the moment it is first identified.
        if (firstSighting && device.threat >= Threat.HIGH) {
            return emit(
                deviceId = device.id,
                type = "identified",
                severity = device.threat,
                title = device.deviceClass.readable() + " detected",
                detail = device.classReason ?: "Matched a surveillance signature",
                fix = fix,
                cooldownMs = 0
            )
        }

        val now = System.currentTimeMillis()
        val last = lastFollowCheck[device.id] ?: 0L
        if (now - last < FOLLOW_CHECK_INTERVAL_MS) return null
        lastFollowCheck[device.id] = now

        val history = observations.locatedForDevice(device.id, now - FOLLOW_WINDOW_MS)
        if (history.size < 3) return null

        val verdict = FollowDetector.evaluate(history, device.deviceClass)
        if (!verdict.isFollowing) return null

        return emit(
            deviceId = device.id,
            type = "following",
            severity = verdict.threat,
            title = "Device travelling with you",
            detail = (device.vendor ?: device.address) + ": " + verdict.explanation +
                " Confidence " + verdict.score + "/100.",
            fix = fix,
            cooldownMs = FOLLOW_ALERT_COOLDOWN_MS
        )
    }

    private suspend fun emit(
        deviceId: String,
        type: String,
        severity: Threat,
        title: String,
        detail: String,
        fix: Location?,
        cooldownMs: Long
    ): ThreatEventEntity? {
        val now = System.currentTimeMillis()
        if (cooldownMs > 0 &&
            threats.recentCountFor(deviceId, type, now - cooldownMs) > 0
        ) return null

        val event = ThreatEventEntity(
            deviceId = deviceId,
            ts = now,
            type = type,
            severity = severity,
            title = title,
            detail = detail,
            lat = fix?.latitude,
            lon = fix?.longitude
        )
        val rowId = threats.insert(event)
        return event.copy(eventId = rowId)
    }

    // ------------------------------------------------------------------ reads

    fun recentDevices(limit: Int = 500): Flow<List<DeviceEntity>> = devices.recent(limit)
    fun devicesByBand(band: Band): Flow<List<DeviceEntity>> = devices.byBand(band)
    fun devicesByMinThreat(min: Threat): Flow<List<DeviceEntity>> = devices.byMinThreat(min)
    fun watchedDevices(): Flow<List<DeviceEntity>> = devices.watched()

    fun myDevices(): Flow<List<DeviceEntity>> = devices.mine()
    fun searchDevices(q: String): Flow<List<DeviceEntity>> = devices.search(q)
    fun deviceCount(): Flow<Int> = devices.count()
    fun observationCount(): Flow<Int> = observations.count()
    fun recentThreats(limit: Int = 300) = threats.recent(limit)
    fun unacknowledgedThreats() = threats.unacknowledged()
    fun unackCount(): Flow<Int> = threats.unackCount()
    fun alprCount(): Flow<Int> = alprDao.count()

    suspend fun device(id: String) = devices.byId(id)
    suspend fun observationsFor(id: String) = observations.forDevice(id)
    suspend fun locatedSince(since: Long) = observations.locatedSince(since)
    suspend fun setWatched(id: String, v: Boolean) = devices.setWatched(id, v)
    suspend fun setIgnored(id: String, v: Boolean) = devices.setIgnored(id, v)
    suspend fun setMine(id: String, v: Boolean) = devices.setMine(id, v)
    suspend fun setLabel(id: String, label: String?) = devices.setLabel(id, label)
    suspend fun acknowledge(id: Long) = threats.acknowledge(id)
    suspend fun acknowledgeAll() = threats.acknowledgeAll()

    suspend fun evaluateFollow(deviceId: String): FollowDetector.Verdict? {
        val d = devices.byId(deviceId) ?: return null
        val history = observations.locatedForDevice(deviceId, 0L)
        return FollowDetector.evaluate(history, d.deviceClass)
    }

    /** Bounding-box prefilter then a true radius test, since the box is wider at the corners. */
    suspend fun nearbyAlpr(fix: Location, radiusMeters: Double) =
        alprDao.inBox(
            fix.latitude - radiusMeters / 111_320.0,
            fix.latitude + radiusMeters / 111_320.0,
            fix.longitude - radiusMeters / 111_320.0,
            fix.longitude + radiusMeters / 111_320.0
        ).filter {
            GnssTracker.distanceMeters(fix.latitude, fix.longitude, it.lat, it.lon) <= radiusMeters
        }

    private fun DeviceClass.readable(): String = name.lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private companion object {
        private const val MAX_FACTS = 12
        const val FOLLOW_CHECK_INTERVAL_MS = 60_000L
        const val FOLLOW_WINDOW_MS = 4 * 60 * 60 * 1000L
        const val FOLLOW_ALERT_COOLDOWN_MS = 15 * 60 * 1000L
        const val CELL_CHECK_INTERVAL_MS = 60_000L
        const val CELL_ALERT_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
