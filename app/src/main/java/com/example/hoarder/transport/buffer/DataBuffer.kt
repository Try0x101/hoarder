package com.example.hoarder.transport.buffer

import com.example.hoarder.data.models.BufferedPayload
import com.example.hoarder.data.storage.db.LogDao
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DataBuffer(private val logDao: LogDao, private val gson: Gson) {

    private val maxBufferEntries = 1000

    fun saveToBuffer(jsonString: String) {
        val currentCount = logDao.getBufferedPayloadsCount()
        if (currentCount >= maxBufferEntries) {
            logDao.deleteOldestBufferedPayloads(100)
        }

        val jsonWithTimestamp = try {
            val type = object : TypeToken<MutableMap<String, Any>>() {}.type
            val dataMap: MutableMap<String, Any> = gson.fromJson(jsonString, type)
            dataMap["ts"] = com.example.hoarder.common.time.TimestampUtils.getCurrentTimestamp()
            gson.toJson(dataMap)
        } catch (e: Exception) {
            jsonString
        }

        val payload = BufferedPayload(
            timestamp = System.currentTimeMillis(),
            payload = jsonWithTimestamp
        )
        logDao.insertBufferedPayload(payload)
    }

    fun getBufferedData(): List<BufferedPayload> {
        return logDao.getBufferedPayloads()
    }

    fun getBufferedDataSize(): Long {
        return logDao.getBufferedPayloads().sumOf { it.payload.toByteArray().size.toLong() }
    }

    fun clearBuffer(processedPayloads: List<BufferedPayload>) {
        if (processedPayloads.isNotEmpty()) {
            val ids = processedPayloads.map { it.id }
            logDao.deleteBufferedPayloadsByIds(ids)
        }
    }

    fun cleanupOldData() {
        // This is now handled by the DeltaManager's periodic cleanup.
        // This method can be removed or left empty.
    }
}