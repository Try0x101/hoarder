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
import com.example.hoarder.ui.dialogs.log.LogEntryFormatter
import com.example.hoarder.ui.dialogs.log.LogPaginator
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.utils.ToastHelper
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy

class LogViewer(private val ctx: Context) {
    private val gson by lazy {
        GsonBuilder()
            .setPrettyPrinting()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create()
    }
    private val logRepository = LogRepository(ctx, gson)
    private val logEntryFormatter = LogEntryFormatter(gson)
    private val logPaginator = LogPaginator()
    private var currentLogType: String = ""

    fun showLogDialog(logType: String) {
        currentLogType = logType
        val builder = AlertDialog.Builder(ctx, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_log_viewer, null)
        val container = view.findViewById<LinearLayout>(R.id.logViewerContainer)
        val controlsContainer = view.findViewById<LinearLayout>(R.id.controlsContainer)
        val prevButton = view.findViewById<Button>(R.id.prevButton)
        val nextButton = view.findViewById<Button>(R.id.nextButton)
        val pageIndicator = view.findViewById<TextView>(R.id.pageIndicator)
        val copyPageButton = view.findViewById<Button>(R.id.copyPageButton)
        val refreshButton = view.findViewById<Button>(R.id.refreshButton)
        builder.setView(view)

        fun renderPage() {
            container.removeAllViews()
            val pageEntries = logPaginator.getCurrentPageEntries()

            if (pageEntries.isNotEmpty()) {
                pageEntries.forEachIndexed { index, entry ->
                    try {
                        val formatted = logEntryFormatter.formatLogEntry(logType, entry)
                        val header = TextView(ctx).apply {
                            text = formatted.header
                            textSize = 16f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(ContextCompat.getColor(ctx, R.color.amoled_white))
                            setPadding(0, if (index > 0) 24 else 0, 0, 8)
                        }
                        container.addView(header)

                        val content = TextView(ctx).apply {
                            text = formatted.content
                            textSize = 12f
                            typeface = Typeface.MONOSPACE
                            setTextColor(ContextCompat.getColor(ctx, R.color.amoled_light_gray))
                            setOnLongClickListener {
                                copyToClipboard("Hoarder Log Record", formatted.copyText)
                                true
                            }
                        }
                        container.addView(content)
                    } catch (e: Exception) {
                        showErrorView(container, "Error displaying entry: ${e.message}")
                    }
                }
            } else {
                showNoDataView(container)
            }
            updatePaginationControls(prevButton, nextButton, pageIndicator, copyPageButton, pageEntries.isNotEmpty())
        }

        fun refreshLogs() {
            logPaginator.setData(logRepository.getLogEntries(currentLogType))
            renderPage()
        }

        controlsContainer.visibility = android.view.View.VISIBLE
        refreshLogs()

        prevButton.setOnClickListener {
            logPaginator.prevPage()
            renderPage()
        }

        nextButton.setOnClickListener {
            logPaginator.nextPage()
            renderPage()
        }

        copyPageButton.setOnClickListener {
            copyCurrentPage()
        }

        refreshButton.setOnClickListener {
            refreshLogs()
        }

        val title = when(logType) {
            "cached" -> "Buffered Batch Uploads"
            "success" -> "Upload Log"
            "error" -> "Error Log"
            else -> "Log"
        }

        builder.setTitle(title)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun copyCurrentPage() {
        try {
            val pageEntries = logPaginator.getCurrentPageEntries()
            val allPageJson = pageEntries.joinToString(separator = ",\n\n") { entry ->
                logEntryFormatter.formatLogEntry(currentLogType, entry).copyText
            }

            copyToClipboard("Hoarder Page Log", allPageJson)
            ToastHelper.showToast(ctx, "Copied ${pageEntries.size} records from page", Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            ToastHelper.showToast(ctx, "Error copying page: ${e.message}", Toast.LENGTH_SHORT)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            ToastHelper.showToast(ctx, "Log entry copied to clipboard", Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            ToastHelper.showToast(ctx, "Failed to copy to clipboard", Toast.LENGTH_SHORT)
        }
    }

    private fun showErrorView(container: LinearLayout, message: String) {
        val errorView = TextView(ctx).apply {
            text = message
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.amoled_red))
            setPadding(0, 16, 0, 16)
        }
        container.addView(errorView)
    }

    private fun showNoDataView(container: LinearLayout) {
        val noDataView = TextView(ctx).apply {
            text = "No logs found for this page."
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.amoled_light_gray))
            gravity = Gravity.CENTER
            setPadding(0, 50, 0, 0)
        }
        container.addView(noDataView)
    }

    private fun updatePaginationControls(prevButton: Button, nextButton: Button, pageIndicator: TextView, copyPageButton: Button, hasData: Boolean) {
        pageIndicator.text = "Page ${logPaginator.currentPage + 1} of ${logPaginator.totalPages}"
        prevButton.isEnabled = logPaginator.hasPrevPage()
        nextButton.isEnabled = logPaginator.hasNextPage()
        copyPageButton.isEnabled = hasData
    }
}