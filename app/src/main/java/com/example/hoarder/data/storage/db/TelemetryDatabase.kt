package com.example.hoarder.data.storage.db

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.hoarder.data.models.BufferedPayload
import com.example.hoarder.data.models.LogEntry
import com.example.hoarder.data.models.TelemetryRecord

@Dao
interface LogDao {
    @Insert
    fun insertLog(log: LogEntry)

    @Query("SELECT * FROM log_entries WHERE type = :logType ORDER BY timestamp DESC LIMIT :limit")
    fun getLogsByType(logType: String, limit: Int): List<LogEntry>

    @Query("DELETE FROM log_entries")
    fun clearAllLogs()

    @Insert
    fun insertBufferedPayload(payload: BufferedPayload)

    @Query("SELECT * FROM buffered_payloads ORDER BY timestamp ASC")
    fun getBufferedPayloads(): List<BufferedPayload>

    @Query("DELETE FROM buffered_payloads WHERE id IN (:ids)")
    fun deleteBufferedPayloadsByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM buffered_payloads")
    fun getBufferedPayloadsCount(): Long

    @Query("DELETE FROM buffered_payloads")
    fun clearAllBufferedPayloads()

    @Query("DELETE FROM buffered_payloads WHERE id IN (SELECT id FROM buffered_payloads ORDER BY timestamp ASC LIMIT :count)")
    fun deleteOldestBufferedPayloads(count: Int)
}

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
    entities = [TelemetryRecord::class, LogEntry::class, BufferedPayload::class],
    version = 2,
    exportSchema = false
)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao
    abstract fun logDao(): LogDao

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
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_telemetry_timestamp ON telemetry_records(timestamp)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_telemetry_sync_status ON telemetry_records(syncStatus)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_log_entries_type_timestamp ON log_entries(type, timestamp)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_buffered_payloads_timestamp ON buffered_payloads(timestamp)")
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}