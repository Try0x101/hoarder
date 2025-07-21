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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.dialogs.log.LogRepository
import com.example.hoarder.ui.dialogs.server.ServerStatsManager
import com.example.hoarder.ui.formatters.ByteFormatter
import com.google.gson.Gson
import kotlinx.coroutines.launch

class DialogManager(private val a: MainActivity, private val p: Prefs) {

    private val logRepository by lazy { LogRepository(a, Gson()) }
    private val serverStatsManager by lazy { ServerStatsManager(a) }

    fun showServerSettingsDialog() {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_server_settings, null)
        builder.setView(view)

        val editText = view.findViewById<TextView>(R.id.serverIpPortEditText)
        val bufferThresholdEditText = view.findViewById<EditText>(R.id.bufferWarningThresholdEditText)
        val bulkThresholdEditText = view.findViewById<EditText>(R.id.bulkUploadThresholdEditText)
        val statsLastHour = view.findViewById<TextView>(R.id.statsLastHour)
        val statsLastDay = view.findViewById<TextView>(R.id.statsLastDay)
        val statsLast7Days = view.findViewById<TextView>(R.id.statsLast7Days)
        val sendBufferButton = view.findViewById<Button>(R.id.sendBufferedDataButton)
        val batchLogButton = view.findViewById<Button>(R.id.viewCachedUploadLogButton)
        val successLogButton = view.findViewById<Button>(R.id.viewSuccessLogButton)
        val errorLogButton = view.findViewById<Button>(R.id.viewErrorLogButton)
        val clearLogsButton = view.findViewById<Button>(R.id.clearLogsButton)
        val batchingSettingsButton = view.findViewById<Button>(R.id.batchingSettingsButton)

        editText.text = p.getServerAddress()
        bufferThresholdEditText.setText(p.getBufferWarningThresholdKb().toString())
        bulkThresholdEditText.setText(p.getBulkUploadThresholdKb().toString())
        statsLastHour.text = "Calculating..."
        statsLastDay.text = "Calculating..."
        statsLast7Days.text = "Calculating..."

        batchingSettingsButton.setOnClickListener { showBatchSettingsDialog() }

        a.lifecycleScope.launch {
            val (lastHour, lastDay, last7Days) = serverStatsManager.calculateUploadStats()
            statsLastHour.text = formatStats(lastHour)
            statsLastDay.text = formatStats(lastDay)
            statsLast7Days.text = formatStats(last7Days)
        }

        batchLogButton.text = "View Batch Upload Log"

