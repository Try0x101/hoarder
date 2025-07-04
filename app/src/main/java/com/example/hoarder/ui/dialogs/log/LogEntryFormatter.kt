package com.example.hoarder.ui.dialogs.log

import com.example.hoarder.data.models.LogEntry
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
            val timestampStr: String

            when {
                jsonObject?.containsKey("bts") == true -> {
                    val bts = (jsonObject["bts"] as? Number)?.toLong()
                    timestampStr = if (bts != null) {
                        dateFormat.format(Date(bts * 1000))
                    } else {
                        "Invalid Base TS"
                    }
                }
                jsonObject?.containsKey("tso") == true -> {
                    val tso = (jsonObject["tso"] as? Number)?.toLong()
                    timestampStr = if (tso != null) "+${tso}s" else "Invalid Offset"
                }
                else -> {
                    timestampStr = "No Timestamp"
                }
            }

            val header = "[${timestampStr}] [${ByteFormatter.format(entry.sizeBytes ?: 0L)}]"
            val prettyJson = gson.toJson(JsonParser.parseString(entry.message))

            FormattedLogEntry(header, prettyJson, prettyJson)
        } catch (e: Exception) {
            FormattedLogEntry("Error", "Error formatting batch record: ${e.message}", entry.message)
        }
    }

    private fun formatSuccessEntry(entry: LogEntry): FormattedLogEntry {
        val timestamp = dateFormat.format(Date(entry.timestamp))
        val header = "[${timestamp}] [${ByteFormatter.format(entry.sizeBytes ?: 0L)}]"
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
}