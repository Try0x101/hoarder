package com.example.hoarder.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hoarder.R
import com.example.hoarder.data.storage.db.LogEntry
import com.example.hoarder.ui.dialogs.log.LogEntryFormatter
import com.example.hoarder.ui.dialogs.log.LogPaginator
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.utils.ToastHelper
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

        val logAdapter = LogAdapter(emptyList())
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

    private inner class LogAdapter(private var entries: List<LogEntry>) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            return LogViewHolder(container)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(entries[position], position)
        }

        override fun getItemCount(): Int = entries.size

        fun updateData(newEntries: List<LogEntry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        private inner class LogViewHolder(itemView: LinearLayout) : RecyclerView.ViewHolder(itemView) {
            private val header: TextView = TextView(ctx).apply {
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.amoled_white))
                setPadding(0, 0, 0, 8)
                itemView.addView(this)
            }

            private val content: TextView = TextView(ctx).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(ctx, R.color.amoled_light_gray))
                itemView.addView(this)
            }

            fun bind(entry: LogEntry, position: Int) {
                (itemView.layoutParams as? RecyclerView.LayoutParams)?.topMargin = if (position > 0) 24 else 0
                try {
                    val formatted = logEntryFormatter.formatLogEntry(entry)
                    header.text = formatted.header
                    content.text = formatted.content
                    itemView.setOnLongClickListener {
                        copyToClipboard("Hoarder Log Record", formatted.copyText)
                        true
                    }
                } catch (e: Exception) {
                    header.text = "Format Error"
                    content.text = e.message ?: "Could not display entry."
                }
            }
        }
    }
}