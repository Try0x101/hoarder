package com.example.hoarder.data.uploader

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset

class BufferManager(private val ctx: Context, private val sp: SharedPreferences) {
    private val g = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

    fun saveToBuffer(jsonString: String) {
        val buffer = getBufferedData().toMutableSet()
        try {
            val type = object : TypeToken<MutableMap<String, Any>>() {}.type
            val dataMap = g.fromJson<MutableMap<String, Any>>(jsonString, type)

            val now = Instant.now()
            val fixedEpoch = Instant.parse("2025-06-30T00:00:00Z")
            val secondsSinceFixedEpoch = now.epochSecond - fixedEpoch.epochSecond

            dataMap["ts"] = secondsSinceFixedEpoch.toString()

            val stringDataMap = convertToStringMap(dataMap)
            val modifiedJsonString = g.toJson(stringDataMap)
            buffer.add(modifiedJsonString)

            sp.edit()
                .putStringSet("data_buffer", buffer)
                .putLong("buffer_entry_${modifiedJsonString.hashCode()}", now.toEpochMilli())
                .apply()
            cleanupOldBufferData()
        } catch (e: Exception) {
        }
    }

    fun getBufferedData(): List<String> {
        cleanupOldBufferData()
        return sp.getStringSet("data_buffer", emptySet())?.toList() ?: emptyList()
    }

    fun clearBuffer(sentData: List<String>) {
        val buffer = getBufferedData().toMutableSet()
        buffer.removeAll(sentData)
        sp.edit().putStringSet("data_buffer", buffer).apply()
    }

    fun getBufferedDataSize(): Long {
        return getBufferedData().sumOf { it.toByteArray(StandardCharsets.UTF_8).size }.toLong()
    }

    private fun convertToStringMap(data: Map<String, Any?>): Map<String, String> {
        return data.mapValues { (_, value) ->
            when (value) {
                is String -> value
                is Number -> value.toString()
                null -> "null"
                else -> value.toString()
            }
        }
    }

    private fun cleanupOldBufferData() {
        val currentTimeMillis = System.currentTimeMillis()
        val sevenDaysAgoMillis = currentTimeMillis - 7 * 24 * 60 * 60 * 1000L
        val sevenDaysAgoMinute = sevenDaysAgoMillis / 60000L

        val buffer = sp.getStringSet("data_buffer", emptySet())?.toMutableSet() ?: return
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val toRemove = buffer.filter {
            try {
                val data = g.fromJson<Map<String, Any>>(it, type)
                val tsInMinute = (data["ts"] as? String)?.toLongOrNull() ?: return@filter false

                val entryCreationTime = sp.getLong("buffer_entry_${it.hashCode()}", currentTimeMillis)
                val entryMinute = entryCreationTime / 60000L

                entryMinute < sevenDaysAgoMinute
            } catch (e: Exception) {
                true
            }
        }
        if (toRemove.isNotEmpty()) {
            buffer.removeAll(toRemove)
            toRemove.forEach {
                sp.edit().remove("buffer_entry_${it.hashCode()}").apply()
            }
            sp.edit().putStringSet("data_buffer", buffer).apply()
        }
    }

    fun saveLastUploadDetails(jsonData: List<String>) {
        try {
            val file = java.io.File(ctx.cacheDir, "last_upload_details.json")
            file.writeText(g.toJson(jsonData))
        } catch (e: Exception) {
        }
    }

    fun addUploadRecord(bytes: Long) {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000L
        val newRecord = "$now:$bytes"

        val records = sp.getStringSet("uploadRecords", mutableSetOf()) ?: mutableSetOf()

        val updatedRecords = records.filter { record ->
            val timestamp = record.split(":").firstOrNull()?.toLongOrNull()
            timestamp != null && timestamp >= sevenDaysAgo
        }.toMutableSet()

        updatedRecords.add(newRecord)

        sp.edit().putStringSet("uploadRecords", updatedRecords).apply()
    }

    fun addErrorLog(message: String) {
        val logs = sp.getStringSet("error_logs", mutableSetOf())?.toMutableList() ?: mutableListOf()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logs.add(0, "$timestamp|$message")
        while (logs.size > 10) {
            logs.removeAt(logs.size - 1)
        }
        sp.edit().putStringSet("error_logs", logs.toSet()).apply()
    }

    fun addSuccessLog(json: String, size: Long) {
        val logs = sp.getStringSet("success_logs", mutableSetOf())?.toMutableList() ?: mutableListOf()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logs.add(0, "$timestamp|$size|$json")
        while (logs.size > 10) {
            logs.removeAt(logs.size - 1)
        }
        sp.edit().putStringSet("success_logs", logs.toSet()).apply()
    }
}