package com.example.hoarder.ui

import android.widget.TextView
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.managers.MainSwitchManager
import com.example.hoarder.ui.managers.PowerSettingsManager
import com.example.hoarder.ui.managers.PrecisionSettingsManager
import com.example.hoarder.ui.managers.SectionToggleManager
import com.example.hoarder.ui.state.UploadState

class UIHelper(
    private val a: TelemetrySettingsActivity,
    private val p: Prefs,
    private val switchCallbacks: MainSwitchManager.MainSwitchCallbacks,
    private val powerModeChangedCallback: (Int) -> Unit
) {
    private val statusManager = StatusManager(a, p)
    private val dialogManager = DialogManager(a, p)
    private val mainSwitchManager = MainSwitchManager(a, p, switchCallbacks)
    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    private val jsonContent: TextView by lazy { a.findViewById(R.id.jsonContent) }
    private val sectionToggleManager = SectionToggleManager(a) { updateRawJson(a.getLastData()) }
    private val precisionSettingsManager = PrecisionSettingsManager(a, p) { statusManager.updateAllPrecisionLabels() }
    private val powerSettingsManager = PowerSettingsManager(a, p) { newMode -> powerModeChangedCallback(newMode) }

    fun setupUI() {
        mainSwitchManager.setup()
        sectionToggleManager.setup()
        precisionSettingsManager.setup()
        powerSettingsManager.setup()
        statusManager.updateDataCollectionUI(p.isDataCollectionEnabled())
        statusManager.updateUploadUI(p.isDataUploadEnabled(), UploadState())
        statusManager.updateAllPrecisionLabels()
        setupServerRowListener()
    }

    private fun setupServerRowListener() {
        val serverUploadRow = a.findViewById<android.widget.RelativeLayout>(R.id.serverUploadRow)
        serverUploadRow.setOnClickListener {
            dialogManager.showServerSettingsDialog()
        }
    }

    fun updateDataCollectionUI(isActive: Boolean) {
        statusManager.updateDataCollectionUI(isActive)
    }

    fun updateUploadUI(isActive: Boolean, state: UploadState) {
        statusManager.updateUploadUI(isActive, state)
    }

    fun updateRawJson(json: String?) {
        val prettyJson = if (json.isNullOrEmpty() || json.startsWith("Collection is inactive")) {
            "Collection is inactive or waiting for data..."
        } else {
            try {
                gson.toJson(com.google.gson.JsonParser.parseString(json))
            } catch (e: Exception) { json }
        }
        jsonContent.text = prettyJson
    }
}