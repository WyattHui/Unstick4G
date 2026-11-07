package com.wyatthui.unstick4g.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "network_events", indices = [Index("timestamp")])
data class NetworkEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dataType: Int,
    val voiceType: Int,
    val signalDbm: Int?,
    val inDrive: Boolean,
)

@Entity(tableName = "drives")
data class Drive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
)

@Entity(tableName = "location_samples", indices = [Index("timestamp")])
data class LocationSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    val driveId: Long,
    val dataType: Int,
    val voiceType: Int,
    val lteCells: String,
)

@Entity(tableName = "fix_attempts")
data class FixAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val method: String,
    val typeBefore: Int,
    val typeAfter: Int,
    val secondsOn2gBefore: Long?,
    val result: String,
    val durationMs: Long,
)

@Entity(tableName = "shizuku_stops")
data class ShizukuStop(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
)

@Dao
interface LogDao {
    @Insert
    suspend fun insert(event: NetworkEvent)

    @Insert
    suspend fun insert(drive: Drive): Long

    @Insert
    suspend fun insert(sample: LocationSample)

    @Insert
    suspend fun insert(attempt: FixAttempt)

    @Insert
    suspend fun insert(stop: ShizukuStop)

    @Query("UPDATE drives SET endedAt = :at WHERE id = :id")
    suspend fun endDrive(id: Long, at: Long)

    @Query(
        """
        UPDATE drives SET endedAt = MAX(
            startedAt,
            IFNULL((SELECT MAX(timestamp) FROM location_samples WHERE driveId = drives.id), 0),
            IFNULL((SELECT MAX(timestamp) FROM network_events WHERE inDrive = 1 AND timestamp >= drives.startedAt), 0)
        )
        WHERE endedAt IS NULL
        """,
    )
    suspend fun endOpenDrives()

    @Query("SELECT * FROM network_events WHERE timestamp <= :at ORDER BY timestamp DESC LIMIT :limit")
    suspend fun eventsUpTo(at: Long, limit: Int): List<NetworkEvent>

    @Query("SELECT * FROM network_events ORDER BY timestamp DESC LIMIT :limit")
    fun recentEvents(limit: Int): Flow<List<NetworkEvent>>

    @Query("SELECT * FROM fix_attempts ORDER BY timestamp DESC LIMIT :limit")
    fun recentFixAttempts(limit: Int): Flow<List<FixAttempt>>

    @Query("SELECT * FROM network_events ORDER BY timestamp")
    suspend fun allEvents(): List<NetworkEvent>

    @Query("SELECT * FROM drives ORDER BY startedAt")
    suspend fun allDrives(): List<Drive>

    @Query("SELECT * FROM location_samples ORDER BY timestamp")
    suspend fun allSamples(): List<LocationSample>

    @Query("SELECT * FROM fix_attempts ORDER BY timestamp")
    suspend fun allFixAttempts(): List<FixAttempt>

    @Query("SELECT * FROM shizuku_stops ORDER BY timestamp")
    suspend fun allShizukuStops(): List<ShizukuStop>

    @Transaction
    suspend fun deleteOlderThan(cutoff: Long) {
        deleteEventsBefore(cutoff)
        deleteDrivesBefore(cutoff)
        deleteSamplesBefore(cutoff)
        deleteFixAttemptsBefore(cutoff)
        deleteShizukuStopsBefore(cutoff)
    }

    @Query("DELETE FROM network_events WHERE timestamp < :cutoff")
    suspend fun deleteEventsBefore(cutoff: Long)

    @Query("DELETE FROM drives WHERE startedAt < :cutoff")
    suspend fun deleteDrivesBefore(cutoff: Long)

    @Query("DELETE FROM location_samples WHERE timestamp < :cutoff")
    suspend fun deleteSamplesBefore(cutoff: Long)

    @Query("DELETE FROM fix_attempts WHERE timestamp < :cutoff")
    suspend fun deleteFixAttemptsBefore(cutoff: Long)

    @Query("DELETE FROM shizuku_stops WHERE timestamp < :cutoff")
    suspend fun deleteShizukuStopsBefore(cutoff: Long)
}

@Database(
    entities = [NetworkEvent::class, Drive::class, LocationSample::class, FixAttempt::class, ShizukuStop::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "unstick4g.db")
                .addMigrations(ADD_SHIZUKU_STOPS)
                .build()

        private val ADD_SHIZUKU_STOPS = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shizuku_stops` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL)",
                )
            }
        }
    }
}
