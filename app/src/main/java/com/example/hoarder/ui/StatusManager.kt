package com.example.hoarder.ui

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.formatters.ByteFormatter
import com.example.hoarder.ui.formatters.PrecisionFormatter
import com.example.hoarder.ui.formatters.StatusFormatter
import com.example.hoarder.ui.state.UploadState

class StatusManager(private val a: MainActivity, private val p: Prefs) {

    fun updateDataCollectionUI(isActive: Boolean) {
        val subtitle = a.findViewById<TextView>(R.id.dataCollectionSubtitle)
        subtitle.text = if (isActive) "Active" else "Inactive"
    }

    fun updateUploadUI(isActive: Boolean, state: UploadState) {
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
        statusView.text = StatusFormatter.formatStatusText(state.status, state.message, state.bufferedDataSize, bufferWarningThreshold)

        if (state.totalActualNetworkBytes > 0) {
            bytesView.text = "Uploaded: ${ByteFormatter.format(state.totalUploadedBytes)} / ${ByteFormatter.format(state.totalActualNetworkBytes)}"
        } else {
            bytesView.text = "Uploaded: ${ByteFormatter.format(state.totalUploadedBytes)}"
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
        updateInfoVisibility(R.id.gpsPrecisionInfo, p.getGPSPrecision() == -1, R.string.gps_precision_info)
        updateInfoVisibility(R.id.gpsAltitudePrecisionInfo, p.getGPSAltitudePrecision() == -1, R.string.gps_altitude_precision_info)
        updateInfoVisibility(R.id.rssiPrecisionInfo, p.getRSSIPrecision() == -1, R.string.rssi_precision_info)
        updateInfoVisibility(R.id.batteryPrecisionInfo, p.getBatteryPrecision() == -1, R.string.battery_precision_info)
        updateInfoVisibility(R.id.networkPrecisionInfo, p.getNetworkPrecision() == 0, R.string.network_precision_info)
        updateInfoVisibility(R.id.speedPrecisionInfo, p.getSpeedPrecision() == -1, R.string.speed_precision_info)
    }

    private fun updateInfoVisibility(id: Int, show: Boolean, @StringRes textResId: Int) {
        val view = a.findViewById<TextView>(id)
        view.visibility = if (show) View.VISIBLE else View.GONE
        if (show) view.setText(textResId)
    }
}