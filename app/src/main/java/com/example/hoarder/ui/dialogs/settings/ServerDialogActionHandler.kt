package com.example.hoarder.ui.dialogs.settings

import android.content.Intent
import android.view.View
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.ui.TelemetrySettingsActivity
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.ui.dialogs.log.LogViewer
import com.example.hoarder.ui.formatters.ByteFormatter
import com.example.hoarder.ui.service.ServiceCommander
import kotlinx.coroutines.launch

class ServerDialogActionHandler(
    private val a: TelemetrySettingsActivity,
    private val p: Prefs,
    private val view: View,
    private val logRepository: LogRepository,
    private val statsViewManager: ServerStatsViewManager
) {
    private val sendBufferButton: Button = view.findViewById(R.id.sendBufferedDataButton)
    private val batchSettingsDialogHandler by lazy { BatchSettingsDialogHandler(a, p) }

    fun setupListeners() {
        a.viewModel.uploadState.observe(a) { state ->
            updateSendButtonState()
        }

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
    }

    fun unregisterReceiver() {
    }

    private fun updateSendButtonState() {
        val state = a.viewModel.uploadState.value
        val bufferSize = state?.bufferedDataSize ?: 0L
        val bulkInProgress = state?.isBulkInProgress ?: false

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