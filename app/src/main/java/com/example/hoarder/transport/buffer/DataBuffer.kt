package com.example.hoarder.transport.buffer

import android.content.Context
import android.content.SharedPreferences
import com.example.hoarder.common.time.TimestampUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DataBuffer(private val sp: SharedPreferences, private val gson: Gson) {

    private val maxBufferEntries = 1000

    fun saveToBuffer(jsonString: String) {
        val buffer = sp.getStringSet("data_buffer", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        if (buffer.size >= maxBufferEntries) {
            val sortedBuffer = buffer.sorted().toMutableList()
            repeat(100) { if (sortedBuffer.isNotEmpty()) sortedBuffer.removeAt(0) }
            buffer.clear()
            buffer.addAll(sortedBuffer)
        }

        val jsonWithTimestamp = try {
            val type = object : TypeToken<MutableMap<String, Any>>() {}.type
            val dataMap: MutableMap<String, Any> = gson.fromJson(jsonString, type)
            dataMap["ts"] = TimestampUtils.getCurrentTimestamp()
            gson.toJson(dataMap)
        } catch (e: Exception) {
            jsonString
        }

        val timestampedEntry = "${System.currentTimeMillis()}:$jsonWithTimestamp"
        buffer.add(timestampedEntry)

        sp.edit().putStringSet("data_buffer", buffer).apply()
    }

    fun getBufferedData(): List<String> {
        val buffer = sp.getStringSet("data_buffer", emptySet()) ?: emptySet()
        return buffer.mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) parts[1] else null
        }.sorted()
    }

    fun getBufferedDataSize(): Long {
        val buffer = sp.getStringSet("data_buffer", emptySet()) ?: emptySet()
        return buffer.sumOf { it.toByteArray().size.toLong() }
    }

    fun clearBuffer(processedEntries: List<String>) {
        val buffer = sp.getStringSet("data_buffer", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val processedSet = processedEntries.toSet()

        val filtered = buffer.filter { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) !processedSet.contains(parts[1]) else true
        }.toSet()

        sp.edit().putStringSet("data_buffer", filtered).apply()
    }

    fun cleanupOldData() {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val buffer = sp.getStringSet("data_buffer", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val filteredBuffer = buffer.filter { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val timestamp = parts[0].toLongOrNull() ?: 0L
                timestamp >= sevenDaysAgo
            } else false
        }.toSet()
        sp.edit().putStringSet("data_buffer", filteredBuffer).apply()
    }
}