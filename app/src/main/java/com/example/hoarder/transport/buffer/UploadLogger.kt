package com.example.hoarder.transport.buffer

import com.example.hoarder.data.models.LogEntry
import com.example.hoarder.data.storage.db.LogDao

class UploadLogger(private val logDao: LogDao) {

    companion object {
        const val TYPE_SUCCESS = "SUCCESS"
        const val TYPE_ERROR = "ERROR"
        const val TYPE_BATCH_SUCCESS = "BATCH_SUCCESS"
    }

    fun addErrorLog(errorMessage: String) {
        val log = LogEntry(
            timestamp = System.currentTimeMillis(),
            type = TYPE_ERROR,
            message = errorMessage,
            sizeBytes = 0
        )
        logDao.insertLog(log)
    }

    fun addSuccessLog(jsonData: String, uploadedBytes: Long) {
        val log = LogEntry(
            timestamp = System.currentTimeMillis(),
            type = TYPE_SUCCESS,
            message = jsonData,
            sizeBytes = uploadedBytes
        )
        logDao.insertLog(log)
    }

    fun addBatchSuccessLog(jsonData: String, uploadedBytes: Long) {
        val log = LogEntry(
            timestamp = System.currentTimeMillis(),
            type = TYPE_BATCH_SUCCESS,
            message = jsonData,
            sizeBytes = uploadedBytes
        )
        logDao.insertLog(log)
    }
}