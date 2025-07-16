package com.example.hoarder.transport.buffer

import com.example.hoarder.data.models.BufferedPayload
import com.example.hoarder.data.storage.db.LogDao
import com.google.gson.Gson

class DataBuffer(private val logDao: LogDao, private val gson: Gson) {

    fun saveToBuffer(jsonString: String) {
        val payload = BufferedPayload(
            timestamp = System.currentTimeMillis(),
            payload = jsonString
        )
        logDao.insertPayload(payload)
    }

    fun getBufferedData(): List<BufferedPayload> {
        return logDao.getAllPayloads()
    }

    fun getBufferedDataSize(): Long {
        return logDao.getBufferedPayloadsSize() ?: 0L
    }

    fun getBufferedPayloadsCount(): Int {
        return logDao.getBufferedPayloadsCount()
    }

    fun clearBuffer(processedPayloads: List<BufferedPayload>) {
        val ids = processedPayloads.map { it.id }
        if (ids.isNotEmpty()) {
            logDao.deletePayloadsById(ids)
        }
    }

    fun cleanupOldData() {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        logDao.deleteOldPayloads(sevenDaysAgo)
    }
}