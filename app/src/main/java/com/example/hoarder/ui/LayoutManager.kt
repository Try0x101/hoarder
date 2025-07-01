// app/src/main/java/com/example/hoarder/ui/LayoutManager.kt
package com.example.hoarder.ui

import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.example.hoarder.R
import com.example.hoarder.data.Prefs

class LayoutManager(private val a: MainActivity, private val p: Prefs) {
    private lateinit var dataCollectionHeader: RelativeLayout
    private lateinit var dataCollectionSwitch: Switch
    private lateinit var dataCollectionSubtitle: TextView
    private lateinit var dataCollectionArrow: ImageView
    private lateinit var dataCollectionContent: HorizontalScrollView
    private lateinit var rawJsonTextView: TextView
    private lateinit var dataDescriptionHeader: RelativeLayout
    private lateinit var dataDescriptionArrow: ImageView
    private lateinit var dataDescriptionContent: LinearLayout
    private lateinit var serverUploadRow: RelativeLayout
    private lateinit var serverUploadSwitch: Switch
    private lateinit var serverUploadStatus: TextView
    private lateinit var serverUploadBytes: TextView
    private lateinit var precisionSettingsHeader: RelativeLayout
    private lateinit var precisionSettingsArrow: ImageView
    private lateinit var precisionSettingsContent: LinearLayout

    fun setupUI() {
        findViews()
        setupState()
        setupListeners()
    }

    private fun findViews() {
        dataCollectionHeader = a.findViewById(R.id.dataCollectionHeader)
        dataCollectionSwitch = a.findViewById(R.id.dataCollectionSwitch)
        dataCollectionSubtitle = a.findViewById(R.id.dataCollectionSubtitle)
        dataCollectionArrow = a.findViewById(R.id.dataCollectionArrow)
        dataCollectionContent = a.findViewById(R.id.dataCollectionContent)
        rawJsonTextView = a.findViewById(R.id.rawJsonTextView)
        dataDescriptionHeader = a.findViewById(R.id.dataDescriptionHeader)
        dataDescriptionArrow = a.findViewById(R.id.dataDescriptionArrow)
        dataDescriptionContent = a.findViewById(R.id.dataDescriptionContent)
        serverUploadRow = a.findViewById(R.id.serverUploadRow)
        serverUploadSwitch = a.findViewById(R.id.serverUploadSwitch)
        serverUploadStatus = a.findViewById(R.id.serverUploadStatus)
        serverUploadBytes = a.findViewById(R.id.serverUploadBytes)
        precisionSettingsHeader = a.findViewById(R.id.precisionSettingsHeader)
        precisionSettingsArrow = a.findViewById(R.id.precisionSettingsArrow)
        precisionSettingsContent = a.findViewById(R.id.precisionSettingsContent)
    }

    private fun setupState() {
        dataCollectionSwitch.isChecked = p.isDataCollectionEnabled()
        serverUploadSwitch.isChecked = p.isDataUploadEnabled()
    }

    private fun setupListeners() {
        dataCollectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataCollectionEnabled(isChecked)
            if (isChecked) a.startCollection() else a.stopCollection()
        }

        dataCollectionHeader.setOnClickListener {
            toggleVisibility(dataCollectionContent, dataCollectionArrow)
        }

        dataDescriptionHeader.setOnClickListener {
            toggleVisibility(dataDescriptionContent, dataDescriptionArrow)
        }

        serverUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            p.setDataUploadEnabled(isChecked)
            if (isChecked) {
                val addr = p.getServerAddress()
                if (addr.isNotEmpty()) {
                    a.startUpload(addr)
                } else {
                    serverUploadSwitch.isChecked = false
                    p.setDataUploadEnabled(false)
                }
            } else {
                a.stopUpload()
            }
        }

        precisionSettingsHeader.setOnClickListener {
            toggleVisibility(precisionSettingsContent, precisionSettingsArrow)
        }

        setupPrecisionListeners()
        setupTextCopyListeners()
    }

    private fun setupPrecisionListeners() {
        a.findViewById<LinearLayout>(R.id.gpsPrecisionSetting).setOnClickListener {
            showGpsPrecisionChooser()
        }
        a.findViewById<LinearLayout>(R.id.gpsAltitudePrecisionSetting).setOnClickListener {
            showGpsAltitudePrecisionChooser()
        }
        a.findViewById<LinearLayout>(R.id.rssiPrecisionSetting).setOnClickListener {
            showRssiPrecisionChooser()
        }
        a.findViewById<LinearLayout>(R.id.batteryPrecisionSetting).setOnClickListener {
            showBatteryPrecisionChooser()
        }
        a.findViewById<LinearLayout>(R.id.networkPrecisionSetting).setOnClickListener {
            showNetworkPrecisionChooser()
        }
        a.findViewById<LinearLayout>(R.id.speedPrecisionSetting).setOnClickListener {
            showSpeedPrecisionChooser()
        }
    }

    private fun setupTextCopyListeners() {
        a.findViewById<TextView>(R.id.timestampInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Timestamp Info")
            true
        }
        a.findViewById<TextView>(R.id.precisionInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Precision Settings Info")
            true
        }
        a.findViewById<TextView>(R.id.fieldsInfoText)?.setOnLongClickListener {
            copyTextToClipboard(it as TextView, "Data Fields Reference")
            true
        }
    }

    fun toggleVisibility(content: android.view.View, arrow: ImageView) {
        val isVisible = content.visibility == android.view.View.VISIBLE
        content.visibility = if (isVisible) android.view.View.GONE else android.view.View.VISIBLE
        arrow.animate().rotation(if (isVisible) 0f else 180f).setDuration(300).start()
        if (content.id == R.id.dataCollectionContent && !isVisible) {
            rawJsonTextView.text = a.getLastData()
        }
    }

    private fun copyTextToClipboard(textView: TextView, label: String) {
        val clipboard = a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, textView.text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showGpsPrecisionChooser() = showChooser("GPS Precision", arrayOf("Smart", "Maximum", "20m", "100m", "1km", "10km"), intArrayOf(-1, 0, 20, 100, 1000, 10000), p.getGPSPrecision()) { p.setGPSPrecision(it) }
    private fun showGpsAltitudePrecisionChooser() = showChooser("GPS Altitude", arrayOf("Smart", "Maximum", "2m", "10m", "25m", "50m", "100m"), intArrayOf(-1, 0, 2, 10, 25, 50, 100), p.getGPSAltitudePrecision()) { p.setGPSAltitudePrecision(it) }
    private fun showRssiPrecisionChooser() = showChooser("RSSI", arrayOf("Smart", "Maximum", "3dBm", "5dBm", "10dBm"), intArrayOf(-1, 0, 3, 5, 10), p.getRSSIPrecision()) { p.setRSSIPrecision(it) }
    private fun showBatteryPrecisionChooser() = showChooser("Battery", arrayOf("Smart", "Maximum", "2%", "5%", "10%"), intArrayOf(-1, 0, 2, 5, 10), p.getBatteryPrecision()) { p.setBatteryPrecision(it) }
    private fun showNetworkPrecisionChooser() = showChooser("Network Speed", arrayOf("Smart", "Float", "1Mbps", "2Mbps", "5Mbps"), intArrayOf(0, -2, 1, 2, 5), p.getNetworkPrecision()) { p.setNetworkPrecision(it) }
    private fun showSpeedPrecisionChooser() = showChooser("Speed", arrayOf("Smart", "Maximum", "1km/h", "3km/h", "5km/h", "10km/h"), intArrayOf(-1, 0, 1, 3, 5, 10), p.getSpeedPrecision()) { p.setSpeedPrecision(it) }

    private fun showChooser(title: String, options: Array<String>, values: IntArray, current: Int, onSelected: (Int) -> Unit) {
        val checkedItem = values.indexOf(current).takeIf { it != -1 } ?: 0
        androidx.appcompat.app.AlertDialog.Builder(a, R.style.AlertDialogTheme)
            .setTitle(title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                onSelected(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}