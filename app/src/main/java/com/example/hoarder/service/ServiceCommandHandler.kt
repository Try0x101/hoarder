package com.example.hoarder.service

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.DataUploader
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import com.example.hoarder.sensors.DataCollector
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

class ServiceCommandHandler(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val dataCollector: DataCollector,
    private val dataUploader: DataUploader,
    private val powerManager: PowerManager,
    private val collectionActive: AtomicBoolean,
    private val uploadActive: AtomicBoolean,
    private val updateAppPreferences: (String, Any) -> Unit,
    private val broadcastStateUpdate: () -> Unit
) {
    fun handle(intent: Intent?) {
        when (intent?.action) {
            "com.example.hoarder.START_COLLECTION" -> handleStartCollection()
            "com.example.hoarder.STOP_COLLECTION" -> handleStopCollection()
            "com.example.hoarder.START_UPLOAD" -> handleStartUpload(intent)
            "com.example.hoarder.STOP_UPLOAD" -> handleStopUpload()
            "com.example.hoarder.FORCE_UPLOAD" -> handleForceUpload(intent)
            "com.example.hoarder.SEND_BUFFER" -> handleSendBuffer()
            "com.example.hoarder.GET_STATE" -> broadcastStateUpdate()
            "com.example.hoarder.POWER_MODE_CHANGED" -> handlePowerModeChange(intent)
            "com.example.hoarder.MOTION_STATE_CHANGED" -> handleMotionStateChange(intent)
            "com.example.hoarder.BATCHING_SETTINGS_CHANGED" -> handleBatchingSettingsChange(intent)
        }
    }

    private fun handleStartCollection() {
        if (collectionActive.compareAndSet(false, true)) {
            dataCollector.start()
            updateAppPreferences("dataCollectionToggleState", true)
            broadcastStateUpdate()
        }
    }

    private fun handleStopCollection() {
        if (collectionActive.compareAndSet(true, false)) {
            dataCollector.stop()
            updateAppPreferences("dataCollectionToggleState", false)
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent("com.example.hoarder.DATA_UPDATE").putExtra("jsonString", ""))
            broadcastStateUpdate()
        }
    }

    private fun handleStartUpload(intent: Intent) {
        val ip = intent.getStringExtra("ipPort")?.split(":")
        if (ip != null && ip.size == 2 && ip[0].isNotBlank() && ip[1].toIntOrNull() != null
            && ip[1].toInt() in 1..65535
        ) {
            if (uploadActive.compareAndSet(false, true)) {
                dataUploader.resetCounter()
                dataUploader.setServer(ip[0], ip[1].toInt())
                dataUploader.start()
                updateAppPreferences("dataUploadToggleState", true)
                updateAppPreferences("serverIpPortAddress", "${ip[0]}:${ip[1]}")
                broadcastStateUpdate()
            }
        } else {
            uploadActive.set(false)
            dataUploader.notifyStatus("Error", "Invalid Server IP:Port for starting upload.", 0L, 0L, 0L)
            updateAppPreferences("dataUploadToggleState", false)
            broadcastStateUpdate()
        }
    }

    private fun handleStopUpload() {
        if (uploadActive.compareAndSet(true, false)) {
            dataUploader.stop()
            dataUploader.resetCounter()
            updateAppPreferences("dataUploadToggleState", false)
            broadcastStateUpdate()
        }
    }

    private fun handleForceUpload(intent: Intent) {
        if (uploadActive.get()) {
            val forcedData = intent.getStringExtra("forcedData")
            if (forcedData != null && forcedData.isNotBlank()) {
                dataUploader.queueData(forcedData)
                dataUploader.forceSendBuffer()
            }
        }
    }

    private fun handleSendBuffer() {
        if (uploadActive.get()) {
            dataUploader.forceSendBuffer()
        }
    }

    private fun handlePowerModeChange(intent: Intent) {
        val newMode = intent.getIntExtra("newMode", Prefs.POWER_MODE_CONTINUOUS)
        powerManager.updateMode(newMode)
    }

    private fun handleMotionStateChange(intent: Intent) {
        val isMoving = intent.getBooleanExtra("isMoving", true)
        powerManager.onMotionStateChanged(isMoving)
    }

    private fun handleBatchingSettingsChange(intent: Intent) {
        val enabled = intent.getBooleanExtra("enabled", false)
        val recordCount = intent.getIntExtra("recordCount", 20)
        val byCount = intent.getBooleanExtra("byCount", false)
        val timeout = intent.getIntExtra("timeout", 60)
        val byTimeout = intent.getBooleanExtra("byTimeout", false)
        val maxSize = intent.getIntExtra("maxSize", 5)
        val byMaxSize = intent.getBooleanExtra("byMaxSize", true)
        val compLevel = intent.getIntExtra("compLevel", 6)

        dataUploader.updateBatchSettings(enabled, recordCount, byCount, timeout, byTimeout, maxSize, byMaxSize, compLevel)

        updateAppPreferences("batchUploadEnabled", enabled)
        updateAppPreferences("batchRecordCount", recordCount)
        updateAppPreferences("batchTriggerByCountEnabled", byCount)
        updateAppPreferences("batchTimeoutSec", timeout)
        updateAppPreferences("batchTriggerByTimeoutEnabled", byTimeout)
        updateAppPreferences("batchMaxSizeKb", maxSize)
        updateAppPreferences("batchTriggerByMaxSizeEnabled", byMaxSize)
        updateAppPreferences("compressionLevel", compLevel)
        if (!enabled && uploadActive.get()) {
            dataUploader.forceSendBuffer()
        }
    }
}