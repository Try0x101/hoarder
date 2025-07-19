package com.example.hoarder.ui.formatters

object StatusFormatter {
    fun formatStatusText(status: String?, message: String?, bufferedSize: Long, bufferWarningThresholdKb: Int = 5): String {
        val baseStatus = when (status) {
            "Preparing", "Uploading", "Processing" -> "$status..."
            "OK (Bulk)" -> "Bulk upload complete"
            "OK (Batch)" -> "Connected - Batch uploaded"
            "Saving Locally" -> "Saving locally"
            "Auto-sending" -> "Auto-sending buffered data"
            "Error", "HTTP Error", "Network Error" -> "Error (Check logs)"
            else -> status ?: "Connecting"
        }

        val formattedBuffer = if (bufferedSize > 0) " - Local: ${ByteFormatter.format(bufferedSize)}" else ""

        val thresholdBytes = bufferWarningThresholdKb * 1024L
        val warningMessage = if (bufferedSize > thresholdBytes) "\nBuffer large, confirm send in settings." else ""

        return baseStatus + formattedBuffer + warningMessage
    }
}