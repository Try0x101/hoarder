package com.example.hoarder.ui.dialogs.log

import com.example.hoarder.ui.formatters.ByteFormatter
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.*

data class FormattedLogEntry(
    val header: String,
    val content: String,
    val copyText: String
)

class LogEntryFormatter(private val gson: Gson) {

    fun formatLogEntry(logType: String, entry: String): FormattedLogEntry {
        return try {
            when (logType) {
                "cached" -> formatCachedEntry(entry)
                "success" -> formatSuccessEntry(entry)
                "error" -> formatErrorEntry(entry)
                else -> FormattedLogEntry("", entry, entry)
            }
        } catch (e: Exception) {
            FormattedLogEntry("Error", "Error processing entry: ${e.message}", entry)
        }
    }

    private fun formatCachedEntry(entry: String): FormattedLogEntry {
        return try {
            val entrySize = entry.toByteArray(Charsets.UTF_8).size.toLong()
            val jsonObject = gson.fromJson(entry, Map::class.java) as? Map<String, Any>
            val timestamp = jsonObject?.get("ts")

            val timestampStr = formatTimestamp(timestamp)
            val header = "[${timestampStr}] [${ByteFormatter.format(entrySize)}]"

            FormattedLogEntry(header, entry, entry)
        } catch (e: Exception) {
            FormattedLogEntry("Error", "Error formatting cached entry: ${e.message}", entry)
        }
    }

    private fun formatSuccessEntry(entry: String): FormattedLogEntry {
        return try {
            val parts = entry.split("|", limit = 3)
            val timestamp = parts.getOrElse(0) { "Unknown" }
            val size = parts.getOrElse(1) { "0" }.toLongOrNull() ?: 0
            val json = parts.getOrElse(2) { "" }

            val header = "[${timestamp}] [${ByteFormatter.format(size)}]"
            val content = if (json.startsWith("Batch upload")) {
                json
            } else {
                try {
                    gson.toJson(JsonParser.parseString(json))
                } catch (e: Exception) {
                    json
                }
            }

            FormattedLogEntry(header, content, json)
        } catch (e: Exception) {
            FormattedLogEntry("Error", "Error formatting success entry: ${e.message}", entry)
        }
    }

    private fun formatErrorEntry(entry: String): FormattedLogEntry {
        return try {
            val parts = entry.split("|", limit = 2)
            val timestamp = parts.getOrElse(0) { "Unknown" }
            val message = parts.getOrElse(1) { entry }

            val header = "[${timestamp}]"
            FormattedLogEntry(header, message, message)
        } catch (e: Exception) {
            FormattedLogEntry("Error", "Error formatting error entry: ${e.message}", entry)
        }
    }

    private fun formatTimestamp(timestamp: Any?): String {
        return try {
            val tsLong = when (timestamp) {
                is String -> timestamp.toLongOrNull()
                is Number -> timestamp.toLong()
                else -> null
            }

            if (tsLong != null) {
                val fixedEpoch = 1719705600L
                val date = Date((tsLong + fixedEpoch) * 1000)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                dateFormat.format(date)
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}