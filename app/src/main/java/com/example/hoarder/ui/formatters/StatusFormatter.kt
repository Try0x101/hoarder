package com.example.hoarder.ui.formatters

object StatusFormatter {
    fun formatStatusText(status: String?, message: String?, bufferedSize: Long, bufferWarningThresholdKb: Int = 5): String {
        val formattedBuffer = if (bufferedSize > 0) " - Local: ${ByteFormatter.format(bufferedSize)}" else ""
        val thresholdBytes = bufferWarningThresholdKb * 1024L

        return when {
            status == "Saving Locally" -> {
                var localStatus = "Saving locally" + formattedBuffer
                if (bufferedSize > thresholdBytes) {
                    localStatus += "\nBuffer large, confirm send in settings."
                }
                localStatus
            }
            status == "Auto-sending" -> {
                "Auto-sending buffered data" + formattedBuffer
            }
            status == "OK (Batch)" -> {
                "Connected - Buffer uploaded successfully"
            }
            bufferedSize > 0 && (status?.startsWith("OK") == true || status == "No Change" || status == "Connecting") -> {
                "Connected" + formattedBuffer + "\nPending upload"
            }
            bufferedSize == 0L && (status?.startsWith("OK") == true || status == "No Change" || status == "Connecting") -> {
                "Connected"
            }
            status?.startsWith("OK") == true || status == "No Change" || status == "Connecting" -> {
                "Connected" + formattedBuffer
            }
            status == "Network Error" && message == "Internet not accessible" -> {
                (message ?: "Offline") + formattedBuffer
            }
            status == "HTTP Error" || status == "Network Error" -> {
                "Error (Check logs)" + formattedBuffer
            }
            else -> {
                "Connected" + formattedBuffer
            }
        }
    }
}