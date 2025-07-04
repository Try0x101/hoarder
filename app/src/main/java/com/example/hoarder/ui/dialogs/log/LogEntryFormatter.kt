package com.example.hoarder.ui.dialogs.log

import com.example.hoarder.data.storage.db.LogEntry
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

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun formatLogEntry(entry: LogEntry): FormattedLogEntry {
        return try {
            when (entry.type) {
                "BATCH_RECORD" -> formatBatchRecordEntry(entry)
                "SUCCESS" -> formatSuccessEntry(entry)
                "ERROR" -> formatErrorEntry(entry)
                else -> FormattedLogEntry("Unknown", entry.message, entry.message)
            }
        } catch (e: Exception) {
            FormattedLogEntry("Error", "Error processing entry: ${e.message}", entry.message)
        }
    }

    private fun formatBatchRecordEntry(entry: LogEntry): FormattedLogEntry {
        return try {
            val jsonObject = gson.fromJson(entry.message, Map::class.java) as? Map<String, Any>
            val timestamp = jsonObject?.get("ts")

            val timestampStr = formatSpecialTimestamp(timestamp)
            val header = "[${timestampStr}] [${ByteFormatter.format(entry.sizeBytes)}]"
            val prettyJson = gson.toJson(JsonParser.parseString(entry.message))

            FormattedLogEntry(header, prettyJson, prettyJson)
        } catch (e: Exception) {
            FormattedLogEntry("Error", "Error formatting batch record: ${e.message}", entry.message)
        }
    }

    private fun formatSuccessEntry(entry: LogEntry): FormattedLogEntry {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val header = "[${timestamp}] [${ByteFormatter.format(entry.sizeBytes)}]"
        val content = try {
            gson.toJson(JsonParser.parseString(entry.message))
        } catch (e: Exception) {
            entry.message
        }
        return FormattedLogEntry(header, content, entry.message)
    }

    private fun formatErrorEntry(entry: LogEntry): FormattedLogEntry {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val header = "[${timestamp}]"
        return FormattedLogEntry(header, entry.message, entry.message)
    }

    private fun formatSpecialTimestamp(timestamp: Any?): String {
        return try {
            val tsLong = when (timestamp) {
                is String -> timestamp.toLongOrNull()
                is Number -> timestamp.toLong()
                else -> null
            }

            if (tsLong != null) {
                val fixedEpoch = 1719705600L
                val date = Date((tsLong + fixedEpoch) * 1000)
                dateFormat.format(date)
            } else {
                "No Timestamp"
            }
        } catch (e: Exception) {
            "Invalid Timestamp"
        }
    }
}