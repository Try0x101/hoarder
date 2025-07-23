package com.example.hoarder.ui.dialogs.settings

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.MainActivity
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.ui.dialogs.server.ServerStatsManager

class ServerSettingsDialogHandler(
    private val a: MainActivity,
    private val p: Prefs,
    private val logRepository: LogRepository
) {
    fun show() {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_server_settings, null)
        builder.setView(view)

        val serverStatsManager = ServerStatsManager(a)
        val statsViewManager = ServerStatsViewManager(view, a.lifecycleScope, serverStatsManager)
        val actionHandler = ServerDialogActionHandler(a, p, view, logRepository, statsViewManager)

        statsViewManager.loadInitialStats()
        actionHandler.setupListeners()

        val editText = view.findViewById<TextView>(R.id.serverIpPortEditText)
        val bufferThresholdEditText = view.findViewById<EditText>(R.id.bufferWarningThresholdEditText)
        val bulkThresholdEditText = view.findViewById<EditText>(R.id.bulkUploadThresholdEditText)

        editText.text = p.getServerAddress()
        bufferThresholdEditText.setText(p.getBufferWarningThresholdKb().toString())
        bulkThresholdEditText.setText(p.getBulkUploadThresholdKb().toString())

        val dialog = builder.setTitle("Server Settings")
            .setPositiveButton("Save") { _, _ ->
                p.setBufferWarningThresholdKb(bufferThresholdEditText.text.toString().toIntOrNull() ?: p.getBufferWarningThresholdKb())
                p.setBulkUploadThresholdKb(bulkThresholdEditText.text.toString().toIntOrNull() ?: p.getBulkUploadThresholdKb())
            }
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener { actionHandler.registerReceiver() }
        dialog.setOnDismissListener { actionHandler.unregisterReceiver() }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (NetUtils.isValidServerAddress(editText.text.toString())) {
                    p.setServerAddress(editText.text.toString())
                    if (p.isDataUploadEnabled()) {
                        a.stopUpload()
                        a.startUpload(p.getServerAddress())
                    }
                }
                true
            } else { false }
        }
        dialog.show()
    }
}