package com.example.hoarder.ui.dialogs.server

import android.content.Context

class ServerStatsManager(private val context: Context) {

    fun calculateUploadStats(): Triple<Long, Long, Long> {
        val records = context.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
            .getStringSet("uploadRecords", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3600 * 1000L
        val oneDayAgo = now - 24 * 3600 * 1000L

        var lastHourBytes = 0L
        var lastDayBytes = 0L
        var last7DaysBytes = 0L

        records.forEach { record ->
            val parts = record.split(":")
            if (parts.size == 2) {
                val timestamp = parts[0].toLongOrNull()
                val bytes = parts[1].toLongOrNull()
                if (timestamp != null && bytes != null) {
                    last7DaysBytes += bytes
                    if (timestamp >= oneDayAgo) {
                        lastDayBytes += bytes
                    }
                    if (timestamp >= oneHourAgo) {
                        lastHourBytes += bytes
                    }
                }
            }
        }
        return Triple(lastHourBytes, lastDayBytes, last7DaysBytes)
    }
}