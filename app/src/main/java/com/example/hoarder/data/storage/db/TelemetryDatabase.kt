package com.example.hoarder.data.storage.db

import android.content.Context
import android.database.Cursor
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.hoarder.data.models.BufferedPayload
import com.example.hoarder.data.models.LogEntry
import com.example.hoarder.data.models.TelemetryRecord

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

@Dao
interface LogDao {
    @Insert
    fun insertLog(logEntry: LogEntry)

    @Insert
    fun insertPayload(payload: BufferedPayload)

    @Query("SELECT * FROM buffered_payloads ORDER BY timestamp ASC")
    fun getAllPayloads(): List<BufferedPayload>

    @Query("SELECT * FROM buffered_payloads ORDER BY timestamp ASC")
    fun getAllPayloadsCursor(): Cursor

    @Query("DELETE FROM buffered_payloads WHERE id IN (:ids)")
    fun deletePayloadsById(ids: List<Long>)

    @Query("SELECT SUM(LENGTH(payload)) FROM buffered_payloads")
    fun getBufferedPayloadsSize(): Long?

    @Query("SELECT COUNT(*) FROM buffered_payloads")
    fun getBufferedPayloadsCount(): Int

    @Query("DELETE FROM buffered_payloads WHERE timestamp < :cutoffTime")
    fun deleteOldPayloads(cutoffTime: Long)

    @Query("SELECT * FROM log_entries WHERE type = :logType ORDER BY timestamp DESC LIMIT 500")
    fun getLogsByType(logType: String): List<LogEntry>

    @Query("SELECT * FROM log_entries WHERE type = 'BATCH_RECORD' ORDER BY timestamp DESC LIMIT 500")
    fun getBatchRecords(): List<LogEntry>

    @Query("DELETE FROM log_entries")
    fun clearAllLogs()

    @Query("DELETE FROM buffered_payloads")
    fun clearBuffer()

    @Query("SELECT SUM(sizeBytes) FROM log_entries WHERE type = 'SUCCESS' AND timestamp >= :since")
    fun getUploadedBytesSince(since: Long): Long?

    @Query("SELECT SUM(actualNetworkBytes) FROM log_entries WHERE type = 'SUCCESS' AND timestamp >= :since")
    fun getActualNetworkBytesSince(since: Long): Long?
}

@Database(
    entities = [TelemetryRecord::class, LogEntry::class, BufferedPayload::class],
    version = 4,
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
                    .addCallback(object : Callback() {
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