package com.example.hoarder.ui.dialogs.server

import android.content.Context
import com.example.hoarder.data.storage.db.TelemetryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UploadStats(
    val payloadBytes: Long,
    val actualNetworkBytes: Long
)

class ServerStatsManager(private val context: Context) {

    private val logDao = TelemetryDatabase.getDatabase(context).logDao()

    suspend fun calculateUploadStats(): Triple<UploadStats, UploadStats, UploadStats> {
        return withContext(Dispatchers.IO) {
            val successLogs = logDao.getLogsByType("SUCCESS")
            val now = System.currentTimeMillis()
            val oneHourAgo = now - 3600 * 1000L
            val oneDayAgo = now - 24 * 3600 * 1000L
            val sevenDaysAgo = now - 7 * 24 * 3600 * 1000L

            var lastHourPayloadBytes = 0L
            var lastHourNetworkBytes = 0L
            var lastDayPayloadBytes = 0L
            var lastDayNetworkBytes = 0L
            var last7DaysPayloadBytes = 0L
            var last7DaysNetworkBytes = 0L

            successLogs.forEach { log ->
                val timestamp = log.timestamp
                val payloadBytes = log.sizeBytes ?: 0L
                val networkBytes = log.actualNetworkBytes ?: 0L

                if (timestamp >= sevenDaysAgo) {
                    last7DaysPayloadBytes += payloadBytes
                    last7DaysNetworkBytes += networkBytes
                    if (timestamp >= oneDayAgo) {
                        lastDayPayloadBytes += payloadBytes
                        lastDayNetworkBytes += networkBytes
                        if (timestamp >= oneHourAgo) {
                            lastHourPayloadBytes += payloadBytes
                            lastHourNetworkBytes += networkBytes
                        }
                    }
                }
            }

            Triple(
                UploadStats(lastHourPayloadBytes, lastHourNetworkBytes),
                UploadStats(lastDayPayloadBytes, lastDayNetworkBytes),
                UploadStats(last7DaysPayloadBytes, last7DaysNetworkBytes)
            )
        }
    }
}