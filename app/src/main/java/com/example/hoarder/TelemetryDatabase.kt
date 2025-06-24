package com.example.hoarder
import androidx.room.*
import android.content.Context

@Entity(tableName = "telemetry_queue")
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: String,
    val jsonData: String,
    val timestamp: Long,
    val uploaded: Boolean = false,
    val uploadAttempts: Int = 0,
    val lastAttemptTime: Long = 0,
    val dataType: String = "delta"
)

@Dao
interface TelemetryDao {
    @Query("SELECT * FROM telemetry_queue WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getPendingTelemetry(limit: Int = 10): List<TelemetryEntity>

    @Insert
    suspend fun insertTelemetry(telemetry: TelemetryEntity): Long

    @Update
    suspend fun updateTelemetry(telemetry: TelemetryEntity)

    @Query("DELETE FROM telemetry_queue WHERE uploaded = 1 AND timestamp < :cutoffTime")
    suspend fun cleanupOldUploaded(cutoffTime: Long)

    @Query("SELECT COUNT(*) FROM telemetry_queue WHERE uploaded = 0")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM telemetry_queue WHERE uploaded = 1")
    suspend fun getUploadedCount(): Int

    @Query("SELECT * FROM telemetry_queue WHERE uploadAttempts > 5 AND uploaded = 0")
    suspend fun getFailedRecords(): List<TelemetryEntity>

    @Query("UPDATE telemetry_queue SET uploadAttempts = 0 WHERE uploadAttempts > 5")
    suspend fun resetFailedRecords()
}

@Database(
    entities = [TelemetryEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromString(value: String): List<String> {
        return value.split(",")
    }

    @TypeConverter
    fun fromArrayList(list: List<String>): String {
        return list.joinToString(",")
    }
}