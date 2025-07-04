package com.example.hoarder.ui.dialogs.log

import android.content.Context
import com.example.hoarder.data.models.LogEntry
import com.example.hoarder.data.storage.db.TelemetryDatabase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogRepository(private val ctx: Context, private val gson: Gson) {

    private val logDao = TelemetryDatabase.getDatabase(ctx).logDao()

    suspend fun getLogEntries(logType: String): List<LogEntry> {
        return withContext(Dispatchers.IO) {
            try {
                when (logType) {
                    "cached" -> logDao.getBatchRecords()
                    "success" -> logDao.getLogsByType("SUCCESS")
                    "error" -> logDao.getLogsByType("ERROR")
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun clearAllLogs() {
        withContext(Dispatchers.IO) {
            logDao.clearAllLogs()
            logDao.clearBuffer()
        }
    }
}