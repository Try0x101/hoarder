package com.example.hoarder.data.uploader

import android.content.Context
import android.content.SharedPreferences
import com.example.hoarder.data.DataUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BufferManager(private val ctx: Context, private val sp: SharedPreferences) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val maxBufferEntries = 1000
    private val maxLogEntries = 500
    private val gson = Gson()

    fun saveToBuffer(jsonString: String) {
        val buffer = sp.getStringSet("data_buffer", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        if (buffer.size >= maxBufferEntries) {
            val sortedBuffer = buffer.sorted().toMutableList()
            repeat(100) { if (sortedBuffer.isNotEmpty()) sortedBuffer.removeAt(0) }
            buffer.clear()
            buffer.addAll(sortedBuffer)
        }

        // Add timestamp to JSON data before buffering
        val jsonWithTimestamp = try {
            val type = object : TypeToken<MutableMap<String, Any>>() {}.type
            val dataMap: MutableMap<String, Any> = gson.fromJson(jsonString, type)

            // Add timestamp using fixed epoch - this is when data was collected
            dataMap["ts"] = DataUtils.getCurrentTimestamp()

            gson.toJson(dataMap)
        } catch (e: Exception) {
            // If JSON parsing fails, use original string
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

    fun addErrorLog(errorMessage: String) {
        val logs = sp.getStringSet("error_logs", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val timestamp = dateFormat.format(Date())
        val logEntry = "$timestamp|$errorMessage"

        logs.add(logEntry)

        if (logs.size > maxLogEntries) {
            val sortedLogs = logs.sorted().toMutableList()
            repeat(logs.size - maxLogEntries) {
                if (sortedLogs.isNotEmpty()) sortedLogs.removeAt(0)
            }
            logs.clear()
            logs.addAll(sortedLogs)
        }

        sp.edit().putStringSet("error_logs", logs).apply()
    }

    fun addSuccessLog(jsonData: String, uploadedBytes: Long) {
        val logs = sp.getStringSet("success_logs", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val timestamp = dateFormat.format(Date())
        val logEntry = "$timestamp|$uploadedBytes|$jsonData"

        logs.add(logEntry)

        if (logs.size > maxLogEntries) {
            val sortedLogs = logs.sorted().toMutableList()
            repeat(logs.size - maxLogEntries) {
                if (sortedLogs.isNotEmpty()) sortedLogs.removeAt(0)
            }
            logs.clear()
            logs.addAll(sortedLogs)
        }

        sp.edit().putStringSet("success_logs", logs).apply()
    }

    fun addUploadRecord(uploadedBytes: Long) {
        val records = sp.getStringSet("uploadRecords", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val timestamp = System.currentTimeMillis()
        val record = "$timestamp:$uploadedBytes"

        records.add(record)

        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val filtered = records.filter { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val entryTime = parts[0].toLongOrNull() ?: 0L
                entryTime >= sevenDaysAgo
            } else false
        }.toSet()

        sp.edit().putStringSet("uploadRecords", filtered).apply()
    }

    fun saveLastUploadDetails(uploadedData: List<String>) {
        try {
            val file = File(ctx.cacheDir, "last_upload_details.json")

            // LogViewer expects a JSON array of JSON strings (not JSON objects)
            // So each JSON object needs to be a quoted string in the array
            val jsonArrayOfStrings = gson.toJson(uploadedData)

            file.writeText(jsonArrayOfStrings)

            addSuccessLog("Saved ${uploadedData.size} cached upload records to file", file.length())

        } catch (e: Exception) {
            addErrorLog("Failed to save upload details: ${e.message}")
        }
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

        sp.edit()
            .putStringSet("data_buffer", filteredBuffer)
            .apply()

        try {
            val file = File(ctx.cacheDir, "last_upload_details.json")
            if (file.exists() && file.lastModified() < sevenDaysAgo) {
                file.delete()
            }
        } catch (e: Exception) {
            // File cleanup failed, not critical
        }
    }
}