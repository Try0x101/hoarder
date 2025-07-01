package com.example.hoarder.ui.formatters

import java.util.Locale

object ByteFormatter {
    fun format(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val e = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val p = "KMGTPE"[e - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024.0, e.toDouble()), p)
    }
}