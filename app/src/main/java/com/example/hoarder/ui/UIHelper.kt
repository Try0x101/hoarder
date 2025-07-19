package com.example.hoarder.ui

import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs

class UIHelper(private val a: MainActivity, private val p: Prefs) {
    private val layoutManager = LayoutManager(a, p) { updateAllPrecisionLabels() }
    private val statusManager = StatusManager(a, p)
    private val dialogManager = DialogManager(a, p)

    fun setupUI() {
        layoutManager.setupUI()
        statusManager.updateDataCollectionUI(p.isDataCollectionEnabled())
        statusManager.updateUploadUI(p.isDataUploadEnabled(), null, null, null, null, 0L)
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

    fun updateUploadUI(isActive: Boolean, status: String?, message: String?, totalBytes: Long?, actualBytes: Long?, bufferedSize: Long) {
        statusManager.updateUploadUI(isActive, status, message, totalBytes, actualBytes, bufferedSize)
    }

    fun updateRawJson(json: String?) {
        layoutManager.updateJson(json)
    }

    fun updateAllPrecisionLabels() {
        statusManager.updateAllPrecisionLabels()
    }
}