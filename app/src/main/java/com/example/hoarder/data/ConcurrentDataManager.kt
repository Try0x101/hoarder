package com.example.hoarder.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Entity(tableName = "telemetry_records")
data class TelemetryRecord(
    @PrimaryKey val id: String,
    val deviceModel: String,
    val timestamp: Long,
    val batteryPercentage: Int,
    val batteryCapacity: Int?,
    val latitude: Double,
    val longitude: Double,
    val altitude: Int,
    val accuracy: Int,
    val speed: Int,
    val networkOperator: String,
    val networkType: String,
    val cellId: String,
    val trackingAreaCode: String,
    val mobileCountryCode: String,
    val mobileNetworkCode: String,
    val signalStrength: String,
    val wifiBssid: String,
    val downloadSpeed: String,
    val uploadSpeed: String,
    val lastModified: Long = System.currentTimeMillis(),
    val syncStatus: String = "PENDING"
)

@Dao
interface TelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRecord(record: TelemetryRecord)

    @Query("SELECT * FROM telemetry_records WHERE syncStatus = :status ORDER BY timestamp DESC")
    fun getRecordsByStatus(status: String): List<TelemetryRecord>

    @Query("UPDATE telemetry_records SET syncStatus = :status WHERE id IN (:ids)")
    fun updateSyncStatus(ids: List<String>, status: String): Int

    @Query("SELECT * FROM telemetry_records ORDER BY timestamp DESC LIMIT 1")
    fun getLatestRecord(): TelemetryRecord?

    @Query("DELETE FROM telemetry_records WHERE timestamp < :cutoffTime")
    fun deleteOldRecords(cutoffTime: Long): Int

    @Query("SELECT COUNT(*) FROM telemetry_records WHERE syncStatus = :status")
    fun getCountByStatus(status: String): Int

    @Query("SELECT * FROM telemetry_records WHERE lastModified > :since ORDER BY timestamp DESC")
    fun getChangedRecords(since: Long): List<TelemetryRecord>
}

@Database(
    entities = [TelemetryRecord::class],
    version = 1,
    exportSchema = false
)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        @Volatile
        private var INSTANCE: TelemetryDatabase? = null

        fun getDatabase(context: Context): TelemetryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TelemetryDatabase::class.java,
                    "telemetry_database"
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL("""
                            CREATE INDEX IF NOT EXISTS index_telemetry_timestamp 
                            ON telemetry_records(timestamp)
                        """)
                            db.execSQL("""
                            CREATE INDEX IF NOT EXISTS index_telemetry_sync_status 
                            ON telemetry_records(syncStatus)
                        """)
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ConcurrentDataManager {
    private val dataMap = ConcurrentHashMap<String, Any>()
    private val lastJsonData = AtomicReference<String?>(null)
    private val lastTelemetryRecord = AtomicReference<TelemetryRecord?>(null)

    fun setData(key: String, value: Any) {
        dataMap[key] = value
    }

    fun getData(key: String): Any? {
        return dataMap[key]
    }

    fun clearData() {
        dataMap.clear()
        lastJsonData.set(null)
        lastTelemetryRecord.set(null)
    }

    fun setJsonData(json: String?) {
        lastJsonData.set(json)
    }

    fun getJsonData(): String? {
        return lastJsonData.get()
    }

    fun setLastTelemetryRecord(record: TelemetryRecord?) {
        lastTelemetryRecord.set(record)
    }

    fun getLastTelemetryRecord(): TelemetryRecord? {
        return lastTelemetryRecord.get()
    }

    fun containsKey(key: String): Boolean {
        return dataMap.containsKey(key)
    }
}