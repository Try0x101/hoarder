// SpinnerManager.kt
package com.example.hoarder

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView

class SpinnerManager(private val context: MainActivity) {

    // Generic spinner setup function
    fun <T> setupSpinner(
        spinner: Spinner,
        options: Array<T>,
        currentValue: Int,
        onChanged: (Int) -> Unit,
        valueMapper: (Int) -> Int
    ) {
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(valueMapper(currentValue))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onChanged(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // Update info text helper
    fun updateInfoText(textView: TextView, show: Boolean, text: String) {
        textView.text = text
        textView.visibility = if (show) View.VISIBLE else View.GONE
    }

    // Setup all spinners in one function
    fun setupAllSpinners(spinnerData: SpinnerData, prefs: Prefs) {
        // GPS Precision Spinner
        setupSpinner(
            spinnerData.gpsSpinner,
            arrayOf("Smart GPS Precision", "Maximum precision", "20 m", "100 m", "1 km", "10 km"),
            prefs.getGPSPrecision(),
            { pos ->
                val nv = when(pos) { 0 -> -1; 1 -> 0; 2 -> 20; 3 -> 100; 4 -> 1000; 5 -> 10000; else -> -1 }
                prefs.setGPSPrecision(nv)
                updateInfoText(
                    spinnerData.gpsInfo,
                    pos == 0,
                    "• If speed <4 km/h → round up to 1 km\n• If speed 4-40 km/h → round up to 20 m\n• If speed 40-140 km/h → round up to 100 m\n• If speed >140 km/h → round up to 1 km"
                )
            },
            { v -> when(v) { -1 -> 0; 0 -> 1; 20 -> 2; 100 -> 3; 1000 -> 4; 10000 -> 5; else -> 0 } }
        )

        // GPS Altitude Precision Spinner
        setupSpinner(
            spinnerData.gpsAltSpinner,
            arrayOf("Smart Altitude Precision", "Maximum Precision", "25 meters", "50 meters", "100 meters"),
            prefs.getGPSAltitudePrecision(),
            { pos ->
                val nv = when(pos) { 0 -> -1; 1 -> 0; 2 -> 25; 3 -> 50; 4 -> 100; else -> -1 }
                prefs.setGPSAltitudePrecision(nv)

                val infoText = when(pos) {
                    0 -> "• Below 100m: 25m precision\n• 100-1000m: 50m precision\n• Above 1000m: 100m precision\n• Uses Kalman filter to combine GPS and barometer data"
                    1 -> "• Shows exact altitude value from Kalman filter\n• Combines GPS and barometer data for maximum accuracy\n• No rounding applied"
                    else -> ""
                }
                updateInfoText(spinnerData.gpsAltInfo, pos < 2, infoText)
            },
            { v -> when(v) { -1 -> 0; 0 -> 1; 25 -> 2; 50 -> 3; 100 -> 4; else -> 0 } }
        )

        // RSSI Precision Spinner
        setupSpinner(
            spinnerData.rssiSpinner,
            arrayOf("Smart RSSI Precision", "Maximum precision", "3 dBm", "5 dBm", "10 dBm"),
            prefs.getRSSIPrecision(),
            { pos ->
                val nv = when(pos) { 0 -> -1; 1 -> 0; 2 -> 3; 3 -> 5; 4 -> 10; else -> -1 }
                prefs.setRSSIPrecision(nv)
                updateInfoText(
                    spinnerData.rssiInfo,
                    pos == 0,
                    "• If signal worse than -110 dBm → show precise value\n• If signal worse than -90 dBm → round to nearest 5\n• If signal better than -90 dBm → round to nearest 10"
                )
            },
            { v -> when(v) { -1 -> 0; 0 -> 1; 3 -> 2; 5 -> 3; 10 -> 4; else -> 0 } }
        )

        // Battery Precision Spinner
        setupSpinner(
            spinnerData.batterySpinner,
            arrayOf("Smart Battery Precision", "Maximum precision", "2%", "5%", "10%"),
            prefs.getBatteryPrecision(),
            { pos ->
                val nv = when(pos) { 0 -> -1; 1 -> 0; 2 -> 2; 3 -> 5; 4 -> 10; else -> -1 }
                prefs.setBatteryPrecision(nv)
                updateInfoText(
                    spinnerData.batteryInfo,
                    pos == 0,
                    "• If battery below 10% → show precise value\n• If battery 10-50% → round to nearest 5%\n• If battery above 50% → round to nearest 10%"
                )
            },
            { v -> when(v) { -1 -> 0; 0 -> 1; 2 -> 2; 5 -> 3; 10 -> 4; else -> 0 } }
        )

        // Network Precision Spinner
        setupSpinner(
            spinnerData.netSpinner,
            arrayOf("Smart Network Rounding", "Float Precision (0.0 Mbps)", "Round to 1 Mbps", "Round to 2 Mbps", "Round to 5 Mbps"),
            prefs.getNetworkPrecision(),
            { pos ->
                val nv = when(pos) { 0 -> 0; 1 -> -2; 2 -> 1; 3 -> 2; 4 -> 5; else -> 0 }
                prefs.setNetworkPrecision(nv)

                val infoText = when(pos) {
                    0 -> "• Below 2 Mbps → show decimal precision (e.g., 1.5 Mbps)\n• 2-7 Mbps → round to nearest lower 1 Mbps\n• Above 7 Mbps → round to nearest lower 5 Mbps"
                    1 -> "• Shows all network speeds with decimal precision (e.g., 0.3 Mbps)\n• Useful for accurately measuring all connections"
                    else -> ""
                }
                updateInfoText(spinnerData.netInfo, pos < 2, infoText)
            },
            { v -> when(v) { 0 -> 0; -2 -> 1; 1 -> 2; 2 -> 3; 5 -> 4; else -> 0 } }
        )

        // Speed Precision Spinner
        setupSpinner(
            spinnerData.speedSpinner,
            arrayOf("Smart Speed Rounding", "Maximum precision", "1 km/h", "3 km/h", "5 km/h", "10 km/h"),
            prefs.getSpeedPrecision(),
            { pos ->
                val nv = when(pos) { 0 -> -1; 1 -> 0; 2 -> 1; 3 -> 3; 4 -> 5; 5 -> 10; else -> -1 }
                prefs.setSpeedPrecision(nv)
                updateInfoText(
                    spinnerData.speedInfo,
                    pos == 0,
                    "• If speed <2 km/h → show 0\n• If speed <10 km/h → round to nearest 3 km/h\n• If speed ≥10 km/h → round to nearest 10 km/h"
                )
            },
            { v -> when(v) { -1 -> 0; 0 -> 1; 1 -> 2; 3 -> 3; 5 -> 4; 10 -> 5; else -> 0 } }
        )

        // Barometer Precision Spinner
        setupSpinner(
            spinnerData.baroSpinner,
            arrayOf("Smart Barometer Altitude", "Actual Pressure (hPa)", "2 meters", "5 meters", "10 meters", "20 meters", "50 meters", "100 meters"),
            prefs.getBarometerPrecision(),
            { pos ->
                val nv = when(pos) { 0 -> -1; 1 -> 0; 2 -> 2; 3 -> 5; 4 -> 10; 5 -> 20; 6 -> 50; 7 -> 100; else -> -1 }
                prefs.setBarometerPrecision(nv)
                updateInfoText(
                    spinnerData.baroInfo,
                    pos == 0,
                    "• If barometer altitude is below -10 meters → show exact value\n• Otherwise → show minimum 0, rounded to lowest 5 meters"
                )
            },
            { v -> when(v) { -1 -> 0; 0 -> 1; 2 -> 2; 5 -> 3; 10 -> 4; 20 -> 5; 50 -> 6; 100 -> 7; else -> 0 } }
        )
    }
}

// Data class to hold all spinner and info TextView references
data class SpinnerData(
    val gpsSpinner: Spinner,
    val gpsInfo: TextView,
    val gpsAltSpinner: Spinner,
    val gpsAltInfo: TextView,
    val rssiSpinner: Spinner,
    val rssiInfo: TextView,
    val batterySpinner: Spinner,
    val batteryInfo: TextView,
    val netSpinner: Spinner,
    val netInfo: TextView,
    val speedSpinner: Spinner,
    val speedInfo: TextView,
    val baroSpinner: Spinner,
    val baroInfo: TextView
)