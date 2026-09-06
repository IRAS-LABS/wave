package com.wave.scanner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun bandToString(b: Band): String = b.name
    @TypeConverter fun stringToBand(s: String): Band = runCatching { Band.valueOf(s) }.getOrDefault(Band.WIFI)

    @TypeConverter fun classToString(c: DeviceClass): String = c.name
    @TypeConverter fun stringToClass(s: String): DeviceClass =
        runCatching { DeviceClass.valueOf(s) }.getOrDefault(DeviceClass.UNKNOWN)

    // Threat is stored as its ordinal so `threat >= :min` comparisons work in SQL.
    @TypeConverter fun threatToInt(t: Threat): Int = t.ordinal
    @TypeConverter fun intToThreat(i: Int): Threat = Threat.entries.getOrElse(i) { Threat.NONE }
}

@Database(
    entities = [
        DeviceEntity::class,
        ObservationEntity::class,
        SessionEntity::class,
        ThreatEventEntity::class,
        AlprCameraEntity::class,
        TpmsReadingEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class WaveDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun observations(): ObservationDao
    abstract fun sessions(): SessionDao
    abstract fun threats(): ThreatDao
    abstract fun alpr(): AlprDao
    abstract fun tpms(): TpmsDao

    companion object {
        @Volatile private var INSTANCE: WaveDatabase? = null

        /**
         * Adds the "this is one of mine" flag.
         *
         * Written out rather than left to the destructive fallback because by the time this
         * shipped there were real sessions on disk - thousands of sightings and a device
         * history that cannot be re-collected by standing in the same room again. Dropping
         * a table to add a boolean would be throwing away the only copy of that.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE devices ADD COLUMN isMine INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun get(context: Context): WaveDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WaveDatabase::class.java,
                    "wave.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Still the backstop for any version pair with no written path, but
                    // every deliberate schema change from here on gets a real migration.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
