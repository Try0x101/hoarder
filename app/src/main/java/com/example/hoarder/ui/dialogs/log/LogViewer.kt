package com.example.hoarder.ui.dialogs.log

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hoarder.R
import com.example.hoarder.ui.MainActivity
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.launch

class LogViewer(private val ctx: Context, private val logRepository: LogRepository) {
    private val gson by lazy {
        GsonBuilder()
            .setPrettyPrinting()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create()
    }
    private val logEntryFormatter = LogEntryFormatter(gson)
    private val logPaginator = LogPaginator()
    private var currentLogType: String = ""
    private var noDataView: TextView? = null
    private lateinit var activity: MainActivity

    init {
        if (ctx is MainActivity) {
            activity = ctx
        } else {
            throw IllegalStateException("LogViewer must be initialized with MainActivity context")
        }
    }

    fun showLogDialog(logType: String) {
        currentLogType = logType
        val builder = AlertDialog.Builder(ctx, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_log_viewer, null)
        val recyclerView = view.findViewById<RecyclerView>(R.id.logRecyclerView)
        val controlsContainer = view.findViewById<LinearLayout>(R.id.controlsContainer)
        val prevButton = view.findViewById<Button>(R.id.prevButton)
        val nextButton = view.findViewById<Button>(R.id.nextButton)
        val pageIndicator = view.findViewById<TextView>(R.id.pageIndicator)
        val copyPageButton = view.findViewById<Button>(R.id.copyPageButton)
        val refreshButton = view.findViewById<Button>(R.id.refreshButton)
        builder.setView(view)

        val logAdapter = LogAdapter(ctx, emptyList(), logEntryFormatter) { copyText ->
            copyToClipboard("Hoarder Log Record", copyText)
        }
        recyclerView.layoutManager = LinearLayoutManager(ctx)
        recyclerView.adapter = logAdapter

        fun renderPage() {
            val pageEntries = logPaginator.getCurrentPageEntries()
            updateNoDataView(view as ViewGroup, pageEntries.isEmpty())
            recyclerView.visibility = if (pageEntries.isEmpty()) View.GONE else View.VISIBLE
            logAdapter.updateData(pageEntries)
            updatePaginationControls(prevButton, nextButton, pageIndicator, copyPageButton, pageEntries.isNotEmpty())
        }

        fun refreshLogs() {
            activity.lifecycleScope.launch {
                logPaginator.setData(logRepository.getLogEntries(currentLogType))
                renderPage()
            }
        }

        controlsContainer.visibility = View.VISIBLE
        refreshLogs()

        prevButton.setOnClickListener { logPaginator.prevPage(); renderPage() }
        nextButton.setOnClickListener { logPaginator.nextPage(); renderPage() }
        copyPageButton.setOnClickListener { copyCurrentPage() }
        refreshButton.setOnClickListener { refreshLogs() }

        val title = when (logType) {
            "cached" -> "Batch Upload Log"
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
                logEntryFormatter.formatLogEntry(entry).copyText
            }
            copyToClipboard("Hoarder Page Log", allPageJson)
        } catch (e: Exception) {
            Log.e("LogViewer", "Error copying log page", e)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e("LogViewer", "Clipboard copy failed", e)
        }
    }

    private fun updateNoDataView(parent: ViewGroup, show: Boolean) {
        if (noDataView == null) {
            noDataView = TextView(ctx).apply {
                text = "No logs found."
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, R.color.amoled_light_gray))
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            }
            parent.addView(noDataView, 1)
        }
        noDataView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updatePaginationControls(prevButton: Button, nextButton: Button, pageIndicator: TextView, copyPageButton: Button, hasData: Boolean) {
        pageIndicator.text = "Page ${logPaginator.currentPage + 1} of ${logPaginator.totalPages}"
        prevButton.isEnabled = logPaginator.hasPrevPage()
        nextButton.isEnabled = logPaginator.hasNextPage()
        copyPageButton.isEnabled = hasData
    }
}