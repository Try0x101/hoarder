package com.example.hoarder.data.storage.db

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
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