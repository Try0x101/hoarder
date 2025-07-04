package com.example.hoarder.ui.dialogs.log

import com.example.hoarder.data.storage.db.LogEntry
import kotlin.math.ceil

class LogPaginator(private val recordsPerPage: Int = 20) {
    private var logEntries: List<LogEntry> = emptyList()
    var currentPage: Int = 0
        private set
    var totalPages: Int = 1
        private set

    fun setData(entries: List<LogEntry>) {
        logEntries = entries
        totalPages = if (entries.isEmpty()) 1 else ceil(entries.size.toDouble() / recordsPerPage).toInt()
        currentPage = 0
    }

    fun getCurrentPageEntries(): List<LogEntry> {
        if (logEntries.isEmpty()) return emptyList()
        val startIndex = currentPage * recordsPerPage
        val endIndex = (startIndex + recordsPerPage).coerceAtMost(logEntries.size)
        return if (startIndex < logEntries.size) logEntries.subList(startIndex, endIndex) else emptyList()
    }

    fun hasNextPage(): Boolean = currentPage < totalPages - 1
    fun hasPrevPage(): Boolean = currentPage > 0

    fun nextPage() {
        if (hasNextPage()) currentPage++
    }

    fun prevPage() {
        if (hasPrevPage()) currentPage--
    }
}