package com.example.hoarder.transport.buffer

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class UploadLogger(private val ctx: Context, private val sp: SharedPreferences, private val gson: Gson) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val maxLogEntries = 500

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
            if (parts.size == 2) (parts[0].toLongOrNull() ?: 0L) >= sevenDaysAgo else false
        }.toSet()
        sp.edit().putStringSet("uploadRecords", filtered).apply()
    }

    fun saveLastUploadDetails(uploadedData: List<String>) {
        try {
            val file = File(ctx.cacheDir, "last_upload_details.json")
            val jsonArrayOfStrings = gson.toJson(uploadedData)
            file.writeText(jsonArrayOfStrings)
            addSuccessLog("Saved ${uploadedData.size} cached upload records to file", file.length())
        } catch (e: Exception) {
            addErrorLog("Failed to save upload details: ${e.message}")
        }
    }

    fun cleanupOldLogFiles() {
        try {
            val file = File(ctx.cacheDir, "last_upload_details.json")
            if (file.exists() && file.lastModified() < System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)) {
                file.delete()
            }
        } catch (e: Exception) { /* File cleanup failed, not critical */ }
    }
}