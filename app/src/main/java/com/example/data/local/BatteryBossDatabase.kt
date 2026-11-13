package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "charging_sessions")
data class ChargingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val startPercentage: Int,
    val endPercentage: Int,
    val chargingSource: String,
    val peakWatts: Float? = null,
    val averageWatts: Float? = null,
    val averageCurrentMa: Int? = null,
    val peakTemperatureCelsius: Float? = null,
    val isCompleted: Boolean = false
) {
    val durationMinutes: Int
        get() {
            val end = endTimeMillis ?: System.currentTimeMillis()
            return ((end - startTimeMillis).coerceAtLeast(0L) / 60_000L).toInt()
        }

    val percentageGained: Int
        get() = (endPercentage - startPercentage).coerceAtLeast(0)

    /**
     * Computes percentage gained per hour (%/hr) when session duration is at least 1 minute.
     */
    val percentPerHour: Float?
        get() {
            val end = endTimeMillis ?: System.currentTimeMillis()
            val elapsedHours = (end - startTimeMillis).coerceAtLeast(0L) / 3_600_000f
            return if (elapsedHours >= (1f / 60f) && percentageGained > 0) {
                percentageGained / elapsedHours
            } else {
                null
            }
        }
}

@Entity(tableName = "announcement_logs")
data class AnnouncementLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val eventType: String,
    val messageSpoken: String,
    val voiceProfileLabel: String,
    val batteryPercentage: Int,
    val temperatureCelsius: Float? = null,
    val wasMutedByQuietMode: Boolean = false
)

@Dao
interface BatteryBossDao {
    @Query("SELECT * FROM charging_sessions ORDER BY startTimeMillis DESC LIMIT 50")
    fun observeChargingSessions(): Flow<List<ChargingSessionEntity>>

    @Query("SELECT * FROM charging_sessions WHERE isCompleted = 0 ORDER BY startTimeMillis DESC LIMIT 1")
    suspend fun getActiveChargingSession(): ChargingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChargingSession(session: ChargingSessionEntity): Long

    @Update
    suspend fun updateChargingSession(session: ChargingSessionEntity)

    @Query("DELETE FROM charging_sessions")
    suspend fun clearAllSessions()

    @Query("SELECT * FROM announcement_logs ORDER BY timestampMillis DESC LIMIT 100")
    fun observeAnnouncementLogs(): Flow<List<AnnouncementLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnouncementLog(log: AnnouncementLogEntity)

    @Query("DELETE FROM announcement_logs")
    suspend fun clearAllAnnouncementLogs()
}

@Database(
    entities = [ChargingSessionEntity::class, AnnouncementLogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class BatteryBossDatabase : RoomDatabase() {
    abstract fun batteryBossDao(): BatteryBossDao

    companion object {
        @Volatile
        private var INSTANCE: BatteryBossDatabase? = null

        fun getInstance(context: Context): BatteryBossDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryBossDatabase::class.java,
                    "battery_boss_telemetry.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class BatteryBossRepository(private val dao: BatteryBossDao) {
    val chargingSessions: Flow<List<ChargingSessionEntity>> = dao.observeChargingSessions()
    val announcementLogs: Flow<List<AnnouncementLogEntity>> = dao.observeAnnouncementLogs()

    suspend fun getActiveSession(): ChargingSessionEntity? = dao.getActiveChargingSession()

    suspend fun startOrUpdateChargingSession(
        percentage: Int,
        sourceName: String,
        watts: Float?,
        currentMa: Int?,
        tempCelsius: Float?
    ) {
        val cleanWatts = watts?.let { abs(it) }
        val cleanCurrent = currentMa?.let { abs(it) }
        val active = dao.getActiveChargingSession()
        if (active == null) {
            dao.insertChargingSession(
                ChargingSessionEntity(
                    startTimeMillis = System.currentTimeMillis(),
                    startPercentage = percentage,
                    endPercentage = percentage,
                    chargingSource = sourceName,
                    peakWatts = cleanWatts,
                    averageWatts = cleanWatts,
                    averageCurrentMa = cleanCurrent,
                    peakTemperatureCelsius = tempCelsius,
                    isCompleted = false
                )
            )
        } else {
            val updatedPeakWatts = when {
                cleanWatts != null && active.peakWatts != null -> maxOf(active.peakWatts, cleanWatts)
                cleanWatts != null -> cleanWatts
                else -> active.peakWatts
            }
            val updatedAvgWatts = when {
                cleanWatts != null && active.averageWatts != null ->
                    (active.averageWatts * 0.75f) + (cleanWatts * 0.25f)
                cleanWatts != null -> cleanWatts
                else -> active.averageWatts
            }
            val updatedPeakTemp = when {
                tempCelsius != null && active.peakTemperatureCelsius != null ->
                    maxOf(active.peakTemperatureCelsius, tempCelsius)
                tempCelsius != null -> tempCelsius
                else -> active.peakTemperatureCelsius
            }
            val updatedAvgCurrent = when {
                cleanCurrent != null && active.averageCurrentMa != null ->
                    (active.averageCurrentMa + cleanCurrent) / 2
                cleanCurrent != null -> cleanCurrent
                else -> active.averageCurrentMa
            }
            dao.updateChargingSession(
                active.copy(
                    endPercentage = percentage,
                    chargingSource = if (sourceName != "On Battery") sourceName else active.chargingSource,
                    peakWatts = updatedPeakWatts,
                    averageWatts = updatedAvgWatts,
                    averageCurrentMa = updatedAvgCurrent,
                    peakTemperatureCelsius = updatedPeakTemp
                )
            )
        }
    }

    suspend fun completeActiveChargingSession(finalPercentage: Int, tempCelsius: Float?) {
        val active = dao.getActiveChargingSession() ?: return
        val updatedPeakTemp = when {
            tempCelsius != null && active.peakTemperatureCelsius != null ->
                maxOf(active.peakTemperatureCelsius, tempCelsius)
            tempCelsius != null -> tempCelsius
            else -> active.peakTemperatureCelsius
        }
        dao.updateChargingSession(
            active.copy(
                endTimeMillis = System.currentTimeMillis(),
                endPercentage = finalPercentage,
                peakTemperatureCelsius = updatedPeakTemp,
                isCompleted = true
            )
        )
    }

    suspend fun logAnnouncement(
        eventType: String,
        messageSpoken: String,
        voiceProfileLabel: String,
        batteryPercentage: Int,
        temperatureCelsius: Float?,
        wasMutedByQuietMode: Boolean = false
    ) {
        dao.insertAnnouncementLog(
            AnnouncementLogEntity(
                eventType = eventType,
                messageSpoken = messageSpoken,
                voiceProfileLabel = voiceProfileLabel,
                batteryPercentage = batteryPercentage,
                temperatureCelsius = temperatureCelsius,
                wasMutedByQuietMode = wasMutedByQuietMode
            )
        )
    }

    suspend fun clearAllHistory() {
        dao.clearAllSessions()
        dao.clearAllAnnouncementLogs()
    }
}
