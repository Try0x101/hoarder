package com.example.hoarder.ui

import android.view.View
import android.widget.TextView
import com.example.hoarder.R
import com.example.hoarder.utils.NetUtils
import com.example.hoarder.data.Prefs
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.util.Locale

class StatusManager(private val a: MainActivity, private val p: Prefs) {
    private val g by lazy { GsonBuilder().setPrettyPrinting().create() }

    fun updateDataCollectionUI(isActive: Boolean) {
        val subtitle = a.findViewById<TextView>(R.id.dataCollectionSubtitle)
        subtitle.text = if (isActive) "Active" else "Inactive"
    }

    fun updateUploadUI(isActive: Boolean, status: String?, message: String?, totalBytes: Long?, lastUploadBytes: Long?, bufferedSize: Long) {
        val statusView = a.findViewById<TextView>(R.id.serverUploadStatus)
        val bytesView = a.findViewById<TextView>(R.id.serverUploadBytes)

        if (!isActive) {
            statusView.text = "Inactive"
            bytesView.visibility = View.GONE
            return
        }

        val serverAddress = p.getServerAddress()
        if (!NetUtils.isValidIpPort(serverAddress)) {
            statusView.text = "Invalid Address"
            bytesView.visibility = View.GONE
            return
        }

        val statusText = formatStatusText(status, message, bufferedSize)
        statusView.text = statusText
        bytesView.text = "Uploaded: ${formatBytes(totalBytes ?: 0)}"
        bytesView.visibility = View.VISIBLE
    }

    private fun formatStatusText(status: String?, message: String?, bufferedSize: Long): String {
        return when {
            status == "Saving Locally" -> {
                var localStatus = "Saving locally: ${formatBytes(bufferedSize)}"
                if (bufferedSize > 5120) {
                    localStatus += "\nBuffer large, confirm send in settings."
                }
                localStatus
            }
            status?.startsWith("OK") == true || status == "No Change" || status == "Connecting" || status == "OK (Batch)" -> {
                var connectedStatus = "Connected"
                if (bufferedSize > 0) {
                    connectedStatus += " | Local: ${formatBytes(bufferedSize)}"
                }
                connectedStatus
            }
            status == "Network Error" && message == "Internet not accessible" -> {
                message
            }
            status == "HTTP Error" || status == "Network Error" -> {
                "Error (Check logs)"
            }
            else -> "Connected"
        }
    }

    fun updateRawJson(json: String?) {
        val jsonTextView = a.findViewById<TextView>(R.id.rawJsonTextView)
        if (json.isNullOrEmpty()) {
            jsonTextView.text = "Collection is inactive or waiting for data..."
            return
        }
        try {
            jsonTextView.text = g.toJson(JsonParser.parseString(json))
        } catch (e: Exception) {
            jsonTextView.text = "Error parsing JSON: ${e.message}"
        }
    }

    fun updateAllPrecisionLabels() {
        updatePrecisionLabel(R.id.gpsPrecisionValue, getGpsPrecisionString(p.getGPSPrecision()))
        updatePrecisionLabel(R.id.gpsAltitudePrecisionValue, getGpsAltitudePrecisionString(p.getGPSAltitudePrecision()))
        updatePrecisionLabel(R.id.rssiPrecisionValue, getRssiPrecisionString(p.getRSSIPrecision()))
        updatePrecisionLabel(R.id.batteryPrecisionValue, getBatteryPrecisionString(p.getBatteryPrecision()))
        updatePrecisionLabel(R.id.networkPrecisionValue, getNetworkPrecisionString(p.getNetworkPrecision()))
        updatePrecisionLabel(R.id.speedPrecisionValue, getSpeedPrecisionString(p.getSpeedPrecision()))
        updatePrecisionInfoVisibility()
    }

    private fun updatePrecisionLabel(id: Int, text: String) {
        a.findViewById<TextView>(id).text = text
    }

