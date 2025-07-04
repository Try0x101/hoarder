package com.example.hoarder.ui.dialogs.server

import android.content.Context
import com.example.hoarder.data.storage.db.TelemetryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServerStatsManager(private val context: Context) {

    private val logDao = TelemetryDatabase.getDatabase(context).logDao()

    suspend fun calculateUploadStats(): Triple<Long, Long, Long> {
        return withContext(Dispatchers.IO) {
            val successLogs = logDao.getLogsByType("SUCCESS")
            val now = System.currentTimeMillis()
            val oneHourAgo = now - 3600 * 1000L
            val oneDayAgo = now - 24 * 3600 * 1000L
            val sevenDaysAgo = now - 7 * 24 * 3600 * 1000L

            var lastHourBytes = 0L
            var lastDayBytes = 0L
            var last7DaysBytes = 0L

            successLogs.forEach { log ->
                val timestamp = log.timestamp
                val bytes = log.sizeBytes ?: 0L
                if (timestamp >= sevenDaysAgo) {
                    last7DaysBytes += bytes
                    if (timestamp >= oneDayAgo) {
                        lastDayBytes += bytes
                        if (timestamp >= oneHourAgo) {
                            lastHourBytes += bytes
                        }
                    }
                }
            }
            Triple(lastHourBytes, lastDayBytes, last7DaysBytes)
        }
    }
}