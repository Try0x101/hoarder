package com.example.hoarder.ui.dialogs.log

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.hoarder.R
import com.example.hoarder.data.models.LogEntry

class LogAdapter(
    private val context: Context,
    private var entries: List<LogEntry>,
    private val logEntryFormatter: LogEntryFormatter,
    private val onEntryLongClick: (String) -> Unit
) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val container = LinearLayout(context).apply {
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

    inner class LogViewHolder(itemView: LinearLayout) : RecyclerView.ViewHolder(itemView) {
        private val header: TextView = TextView(context).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.amoled_white))
            setPadding(0, 0, 0, 8)
            itemView.addView(this)
        }

        private val content: TextView = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(context, R.color.amoled_light_gray))
            itemView.addView(this)
        }

        fun bind(entry: LogEntry, position: Int) {
            (itemView.layoutParams as? RecyclerView.LayoutParams)?.topMargin = if (position > 0) 24 else 0
            try {
                val formatted = logEntryFormatter.formatLogEntry(entry)
                header.text = formatted.header
                content.text = formatted.content
                itemView.setOnLongClickListener {
                    onEntryLongClick(formatted.copyText)
                    true
                }
            } catch (e: Exception) {
                Log.e("LogAdapter", "Failed to format log entry", e)
                header.text = "Format Error"
                content.text = e.message ?: "Could not display entry."
            }
        }
    }
}