    private fun updatePrecisionInfoVisibility() {
        updateInfoVisibility(R.id.gpsPrecisionInfo, p.getGPSPrecision() == -1, "• If speed <4 km/h → round up to 1 km\n• If speed 4-40 km/h → round up to 20 m\n• If speed 40-140 km/h → round up to 100 m\n• If speed >140 km/h → round up to 1 km")
        updateInfoVisibility(R.id.gpsAltitudePrecisionInfo, p.getGPSAltitudePrecision() == -1, "• Below 100m: 10m precision\n• 100-1000m: 50m precision\n• Above 1000m: 100m precision")
        updateInfoVisibility(R.id.rssiPrecisionInfo, p.getRSSIPrecision() == -1, "• If signal worse than -110 dBm → show precise value\n• If signal worse than -90 dBm → round to nearest 5\n• If signal better than -90 dBm → round to nearest 10")
        updateInfoVisibility(R.id.batteryPrecisionInfo, p.getBatteryPrecision() == -1, "• If battery below 10% → show precise value\n• If battery 10-50% → round to nearest 5%\n• If battery above 50% → round to nearest 10%")
        updateInfoVisibility(R.id.networkPrecisionInfo, p.getNetworkPrecision() == 0, "• Below 2 Mbps → show decimal precision\n• 2-7 Mbps → round to nearest lower 1 Mbps\n• Above 7 Mbps → round to nearest lower 5 Mbps")
        updateInfoVisibility(R.id.speedPrecisionInfo, p.getSpeedPrecision() == -1, "• If speed <2 km/h → show 0\n• If speed <10 km/h → round to nearest 3 km/h\n• If speed ≥10 km/h → round to nearest 10 km/h")
    }

    private fun updateInfoVisibility(id: Int, show: Boolean, text: String) {
        val view = a.findViewById<TextView>(id)
        view.visibility = if (show) View.VISIBLE else View.GONE
        if (show) view.text = text
    }

    fun calculateUploadStats(): Triple<Long, Long, Long> {
        val records = a.getSharedPreferences("HoarderServicePrefs", android.content.Context.MODE_PRIVATE)
            .getStringSet("uploadRecords", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 60 * 60 * 1000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L

        var lastHourBytes = 0L
        var lastDayBytes = 0L
        var last7DaysBytes = 0L

        records.forEach { record ->
            val parts = record.split(":")
            if (parts.size == 2) {
                val timestamp = parts[0].toLongOrNull()
                val bytes = parts[1].toLongOrNull()
                if (timestamp != null && bytes != null) {
                    last7DaysBytes += bytes
                    if (timestamp >= oneDayAgo) {
                        lastDayBytes += bytes
                    }
                    if (timestamp >= oneHourAgo) {
                        lastHourBytes += bytes
                    }
                }
            }
        }
        return Triple(lastHourBytes, lastDayBytes, last7DaysBytes)
    }

    fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val e = (Math.log(b.toDouble()) / Math.log(1024.0)).toInt()
        val p = "KMGTPE"[e - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", b / Math.pow(1024.0, e.toDouble()), p)
    }

    private fun getGpsPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 20 -> "20m"; 100 -> "100m"; 1000 -> "1km"; 10000 -> "10km"; else -> "Unknown" }
    private fun getGpsAltitudePrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 2 -> "2m"; 10 -> "10m"; 25 -> "25m"; 50 -> "50m"; 100 -> "100m"; else -> "Unknown" }
    private fun getRssiPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 3 -> "3dBm"; 5 -> "5dBm"; 10 -> "10dBm"; else -> "Unknown" }
    private fun getBatteryPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 2 -> "2%"; 5 -> "5%"; 10 -> "10%"; else -> "Unknown" }
    private fun getNetworkPrecisionString(v: Int) = when(v) { 0 -> "Smart"; -2 -> "Float"; 1 -> "1Mbps"; 2 -> "2Mbps"; 5 -> "5Mbps"; else -> "Unknown" }
    private fun getSpeedPrecisionString(v: Int) = when(v) { -1 -> "Smart"; 0 -> "Maximum"; 1 -> "1km/h"; 3 -> "3km/h"; 5 -> "5km/h"; 10 -> "10km/h"; else -> "Unknown" }
}