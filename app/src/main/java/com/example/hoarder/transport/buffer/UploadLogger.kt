package com.example.hoarder.transport.buffer

import com.example.hoarder.data.storage.db.LogDao
import com.example.hoarder.data.storage.db.LogEntry

class UploadLogger(private val logDao: LogDao) {

    fun addErrorLog(errorMessage: String) {
        logDao.insertLog(
            LogEntry(
                timestamp = System.currentTimeMillis(),
                type = "ERROR",
                message = errorMessage,
                sizeBytes = 0
            )
        )
    }

    fun addSuccessLog(jsonData: String, uploadedBytes: Long) {
        logDao.insertLog(
            LogEntry(
                timestamp = System.currentTimeMillis(),
                type = "SUCCESS",
                message = jsonData,
                sizeBytes = uploadedBytes
            )
        )
    }

    fun addBatchSuccessLog(batchData: List<String>, uploadedBytes: Long) {
        val summaryMessage = "Batch upload of ${batchData.size} records"
        logDao.insertLog(
            LogEntry(
                timestamp = System.currentTimeMillis(),
                type = "SUCCESS",
                message = summaryMessage,
                sizeBytes = uploadedBytes
            )
        )

        batchData.forEach { recordJson ->
            logDao.insertLog(
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    type = "BATCH_RECORD",
                    message = recordJson,
                    sizeBytes = recordJson.toByteArray().size.toLong()
                )
            )
        }
    }
}