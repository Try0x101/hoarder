package com.example.hoarder.ui.dialogs.settings

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.TelemetrySettingsActivity
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.ui.dialogs.server.ServerStatsManager

class ServerSettingsDialogHandler(
    private val a: TelemetrySettingsActivity,
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

        val serverIpPortEditText = view.findViewById<EditText>(R.id.serverIpPortEditText)
        val changeServerButton = view.findViewById<Button>(R.id.changeServerButton)
        val bufferThresholdEditText = view.findViewById<EditText>(R.id.bufferWarningThresholdEditText)
        val bulkThresholdEditText = view.findViewById<EditText>(R.id.bulkUploadThresholdEditText)
        val advancedSettingsButton = view.findViewById<Button>(R.id.advancedSettingsButton)
        val advancedSettingsContainer = view.findViewById<LinearLayout>(R.id.advancedSettingsContainer)

        serverIpPortEditText.setText(p.getServerAddress())
        serverIpPortEditText.isEnabled = false

        changeServerButton.setOnClickListener {
            if (serverIpPortEditText.isEnabled) {
                val newAddress = serverIpPortEditText.text.toString()
                if (NetUtils.isValidServerAddress(newAddress)) {
                    p.setServerAddress(newAddress)
                    if (p.isDataUploadEnabled()) {
                        a.stopUpload()
                        a.startUpload(newAddress)
                    }
                    serverIpPortEditText.isEnabled = false
                    changeServerButton.text = "Change"
                    Toast.makeText(a, "Server address saved.", Toast.LENGTH_SHORT).show()
                } else {
                    serverIpPortEditText.error = "Invalid address format"
                }
            } else {
                AlertDialog.Builder(a, R.style.AlertDialogTheme)
                    .setTitle("Change Server Address")
                    .setMessage("Changing the server address will restart the upload process if it's active. Are you sure?")
                    .setPositiveButton("Yes") { _, _ ->
                        serverIpPortEditText.isEnabled = true
                        serverIpPortEditText.requestFocus()
                        changeServerButton.text = "Save"
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }

        advancedSettingsButton.setOnClickListener {
            val isVisible = advancedSettingsContainer.visibility == View.VISIBLE
            advancedSettingsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
            advancedSettingsButton.text = if (isVisible) "Advanced Settings..." else "Hide Advanced Settings"
        }

        statsViewManager.loadInitialStats()
        actionHandler.setupListeners()

        bufferThresholdEditText.setText(p.getBufferWarningThresholdKb().toString())
        bulkThresholdEditText.setText(p.getBulkUploadThresholdKb().toString())

        val dialog = builder.setTitle("Server Settings")
            .setPositiveButton("Save Advanced") { _, _ ->
                p.setBufferWarningThresholdKb(bufferThresholdEditText.text.toString().toIntOrNull() ?: p.getBufferWarningThresholdKb())
                p.setBulkUploadThresholdKb(bulkThresholdEditText.text.toString().toIntOrNull() ?: p.getBulkUploadThresholdKb())
                Toast.makeText(a, "Advanced settings saved.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener { actionHandler.registerReceiver() }
        dialog.setOnDismissListener { actionHandler.unregisterReceiver() }
        dialog.show()
    }
}