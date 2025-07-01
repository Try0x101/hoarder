package com.example.hoarder.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.Prefs
import com.example.hoarder.utils.NetUtils
import com.example.hoarder.utils.ToastHelper

class DialogManager(private val a: MainActivity, private val p: Prefs, private val statusManager: StatusManager) {

    fun showServerSettingsDialog() {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_server_settings, null)
        builder.setView(view)

        val editText = view.findViewById<TextView>(R.id.serverIpPortEditText)
        editText.text = p.getServerAddress()

        val (lastHour, lastDay, last7Days) = statusManager.calculateUploadStats()
        view.findViewById<TextView>(R.id.statsLastHour).text = statusManager.formatBytes(lastHour)
        view.findViewById<TextView>(R.id.statsLastDay).text = statusManager.formatBytes(lastDay)
        view.findViewById<TextView>(R.id.statsLast7Days).text = statusManager.formatBytes(last7Days)

        val sendBufferButton = view.findViewById<Button>(R.id.sendBufferedDataButton)
        val viewCachedUploadLogButton = view.findViewById<Button>(R.id.viewCachedUploadLogButton)
        val viewSuccessLogButton = view.findViewById<Button>(R.id.viewSuccessLogButton)
        val viewErrorLogButton = view.findViewById<Button>(R.id.viewErrorLogButton)
        val servicePrefs = a.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)

        fun updateButtonsState() {
            val bufferSize = servicePrefs.getStringSet("data_buffer", emptySet())?.sumOf { it.toByteArray().size }?.toLong() ?: 0L
            if (bufferSize > 0) {
                sendBufferButton.visibility = android.view.View.VISIBLE
                sendBufferButton.text = "Send Buffered Data (${statusManager.formatBytes(bufferSize)})"
                sendBufferButton.isEnabled = true
            } else {
                sendBufferButton.visibility = android.view.View.GONE
            }
            val lastUploadFile = java.io.File(a.cacheDir, "last_upload_details.json")
            viewCachedUploadLogButton.visibility = if (lastUploadFile.exists()) android.view.View.VISIBLE else android.view.View.GONE
        }

        updateButtonsState()

        sendBufferButton.setOnClickListener {
            a.sendBuffer()
            it.isEnabled = false
            (it as Button).text = "Sending..."
        }

        viewCachedUploadLogButton.setOnClickListener { showDetailedLogDialog("cached") }
        viewSuccessLogButton.setOnClickListener { showDetailedLogDialog("success") }
        viewErrorLogButton.setOnClickListener { showDetailedLogDialog("error") }

        val dialog = builder.setTitle("Server Settings")
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        val uploadStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateButtonsState()
                val status = intent?.getStringExtra("status")
                when (status) {
                    "OK (Batch)" -> ToastHelper.showToast(a, "Buffered data sent successfully!", Toast.LENGTH_SHORT)
                    "HTTP Error", "Network Error" -> {
                        val message = intent.getStringExtra("message") ?: "Check logs for details."
                        ToastHelper.showToast(a, "Failed to send buffer: $message", Toast.LENGTH_LONG)
                    }
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveServerAddress(editText.text.toString(), dialog)
            }
            view.findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
                clearAllLogs()
                updateButtonsState()
                ToastHelper.showToast(a, "Logs cleared", Toast.LENGTH_SHORT)
            }
            LocalBroadcastManager.getInstance(a).registerReceiver(uploadStatusReceiver, IntentFilter("com.example.hoarder.UPLOAD_STATUS"))
        }

        dialog.setOnDismissListener {
            LocalBroadcastManager.getInstance(a).unregisterReceiver(uploadStatusReceiver)
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveServerAddress(editText.text.toString(), dialog)
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun saveServerAddress(addr: String, dialog: AlertDialog) {
        if (NetUtils.isValidIpPort(addr)) {
            p.setServerAddress(addr)
            ToastHelper.showToast(a, "Server address saved", Toast.LENGTH_SHORT)
            if (p.isDataUploadEnabled()) {
                a.stopUpload()
                a.startUpload(addr)
            }
            dialog.dismiss()
        } else {
            ToastHelper.showToast(a, "Invalid server IP:Port format", Toast.LENGTH_SHORT)
        }
    }

    private fun clearAllLogs() {
        val prefs = a.getSharedPreferences("HoarderServicePrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("error_logs")
            .remove("success_logs")
            .remove("uploadRecords")
            .remove("data_buffer")
            .apply()
        try {
            java.io.File(a.cacheDir, "last_upload_details.json").delete()
        } catch (e: Exception) {
        }
    }

    fun showDetailedLogDialog(logType: String) {
        val logViewer = LogViewer(a)
        logViewer.showLogDialog(logType)
    }
}