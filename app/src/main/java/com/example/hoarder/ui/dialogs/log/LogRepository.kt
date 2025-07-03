package com.example.hoarder.ui.dialogs.log

import com.example.hoarder.data.models.LogEntry
import com.example.hoarder.data.storage.db.LogDao
import com.example.hoarder.transport.buffer.UploadLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogRepository(private val logDao: LogDao) {

    companion object {
        private const val MAX_LOG_ENTRIES = 500
    }

    suspend fun getLogEntries(logType: String): List<LogEntry> {
        return try {
            withContext(Dispatchers.IO) {
                when (logType) {
                    "success" -> logDao.getLogsByType(UploadLogger.TYPE_SUCCESS, MAX_LOG_ENTRIES)
                    "error" -> logDao.getLogsByType(UploadLogger.TYPE_ERROR, MAX_LOG_ENTRIES)
                    "batch_success" -> logDao.getLogsByType(UploadLogger.TYPE_BATCH_SUCCESS, MAX_LOG_ENTRIES)
                    else -> emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}