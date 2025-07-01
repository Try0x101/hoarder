package com.example.hoarder.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.hoarder.R
import com.example.hoarder.utils.ToastHelper
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

class LogViewer(private val ctx: Context) {
    private val g by lazy { GsonBuilder().setPrettyPrinting().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create() }
    private val RECORDS_PER_PAGE = 20

    fun showLogDialog(logType: String) {
        val builder = AlertDialog.Builder(ctx, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_log_viewer, null)
        val container = view.findViewById<LinearLayout>(R.id.logViewerContainer)
        val controlsContainer = view.findViewById<LinearLayout>(R.id.controlsContainer)
        val prevButton = view.findViewById<Button>(R.id.prevButton)
        val nextButton = view.findViewById<Button>(R.id.nextButton)
        val pageIndicator = view.findViewById<TextView>(R.id.pageIndicator)
        val copyPageButton = view.findViewById<Button>(R.id.copyPageButton)
        builder.setView(view)

        val logEntries = getLogEntries(logType)
        var currentPage = 0
        val totalPages = if (logEntries.isEmpty()) 1 else ceil(logEntries.size.toDouble() / RECORDS_PER_PAGE).toInt()

        fun renderPage(page: Int) {
            currentPage = page.coerceIn(0, totalPages - 1)
            container.removeAllViews()

            val startIndex = currentPage * RECORDS_PER_PAGE
            val endIndex = (startIndex + RECORDS_PER_PAGE).coerceAtMost(logEntries.size)

            if (logEntries.isNotEmpty() && startIndex < logEntries.size) {
                val pageEntries = logEntries.subList(startIndex, endIndex)

                pageEntries.forEachIndexed { index, entry ->
                    try {
                        val (headerText, contentText, copyText) = formatLogEntry(logType, entry, startIndex + index + 1)

                        val header = TextView(ctx).apply {
                            text = headerText
                            textSize = 16f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(ContextCompat.getColor(ctx, R.color.amoled_white))
                            setPadding(0, if (index > 0) 24 else 0, 0, 8)
                        }
                        container.addView(header)

                        val content = TextView(ctx).apply {
                            text = contentText
                            textSize = 12f
                            typeface = Typeface.MONOSPACE
                            setTextColor(ContextCompat.getColor(ctx, R.color.amoled_light_gray))
                            setOnLongClickListener {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Hoarder Log Record", copyText)
                                clipboard.setPrimaryClip(clip)
                                ToastHelper.showToast(ctx, "Log entry copied to clipboard", Toast.LENGTH_SHORT)
                                true
                            }
                        }
                        container.addView(content)
                    } catch (e: Exception) {
                        val errorView = TextView(ctx).apply {
                            text = "Error displaying entry: ${e.message}"
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(ctx, R.color.amoled_red))
                        }
                        container.addView(errorView)
                    }
                }
            } else {
                val noDataView = TextView(ctx).apply {
                    text = "No logs found for this page."
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(ctx, R.color.amoled_light_gray))
                    gravity = Gravity.CENTER
                    setPadding(0, 50, 0, 0)
                }
                container.addView(noDataView)
            }

