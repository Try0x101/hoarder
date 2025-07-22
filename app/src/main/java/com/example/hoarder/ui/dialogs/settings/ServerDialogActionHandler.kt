package com.example.hoarder.ui.dialogs.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.MainActivity
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.ui.dialogs.log.LogViewer
import com.example.hoarder.ui.formatters.ByteFormatter
import com.example.hoarder.ui.service.ServiceCommander
import kotlinx.coroutines.launch

class ServerDialogActionHandler(
    private val a: MainActivity,
    private val p: Prefs,
    private val view: View,
    private val logRepository: LogRepository,
    private val statsViewManager: ServerStatsViewManager
) {
    private val sendBufferButton: Button = view.findViewById(R.id.sendBufferedDataButton)
    private val batchSettingsDialogHandler by lazy { BatchSettingsDialogHandler(a, p) }

    private val uploadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ServiceCommander.ACTION_UPLOAD_STATUS) {
                updateSendButtonState()
            }
        }
    }

    fun setupListeners() {
        sendBufferButton.setOnClickListener {
            a.sendBuffer()
            it.isEnabled = false
            (it as Button).text = "Sending..."
        }

        view.findViewById<Button>(R.id.batchingSettingsButton).setOnClickListener {
            batchSettingsDialogHandler.show()
        }
        view.findViewById<Button>(R.id.viewCachedUploadLogButton).setOnClickListener { showDetailedLogDialog("cached") }
        view.findViewById<Button>(R.id.viewSuccessLogButton).setOnClickListener { showDetailedLogDialog("success") }
        view.findViewById<Button>(R.id.viewErrorLogButton).setOnClickListener { showDetailedLogDialog("error") }

        view.findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
            a.lifecycleScope.launch {
                logRepository.clearAllLogs()
                LocalBroadcastManager.getInstance(a).sendBroadcast(Intent(ServiceCommander.ACTION_GET_STATE))
                statsViewManager.updateStats()
            }
        }
        updateSendButtonState()
    }

    fun registerReceiver() {
        LocalBroadcastManager.getInstance(a).registerReceiver(uploadStatusReceiver, IntentFilter(ServiceCommander.ACTION_UPLOAD_STATUS))
    }

    fun unregisterReceiver() {
        LocalBroadcastManager.getInstance(a).unregisterReceiver(uploadStatusReceiver)
    }

    private fun updateSendButtonState() {
        val bufferSize = a.viewModel.bufferedDataSize.value ?: 0L
        val bulkInProgress = a.viewModel.isBulkInProgress.value ?: false

        if (bulkInProgress) {
            sendBufferButton.visibility = View.VISIBLE
            sendBufferButton.text = "Bulk upload in progress..."
            sendBufferButton.isEnabled = false
        } else if (bufferSize > 0) {
            sendBufferButton.visibility = View.VISIBLE
            sendBufferButton.text = "Send Buffered Data (${ByteFormatter.format(bufferSize)})"
            sendBufferButton.isEnabled = true
        } else {
            sendBufferButton.visibility = View.GONE
        }
    }

    private fun showDetailedLogDialog(logType: String) {
        LogViewer(a, logRepository).showLogDialog(logType)
    }
}