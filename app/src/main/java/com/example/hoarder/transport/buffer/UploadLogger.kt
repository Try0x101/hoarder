package com.example.hoarder.transport.buffer

import com.example.hoarder.data.models.LogEntry
import com.example.hoarder.data.storage.db.LogDao

class UploadLogger(private val logDao: LogDao) {

    fun addErrorLog(errorMessage: String) {
        logDao.insertLog(
            LogEntry(
                timestamp = System.currentTimeMillis(),
                type = "ERROR",
                message = errorMessage,
                sizeBytes = 0,
                actualNetworkBytes = 0
            )
        )
    }

    fun addSuccessLog(jsonData: String, uploadedBytes: Long, actualNetworkBytes: Long) {
        logDao.insertLog(
            LogEntry(
                timestamp = System.currentTimeMillis(),
                type = "SUCCESS",
                message = jsonData,
                sizeBytes = uploadedBytes,
                actualNetworkBytes = actualNetworkBytes
            )
        )
    }

    fun addBatchSuccessLog(batchData: List<String>, uploadedBytes: Long, actualNetworkBytes: Long) {
        val summaryMessage = "Batch upload of ${batchData.size} records"
        logDao.insertLog(
            LogEntry(
                timestamp = System.currentTimeMillis(),
                type = "SUCCESS",
                message = summaryMessage,
                sizeBytes = uploadedBytes,
                actualNetworkBytes = actualNetworkBytes
            )
        )

        batchData.forEach { recordJson ->
            logDao.insertLog(
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    type = "BATCH_RECORD",
                    message = recordJson,
                    sizeBytes = recordJson.toByteArray().size.toLong(),
                    actualNetworkBytes = 0
                )
            )
        }
    }
}