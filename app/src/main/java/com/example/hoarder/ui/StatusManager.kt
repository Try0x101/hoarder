package com.example.hoarder.ui

import android.view.View
import android.widget.TextView
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.formatters.ByteFormatter
import com.example.hoarder.ui.formatters.PrecisionFormatter
import com.example.hoarder.ui.formatters.StatusFormatter

class StatusManager(private val a: MainActivity, private val p: Prefs) {

    fun updateDataCollectionUI(isActive: Boolean) {
        val subtitle = a.findViewById<TextView>(R.id.dataCollectionSubtitle)
        subtitle.text = if (isActive) "Active" else "Inactive"
    }

    fun updateUploadUI(isActive: Boolean, status: String?, message: String?, totalBytes: Long?, actualBytes: Long?, bufferedSize: Long) {
        val statusView = a.findViewById<TextView>(R.id.serverUploadStatus)
        val bytesView = a.findViewById<TextView>(R.id.serverUploadBytes)

        if (!isActive) {
            statusView.text = "Inactive"
            bytesView.visibility = View.GONE
            return
        }

        if (!NetUtils.isValidServerAddress(p.getServerAddress())) {
            statusView.text = "Invalid Address"
            bytesView.visibility = View.GONE
            return
        }

        val bufferWarningThreshold = p.getBufferWarningThresholdKb()
        statusView.text = StatusFormatter.formatStatusText(status, message, bufferedSize, bufferWarningThreshold)

        if (actualBytes != null && actualBytes > 0) {
            bytesView.text = "Uploaded: ${ByteFormatter.format(totalBytes ?: 0)} / ${ByteFormatter.format(actualBytes)}"
        } else {
            bytesView.text = "Uploaded: ${ByteFormatter.format(totalBytes ?: 0)}"
        }
        bytesView.visibility = View.VISIBLE
    }

    fun updateRawJson(json: String?) {
    }

    fun updateAllPrecisionLabels() {
        updatePrecisionLabel(R.id.gpsPrecisionValue, PrecisionFormatter.getGpsPrecisionString(p.getGPSPrecision()))
        updatePrecisionLabel(R.id.gpsAltitudePrecisionValue, PrecisionFormatter.getGpsAltitudePrecisionString(p.getGPSAltitudePrecision()))
        updatePrecisionLabel(R.id.rssiPrecisionValue, PrecisionFormatter.getRssiPrecisionString(p.getRSSIPrecision()))
        updatePrecisionLabel(R.id.batteryPrecisionValue, PrecisionFormatter.getBatteryPrecisionString(p.getBatteryPrecision()))
        updatePrecisionLabel(R.id.networkPrecisionValue, PrecisionFormatter.getNetworkPrecisionString(p.getNetworkPrecision()))
        updatePrecisionLabel(R.id.speedPrecisionValue, PrecisionFormatter.getSpeedPrecisionString(p.getSpeedPrecision()))
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
}