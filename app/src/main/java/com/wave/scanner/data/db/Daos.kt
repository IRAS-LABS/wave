package com.wave.scanner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Device lists order by firstSeen, not lastSeen.
 *
 * Ordering by lastSeen is what made the list unreadable while scanning: every device is
 * re-heard every few seconds, so the sort key of every row changed constantly and rows
 * swapped places under the user's thumb. firstSeen never changes once written, so a row
 * stays where it appeared and new discoveries arrive at the top - which is the behaviour
 * a live list is supposed to have. Rows still update in place as new signal arrives; it is
 * only their ORDER that is now stable.
 */
@Dao
interface DeviceDao {
    @Upsert suspend fun upsert(device: DeviceEntity)
    @Update suspend fun update(device: DeviceEntity)

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun byId(id: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE isIgnored = 0 ORDER BY firstSeen DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE isIgnored = 0 AND band = :band ORDER BY firstSeen DESC")
    fun byBand(band: Band): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE isIgnored = 0 AND isMine = 0 AND threat >= :min ORDER BY threat DESC, firstSeen DESC")
    fun byMinThreat(min: Threat): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE isWatched = 1 ORDER BY firstSeen DESC")
    fun watched(): Flow<List<DeviceEntity>>

    /**
     * The owner's own devices.
     *
     * Ordered by label and then name rather than by time, because this is a roll call: the
     * question it answers is "is everything with me", and an answer that reorders itself
     * every time a tracker chirps is one you have to re-read from the top each glance.
     * Presence is shown per row from lastSeen instead.
     */
    @Query("SELECT * FROM devices WHERE isMine = 1 ORDER BY COALESCE(userLabel, displayName, address)")
    fun mine(): Flow<List<DeviceEntity>>

    @Query("UPDATE devices SET isMine = :mine WHERE id = :id")
    suspend fun setMine(id: String, mine: Boolean)

    @Query("""
        SELECT * FROM devices
        WHERE isIgnored = 0 AND (
            address LIKE '%' || :q || '%' OR
            displayName LIKE '%' || :q || '%' OR
            vendor LIKE '%' || :q || '%' OR
            userLabel LIKE '%' || :q || '%')
        ORDER BY firstSeen DESC LIMIT 300
    """)
    fun search(q: String): Flow<List<DeviceEntity>>

    @Query("SELECT COUNT(*) FROM devices")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM devices WHERE deviceClass = :cls")
    fun countOfClass(cls: DeviceClass): Flow<Int>

    @Query("UPDATE devices SET isWatched = :watched WHERE id = :id")
    suspend fun setWatched(id: String, watched: Boolean)

    @Query("UPDATE devices SET isIgnored = :ignored WHERE id = :id")
    suspend fun setIgnored(id: String, ignored: Boolean)

    @Query("UPDATE devices SET userLabel = :label WHERE id = :id")
    suspend fun setLabel(id: String, label: String?)

    @Query("DELETE FROM devices")
    suspend fun clear()
}

@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(obs: ObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(obs: List<ObservationEntity>)

    @Query("SELECT * FROM observations WHERE deviceId = :deviceId ORDER BY ts DESC LIMIT :limit")
    suspend fun forDevice(deviceId: String, limit: Int = 500): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE sessionId = :sessionId ORDER BY ts ASC")
    suspend fun forSession(sessionId: Long): List<ObservationEntity>

    /** Sightings that carry a fix — the only ones that can be mapped or used for follow-detection. */
    @Query("""
        SELECT * FROM observations
        WHERE deviceId = :deviceId AND lat IS NOT NULL AND ts >= :since
        ORDER BY ts ASC
    """)
    suspend fun locatedForDevice(deviceId: String, since: Long): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE lat IS NOT NULL AND ts >= :since ORDER BY ts ASC")
    suspend fun locatedSince(since: Long): List<ObservationEntity>

    @Query("SELECT COUNT(*) FROM observations")
    fun count(): Flow<Int>

    @Query("DELETE FROM observations WHERE ts < :before")
    suspend fun pruneBefore(before: Long)
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun all(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?
}

@Dao
interface ThreatDao {
    @Insert suspend fun insert(e: ThreatEventEntity): Long

    @Query("SELECT * FROM threat_events ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int = 300): Flow<List<ThreatEventEntity>>

    @Query("SELECT * FROM threat_events WHERE acknowledged = 0 ORDER BY severity DESC, ts DESC")
    fun unacknowledged(): Flow<List<ThreatEventEntity>>

    @Query("SELECT COUNT(*) FROM threat_events WHERE acknowledged = 0")
    fun unackCount(): Flow<Int>

    /** Suppress repeat alerts for the same device+type inside a cooldown window. */
    @Query("SELECT COUNT(*) FROM threat_events WHERE deviceId = :deviceId AND type = :type AND ts > :since")
    suspend fun recentCountFor(deviceId: String, type: String, since: Long): Int

    @Query("UPDATE threat_events SET acknowledged = 1 WHERE eventId = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE threat_events SET acknowledged = 1")
    suspend fun acknowledgeAll()
}

@Dao
interface AlprDao {
    @Upsert suspend fun upsertAll(rows: List<AlprCameraEntity>)

    /** Cheap bounding-box query; callers refine to a true radius. */
    @Query("""
        SELECT * FROM alpr_cameras
        WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon
        LIMIT 2000
    """)
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<AlprCameraEntity>

    @Query("SELECT COUNT(*) FROM alpr_cameras")
    fun count(): Flow<Int>

    @Query("DELETE FROM alpr_cameras")
    suspend fun clear()
}

@Dao
interface TpmsDao {
    @Insert suspend fun insert(r: TpmsReadingEntity): Long

    @Query("SELECT * FROM tpms_readings ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int = 300): Flow<List<TpmsReadingEntity>>

    @Query("SELECT DISTINCT sensorId FROM tpms_readings WHERE ts >= :since")
    suspend fun sensorsSince(since: Long): List<String>

    @Query("SELECT * FROM tpms_readings WHERE sensorId = :id ORDER BY ts DESC LIMIT :limit")
    suspend fun forSensor(id: String, limit: Int = 200): List<TpmsReadingEntity>

    @Query("SELECT COUNT(*) FROM tpms_readings")
    fun count(): Flow<Int>
}
