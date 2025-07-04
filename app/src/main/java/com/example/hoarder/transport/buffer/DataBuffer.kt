package com.example.hoarder.transport.buffer

import com.example.hoarder.common.time.TimestampUtils
import com.example.hoarder.data.storage.db.BufferedPayload
import com.example.hoarder.data.storage.db.LogDao
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DataBuffer(private val logDao: LogDao, private val gson: Gson) {

    fun saveToBuffer(jsonString: String) {
        val jsonWithTimestamp = try {
            val type = object : TypeToken<MutableMap<String, Any>>() {}.type
            val dataMap: MutableMap<String, Any> = gson.fromJson(jsonString, type)
            dataMap["ts"] = TimestampUtils.getCurrentTimestamp()
            gson.toJson(dataMap)
        } catch (e: Exception) {
            jsonString
        }
        val payload = BufferedPayload(
            timestamp = System.currentTimeMillis(),
            payload = jsonWithTimestamp
        )
        logDao.insertPayload(payload)
    }

    fun getBufferedData(): List<BufferedPayload> {
        return logDao.getAllPayloads()
    }

    fun getBufferedDataSize(): Long {
        return logDao.getBufferedPayloadsSize() ?: 0L
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