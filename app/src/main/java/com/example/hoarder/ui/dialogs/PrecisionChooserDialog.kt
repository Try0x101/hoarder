package com.example.hoarder.ui.dialogs

import android.content.Context
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs

class PrecisionChooserDialog(private val context: Context, private val prefs: Prefs, private val onSelected: () -> Unit) {

    fun showGpsPrecisionChooser() = showChooser(
        "GPS Precision",
        arrayOf("Smart", "Maximum", "20 Meters", "100 Meters", "1 Kilometer", "10 Kilometers"),
        intArrayOf(-1, 0, 20, 100, 1000, 10000),
        prefs.getGPSPrecision()
    ) { prefs.setGPSPrecision(it) }

    fun showGpsAltitudePrecisionChooser() = showChooser(
        "GPS Altitude",
        arrayOf("Smart", "Maximum", "2 Meters", "10 Meters", "25 Meters", "50 Meters", "100 Meters"),
        intArrayOf(-1, 0, 2, 10, 25, 50, 100),
        prefs.getGPSAltitudePrecision()
    ) { prefs.setGPSAltitudePrecision(it) }

    fun showRssiPrecisionChooser() = showChooser(
        "RSSI",
        arrayOf("Smart", "Maximum", "3 dBm", "5 dBm", "10 dBm"),
        intArrayOf(-1, 0, 3, 5, 10),
        prefs.getRSSIPrecision()
    ) { prefs.setRSSIPrecision(it) }

    fun showBatteryPrecisionChooser() = showChooser(
        "Battery",
        arrayOf("Smart", "Maximum", "2 Percent", "5 Percent", "10 Percent"),
        intArrayOf(-1, 0, 2, 5, 10),
        prefs.getBatteryPrecision()
    ) { prefs.setBatteryPrecision(it) }

    fun showNetworkPrecisionChooser() = showChooser(
        "Network Speed",
        arrayOf("Smart", "Float", "1 Mbps", "2 Mbps", "5 Mbps"),
        intArrayOf(0, -2, 1, 2, 5),
        prefs.getNetworkPrecision()
    ) { prefs.setNetworkPrecision(it) }

    fun showSpeedPrecisionChooser() = showChooser(
        "Speed",
        arrayOf("Smart", "Maximum", "1 km/h", "3 km/h", "5 km/h", "10 km/h"),
        intArrayOf(-1, 0, 1, 3, 5, 10),
        prefs.getSpeedPrecision()
    ) { prefs.setSpeedPrecision(it) }

    private fun showChooser(title: String, options: Array<String>, values: IntArray, current: Int, onSelectedValue: (Int) -> Unit) {
        val checkedItem = values.indexOf(current).takeIf { it != -1 } ?: 0
        androidx.appcompat.app.AlertDialog.Builder(context, R.style.AlertDialogTheme)
            .setTitle(title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                onSelectedValue(values[which])
                onSelected()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}