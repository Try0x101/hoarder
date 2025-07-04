package com.example.hoarder.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.ui.formatters.ByteFormatter
import com.example.hoarder.utils.ToastHelper
import com.google.gson.Gson
import kotlinx.coroutines.launch

class DialogManager(private val a: MainActivity, private val p: Prefs) {

    private val logRepository by lazy { LogRepository(a, Gson()) }

    fun showServerSettingsDialog() {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_server_settings, null)
        builder.setView(view)

        val editText = view.findViewById<TextView>(R.id.serverIpPortEditText)
        editText.text = p.getServerAddress()

        view.findViewById<TextView>(R.id.statsLastHour).text = "N/A"
        view.findViewById<TextView>(R.id.statsLastDay).text = "N/A"
        view.findViewById<TextView>(R.id.statsLast7Days).text = "N/A"

        val sendBufferButton = view.findViewById<Button>(R.id.sendBufferedDataButton)
        val batchLogButton = view.findViewById<Button>(R.id.viewCachedUploadLogButton)
        val successLogButton = view.findViewById<Button>(R.id.viewSuccessLogButton)
        val errorLogButton = view.findViewById<Button>(R.id.viewErrorLogButton)
        val clearLogsButton = view.findViewById<Button>(R.id.clearLogsButton)

        batchLogButton.text = "View Batch Upload Log"

        fun updateButtonsState() {
            val bufferSize = a.viewModel.bufferedDataSize.value ?: 0L
            if (bufferSize > 0) {
                sendBufferButton.visibility = View.VISIBLE
                sendBufferButton.text = "Send Buffered Data (${ByteFormatter.format(bufferSize)})"
                sendBufferButton.isEnabled = true
            } else {
                sendBufferButton.visibility = View.GONE
            }
        }

        updateButtonsState()

        sendBufferButton.setOnClickListener {
            a.sendBuffer()
            it.isEnabled = false
            (it as Button).text = "Sending..."
        }

        batchLogButton.setOnClickListener { showDetailedLogDialog("cached") }
        successLogButton.setOnClickListener { showDetailedLogDialog("success") }
        errorLogButton.setOnClickListener { showDetailedLogDialog("error") }

        val dialog = builder.setTitle("Server Settings")
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        val uploadStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.hoarder.UPLOAD_STATUS") {
                    updateButtonsState()
                    val status = intent.getStringExtra("status")
                    when (status) {
                        "OK (Batch)" -> ToastHelper.showToast(a, "Buffered data sent successfully!", Toast.LENGTH_SHORT)
                        "HTTP Error", "Network Error" -> {
                            val message = intent.getStringExtra("message") ?: "Check logs for details."
                            ToastHelper.showToast(a, "Failed to send buffer: $message", Toast.LENGTH_LONG)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("com.example.hoarder.UPLOAD_STATUS")

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveServerAddress(editText.text.toString(), dialog)
            }
            clearLogsButton.setOnClickListener {
                a.lifecycleScope.launch {
                    logRepository.clearAllLogs()
                    LocalBroadcastManager.getInstance(a).sendBroadcast(Intent("com.example.hoarder.GET_STATE"))
                    ToastHelper.showToast(a, "Logs cleared", Toast.LENGTH_SHORT)
                }
            }
            LocalBroadcastManager.getInstance(a).registerReceiver(uploadStatusReceiver, filter)
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

    fun showDetailedLogDialog(logType: String) {
        val logViewer = LogViewer(a, logRepository)
        logViewer.showLogDialog(logType)
    }
}