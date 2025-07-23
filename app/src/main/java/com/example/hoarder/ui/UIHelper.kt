package com.example.hoarder.ui

import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.state.UploadState

class UIHelper(private val a: MainActivity, private val p: Prefs) {
    private val layoutManager = LayoutManager(a, p) { updateAllPrecisionLabels() }
    private val statusManager = StatusManager(a, p)
    private val dialogManager = DialogManager(a, p)

    fun setupUI() {
        layoutManager.setupUI()
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
        layoutManager.updateJson(json)
    }

    fun updateAllPrecisionLabels() {
        statusManager.updateAllPrecisionLabels()
    }
}