package com.example.hoarder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import android.widget.Toast
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.utils.ToastHelper

class UIHelper(private val a: MainActivity, private val p: Prefs) {
    private val layoutManager = LayoutManager(a, p) { updateAllPrecisionLabels() }
    private val statusManager = StatusManager(a, p)
    private val dialogManager = DialogManager(a, p)

    fun setupUI() {
        layoutManager.setupUI()
        statusManager.updateDataCollectionUI(p.isDataCollectionEnabled())
        statusManager.updateUploadUI(p.isDataUploadEnabled(), null, null, null, null, 0L)
        statusManager.updateAllPrecisionLabels()
        setupJsonCopyListener()
        setupServerRowListener()
    }

    private fun setupJsonCopyListener() {
        val rawJsonTextView = a.findViewById<TextView>(R.id.rawJsonTextView)
        rawJsonTextView.setOnLongClickListener {
            val clipboard = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Hoarder JSON", rawJsonTextView.text)
            clipboard.setPrimaryClip(clip)
            ToastHelper.showToast(a, "JSON data copied to clipboard", Toast.LENGTH_SHORT)
            true
        }
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
        statusManager.updateRawJson(json)
    }

    fun updateAllPrecisionLabels() {
        statusManager.updateAllPrecisionLabels()
    }
}