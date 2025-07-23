package com.example.hoarder.ui.managers

import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.dialogs.PrecisionChooserDialog

class PrecisionSettingsManager(private val a: AppCompatActivity, private val p: Prefs, private val onPrecisionChanged: () -> Unit) {

    private val precisionChooser by lazy { PrecisionChooserDialog(a, p, onPrecisionChanged) }

    fun setup() {
        a.findViewById<LinearLayout>(R.id.gpsPrecisionSetting).setOnClickListener { precisionChooser.showGpsPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.gpsAltitudePrecisionSetting).setOnClickListener { precisionChooser.showGpsAltitudePrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.rssiPrecisionSetting).setOnClickListener { precisionChooser.showRssiPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.batteryPrecisionSetting).setOnClickListener { precisionChooser.showBatteryPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.networkPrecisionSetting).setOnClickListener { precisionChooser.showNetworkPrecisionChooser() }
        a.findViewById<LinearLayout>(R.id.speedPrecisionSetting).setOnClickListener { precisionChooser.showSpeedPrecisionChooser() }
    }
}