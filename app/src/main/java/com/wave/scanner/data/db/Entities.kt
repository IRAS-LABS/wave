package com.wave.scanner.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Which radio a sighting came from. Drives colour, iconography and decoder path. */
enum class Band { WIFI, BLE, BT_CLASSIC, CELL, SUBGHZ }

/**
 * What Wave believes a device *is*. Everything starts UNKNOWN and is promoted by
 * [com.wave.scanner.detect] rules; the UI always shows why a promotion happened.
 */
/**
 * Room persists these by name, so entries may be APPENDED but never renamed or reordered
 * without a migration. The second row was added when naming moved off "is it a threat" and
 * onto "what is it" - a list where every domestic device says IOT is a list nobody reads.
 */
enum class DeviceClass {
    UNKNOWN, ACCESS_POINT, PHONE, TRACKER, ALPR_CAMERA, POLICE_VEHICLE,
    BODY_CAMERA, SURVEILLANCE, VEHICLE_TPMS, CELL_TOWER, IOT, WEARABLE, MEDIA,
    AUDIO, COMPUTER, PRINTER, VEHICLE, GAMING, NETWORK, MEDICAL
}

/** Shared severity ladder. Ordinal order matters — the UI sorts on it. */
enum class Threat { NONE, LOW, MEDIUM, HIGH, CRITICAL }

/**
 * One unique emitter. Keyed by a stable synthetic id because a BLE tracker rotates its
 * MAC every ~15 min: for those the id is derived from the advertisement payload instead
 * of the address, which is the whole trick behind following-detection.
 */
@Entity(
    tableName = "devices",
    indices = [Index("band"), Index("deviceClass"), Index("lastSeen"), Index("threat")]
)
data class DeviceEntity(
    @PrimaryKey val id: String,
    val band: Band,
    val address: String,
    val displayName: String? = null,
    val userLabel: String? = null,
    val vendor: String? = null,
    val vendorSource: String? = null,
    val deviceClass: DeviceClass = DeviceClass.UNKNOWN,
    val threat: Threat = Threat.NONE,
    val classReason: String? = null,
    val firstSeen: Long,
    val lastSeen: Long,
    val timesSeen: Int = 1,
    val bestRssi: Int = -127,
    val lastRssi: Int = -127,
    val frequencyKhz: Int? = null,
    val channel: Int? = null,
    val capabilities: String? = null,
    /** Raw fingerprint blob (vendor IEs, BLE manufacturer data, cell identity) as JSON. */
    val fingerprint: String? = null,
    val isWatched: Boolean = false,
    val isIgnored: Boolean = false,
    /**
     * Marked by the owner as one of their own devices.
     *
     * Deliberately not the same thing as [isIgnored], which removes a device from every
     * list. Your own phone following you is not a threat, but it is still information you
     * want: seeing your keys, your watch and both trackers answer a scan is how you confirm
     * they are actually with you. So this suppresses alerts and nothing else - the device
     * stays in the lists, keeps accumulating sightings, and gets its own screen.
     */
    val isMine: Boolean = false,
    val notes: String? = null,
    /**
     * True when this row's identity came from advertised content rather than the address,
     * because the address was random. Surfaced in the UI so a rotating device is never
     * mistaken for a stationary one with a fixed MAC.
     */
    val addressRotates: Boolean = false,
    /** How many distinct addresses this one device has been seen using. */
    val addressCount: Int = 1,
    /** Vendor-level product hint from the OUI registry. Weak: see [DeviceClass] resolution. */
    val vendorDeviceType: String? = null,
    /**
     * Protocol-level detail lines, newline-separated - GATT services offered, advertising
     * formats, decoded Apple continuity roles. Stored rather than recomputed so the detail
     * view can show what the device was actually heard saying, including from sightings
     * richer than the most recent one.
     */
    val facts: String? = null
)

/** A single timestamped sighting. This is the wardrive trail and the follow-detection input. */
@Entity(
    tableName = "observations",
    indices = [Index("deviceId"), Index("sessionId"), Index("ts")]
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val obsId: Long = 0,
    val deviceId: String,
    val sessionId: Long,
    val ts: Long,
    val rssi: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val speedMps: Float? = null,
    val bearing: Float? = null
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val label: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceMeters: Double = 0.0,
    val deviceCount: Int = 0,
    val observationCount: Int = 0
)

@Entity(tableName = "threat_events", indices = [Index("ts"), Index("severity"), Index("deviceId")])
data class ThreatEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val deviceId: String?,
    val ts: Long,
    val type: String,
    val severity: Threat,
    val title: String,
    val detail: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val acknowledged: Boolean = false
)

/**
 * Crowdsourced ALPR camera positions (DeFlock / OpenStreetMap import). This is the
 * map layer — the RF detectors are separate and always labelled as such in the UI so
 * a mapped camera is never confused with one Wave actually heard.
 */
@Entity(tableName = "alpr_cameras", indices = [Index("lat"), Index("lon")])
data class AlprCameraEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    val operator: String? = null,
    val cameraType: String? = null,
    val direction: String? = null,
    val source: String,
    val importedAt: Long
)

/** Decoded sub-GHz TPMS packet. Requires the SDR lane. */
@Entity(tableName = "tpms_readings", indices = [Index("sensorId"), Index("ts")])
data class TpmsReadingEntity(
    @PrimaryKey(autoGenerate = true) val readingId: Long = 0,
    val sensorId: String,
    val protocol: String,
    val ts: Long,
    val pressureKpa: Double? = null,
    val temperatureC: Double? = null,
    val batteryLow: Boolean? = null,
    val frequencyMhz: Double,
    val lat: Double? = null,
    val lon: Double? = null
)