        fun updateButtonsState() {
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
            .setPositiveButton("Save") { _, _ ->
                val bufferWarnText = bufferThresholdEditText.text.toString()
                val bufferWarn = bufferWarnText.toIntOrNull() ?: 5
                p.setBufferWarningThresholdKb(bufferWarn)

                val bulkThresholdText = bulkThresholdEditText.text.toString()
                val bulkThreshold = bulkThresholdText.toIntOrNull() ?: 10240
                p.setBulkUploadThresholdKb(bulkThreshold)
            }
            .setNegativeButton("Close", null)
            .create()

        val uploadStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.hoarder.UPLOAD_STATUS") {
                    updateButtonsState()
                    val status = intent.getStringExtra("status")
                    when (status) {
                        "OK (Batch)", "OK (Bulk)" -> {}
                        "HTTP Error", "Network Error", "Error" -> {
                            val message = intent.getStringExtra("message") ?: "Check logs for details."
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("com.example.hoarder.UPLOAD_STATUS")

        dialog.setOnShowListener {
            clearLogsButton.setOnClickListener {
                a.lifecycleScope.launch {
                    logRepository.clearAllLogs()
                    LocalBroadcastManager.getInstance(a).sendBroadcast(Intent("com.example.hoarder.GET_STATE"))
                    val (lastHour, lastDay, last7Days) = serverStatsManager.calculateUploadStats()
                    statsLastHour.text = formatStats(lastHour)
                    statsLastDay.text = formatStats(lastDay)
                    statsLast7Days.text = formatStats(last7Days)
                }
            }
            LocalBroadcastManager.getInstance(a).registerReceiver(uploadStatusReceiver, filter)
        }

        dialog.setOnDismissListener {
            LocalBroadcastManager.getInstance(a).unregisterReceiver(uploadStatusReceiver)
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (NetUtils.isValidIpPort(editText.text.toString())) {
                    p.setServerAddress(editText.text.toString())
                    if (p.isDataUploadEnabled()) {
                        a.stopUpload()
                        a.startUpload(p.getServerAddress())
                    }
                } else {
                }
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun showBatchSettingsDialog() {
        val builder = AlertDialog.Builder(a, R.style.AlertDialogTheme)
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_batch_settings, null)

        val masterSwitch = view.findViewById<Switch>(R.id.masterBatchingSwitch)
        val optionsContainer = view.findViewById<LinearLayout>(R.id.advancedBatchingOptionsContainer)
        val triggerByCountSwitch = view.findViewById<Switch>(R.id.triggerByCountSwitch)
        val recordCountEditText = view.findViewById<EditText>(R.id.batchRecordCountEditText)
        val triggerByTimeoutSwitch = view.findViewById<Switch>(R.id.triggerByTimeoutSwitch)
        val timeoutEditText = view.findViewById<EditText>(R.id.batchTimeoutEditText)
        val triggerByMaxSizeSwitch = view.findViewById<Switch>(R.id.triggerByMaxSizeSwitch)
        val maxSizeEditText = view.findViewById<EditText>(R.id.batchMaxSizeEditText)
        val compressionLevelEditText = view.findViewById<EditText>(R.id.compressionLevelEditText)

        masterSwitch.isChecked = p.isBatchUploadEnabled()
        optionsContainer.visibility = if(masterSwitch.isChecked) View.VISIBLE else View.GONE

        triggerByCountSwitch.isChecked = p.isBatchTriggerByCountEnabled()
        recordCountEditText.setText(p.getBatchRecordCount().toString())
        recordCountEditText.isEnabled = triggerByCountSwitch.isChecked

        triggerByTimeoutSwitch.isChecked = p.isBatchTriggerByTimeoutEnabled()
        timeoutEditText.setText(p.getBatchTimeout().toString())
        timeoutEditText.isEnabled = triggerByTimeoutSwitch.isChecked

        triggerByMaxSizeSwitch.isChecked = p.isBatchTriggerByMaxSizeEnabled()
        maxSizeEditText.setText(p.getBatchMaxSizeKb().toString())
        maxSizeEditText.isEnabled = triggerByMaxSizeSwitch.isChecked

        compressionLevelEditText.setText(p.getCompressionLevel().toString())

        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            optionsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        triggerByCountSwitch.setOnCheckedChangeListener { _, isChecked -> recordCountEditText.isEnabled = isChecked }
        triggerByTimeoutSwitch.setOnCheckedChangeListener { _, isChecked -> timeoutEditText.isEnabled = isChecked }
        triggerByMaxSizeSwitch.setOnCheckedChangeListener { _, isChecked -> maxSizeEditText.isEnabled = isChecked }

        builder.setView(view)
            .setTitle("Live Batching and Compression")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { dialog, _ ->
                p.setBatchUploadEnabled(masterSwitch.isChecked)
                p.setBatchTriggerByCountEnabled(triggerByCountSwitch.isChecked)
                p.setBatchRecordCount(recordCountEditText.text.toString().toIntOrNull() ?: p.getBatchRecordCount())

                p.setBatchTriggerByTimeoutEnabled(triggerByTimeoutSwitch.isChecked)
                p.setBatchTimeout(timeoutEditText.text.toString().toIntOrNull() ?: p.getBatchTimeout())

                p.setBatchTriggerByMaxSizeEnabled(triggerByMaxSizeSwitch.isChecked)
                p.setBatchMaxSizeKb(maxSizeEditText.text.toString().toIntOrNull() ?: p.getBatchMaxSizeKb())

                p.setCompressionLevel(compressionLevelEditText.text.toString().toIntOrNull()?.coerceIn(0, 9) ?: p.getCompressionLevel())

                a.onBatchingSettingsChanged(
                    p.isBatchUploadEnabled(),
                    p.getBatchRecordCount(), p.isBatchTriggerByCountEnabled(),
                    p.getBatchTimeout(), p.isBatchTriggerByTimeoutEnabled(),
                    p.getBatchMaxSizeKb(), p.isBatchTriggerByMaxSizeEnabled(),
                    p.getCompressionLevel()
                )
                dialog.dismiss()
            }
            .show()
    }

    private fun formatStats(stats: com.example.hoarder.ui.dialogs.server.UploadStats): String {
        return if (stats.actualNetworkBytes > 0) {
            "${ByteFormatter.format(stats.payloadBytes)} / ${ByteFormatter.format(stats.actualNetworkBytes)}"
        } else {
            ByteFormatter.format(stats.payloadBytes)
        }
    }

    fun showDetailedLogDialog(logType: String) {
        val logViewer = LogViewer(a, logRepository)
        logViewer.showLogDialog(logType)
    }
}