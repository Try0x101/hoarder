package com.example.hoarder.ui.dialogs.log

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

class LogRepository(private val ctx: Context, private val gson: Gson) {

    companion object {
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024
        private const val MAX_LOG_ENTRIES = 1000
    }

    fun getLogEntries(logType: String): List<String> {
        return try {
            when (logType) {
                "cached" -> getCachedLogEntries()
                "success" -> getSuccessLogEntries()
                "error" -> getErrorLogEntries()
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getCachedLogEntries(): List<String> {
        return try {
            val file = File(ctx.cacheDir, "last_upload_details.json")
            if (!file.exists() || file.length() > MAX_FILE_SIZE) {
                return emptyList()
            }

            val jsonText = file.readText(StandardCharsets.UTF_8)
            if (jsonText.isBlank()) {
                return emptyList()
            }

            val type = object : TypeToken<List<String>>() {}.type
            val entries = gson.fromJson<List<String>>(jsonText, type) ?: emptyList()

            (if (entries.size > MAX_LOG_ENTRIES) entries.takeLast(MAX_LOG_ENTRIES) else entries)
                .sortedByDescending { entry -> extractTimestampFromEntry(entry) }
        } catch (e: FileNotFoundException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: JsonSyntaxException) {
            emptyList()
        } catch (e: OutOfMemoryError) {
            emptyList()
        }
    }

    private fun getSuccessLogEntries(): List<String> {
        return try {
            val prefs = ctx.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
            val entries = prefs.getStringSet("success_logs", emptySet())?.toList() ?: emptyList()

            (if (entries.size > MAX_LOG_ENTRIES) entries.takeLast(MAX_LOG_ENTRIES) else entries)
                .sortedByDescending { entry -> extractTimestampFromLogEntry(entry) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getErrorLogEntries(): List<String> {
        return try {
            val prefs = ctx.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
            val entries = prefs.getStringSet("error_logs", emptySet())?.toList() ?: emptyList()

            (if (entries.size > MAX_LOG_ENTRIES) entries.takeLast(MAX_LOG_ENTRIES) else entries)
                .sortedByDescending { entry -> extractTimestampFromLogEntry(entry) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTimestampFromEntry(entry: String): Long {
        return try {
            val jsonObject = gson.fromJson(entry, Map::class.java) as? Map<String, Any>
            val timestamp = jsonObject?.get("ts")
            when (timestamp) {
                is String -> timestamp.toLongOrNull() ?: 0L
                is Number -> timestamp.toLong()
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun extractTimestampFromLogEntry(entry: String): Long {
        return try {
            val parts = entry.split("|", limit = 2)
            val timestampStr = parts.getOrNull(0) ?: return 0L
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            dateFormat.parse(timestampStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}