            pageIndicator.text = "Page ${currentPage + 1} of $totalPages"
            prevButton.isEnabled = currentPage > 0
            nextButton.isEnabled = currentPage < totalPages - 1
            copyPageButton.isEnabled = logEntries.isNotEmpty() && startIndex < logEntries.size
        }

        controlsContainer.visibility = android.view.View.VISIBLE
        renderPage(0)

        prevButton.setOnClickListener {
            if (currentPage > 0) {
                renderPage(currentPage - 1)
            }
        }

        nextButton.setOnClickListener {
            if (currentPage < totalPages - 1) {
                renderPage(currentPage + 1)
            }
        }

        copyPageButton.setOnClickListener {
            val startIndex = currentPage * RECORDS_PER_PAGE
            val endIndex = (startIndex + RECORDS_PER_PAGE).coerceAtMost(logEntries.size)
            val pageEntries = if (logEntries.isNotEmpty()) logEntries.subList(startIndex, endIndex) else emptyList()

            val allPageJson = pageEntries.joinToString(separator = ",\n\n") { entry ->
                val (_, _, copyText) = formatLogEntry(logType, entry, 0)
                copyText
            }
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Hoarder Page Log", allPageJson)
            clipboard.setPrimaryClip(clip)
            ToastHelper.showToast(ctx, "Copied ${pageEntries.size} records from page", Toast.LENGTH_SHORT)
        }

        val title = when(logType) {
            "cached" -> "Last Cached Upload Details"
            "success" -> "Upload Log"
            "error" -> "Error Log"
            else -> "Log"
        }

        builder.setTitle(title)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun getLogEntries(logType: String): List<String> {
        val result = when (logType) {
            "cached" -> {
                try {
                    val file = File(ctx.cacheDir, "last_upload_details.json")
                    if (file.exists()) {
                        val jsonText = file.readText()
                        val type = object : TypeToken<List<String>>() {}.type
                        g.fromJson<List<String>>(jsonText, type) ?: emptyList()
                    } else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            "success" -> {
                ctx.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
                    .getStringSet("success_logs", emptySet())?.toList() ?: emptyList()
            }
            "error" -> {
                ctx.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
                    .getStringSet("error_logs", emptySet())?.toList() ?: emptyList()
            }
            else -> emptyList()
        }

        return when (logType) {
            "success", "error" -> {
                result.sortedByDescending { entry ->
                    try {
                        val timestamp = entry.split("|", limit = 2).firstOrNull() ?: ""
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        dateFormat.parse(timestamp)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
            }
            "cached" -> {
                result.sortedByDescending { entry ->
                    try {
                        val jsonObject = g.fromJson(entry, Map::class.java) as? Map<String, Any>
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
            }
            else -> result
        }
    }

    private fun formatLogEntry(logType: String, entry: String, recordNum: Int): Triple<String, String, String> {
        return try {
            when (logType) {
                "cached" -> {
                    val entrySize = entry.toByteArray(StandardCharsets.UTF_8).size.toLong()

                    val jsonObject = try {
                        g.fromJson(entry, Map::class.java) as? Map<String, Any>
                    } catch (e: Exception) {
                        null
                    }
                    val timestamp = jsonObject?.get("ts")

                    val timestampStr = if (timestamp != null) {
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
                    } else {
                        "Unknown"
                    }

                    val header = "[${timestampStr}] [${formatBytes(entrySize)}]"
                    val content = entry

                    Triple(header, content, content)
                }
                "success" -> {
                    val parts = entry.split("|", limit = 3)
                    val timestamp = parts.getOrElse(0) { "Unknown" }
                    val size = parts.getOrElse(1) { "0" }.toLongOrNull() ?: 0
                    val json = parts.getOrElse(2) { "" }

                    val header = "[${timestamp}] [${formatBytes(size)}]"
                    val content = if (json.startsWith("Batch upload")) {
                        json
                    } else {
                        try {
                            g.toJson(com.google.gson.JsonParser.parseString(json))
                        } catch (e: Exception) {
                            json
                        }
                    }

                    Triple(header, content, json)
                }
                "error" -> {
                    val parts = entry.split("|", limit = 2)
                    val timestamp = parts.getOrElse(0) { "Unknown" }
                    val message = parts.getOrElse(1) { entry }

                    val header = "[${timestamp}]"

                    Triple(header, message, message)
                }
                else -> Triple("", entry, entry)
            }
        } catch (e: Exception) {
            Triple("", "Error processing entry: ${e.message}", entry)
        }
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val e = (Math.log(b.toDouble()) / Math.log(1024.0)).toInt()
        val p = "KMGTPE"[e - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", b / Math.pow(1024.0, e.toDouble()), p)
    }
}