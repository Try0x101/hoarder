package com.example.hoarder.ui.formatters

object StatusFormatter {
    fun formatStatusText(status: String?, message: String?, bufferedSize: Long): String {
        val formattedBuffer = if (bufferedSize > 0) " | Local: ${ByteFormatter.format(bufferedSize)}" else ""

        return when {
            status == "Saving Locally" -> {
                var localStatus = "Saving locally" + formattedBuffer
                if (bufferedSize > 5120) {
                    localStatus += "\nBuffer large, confirm send in settings."
                }
                localStatus
            }
            status?.startsWith("OK") == true || status == "No Change" || status == "Connecting" || status == "OK (Batch)" -> {
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