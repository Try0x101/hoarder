package com.example.hoarder.service

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.DataUploader
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import com.example.hoarder.sensors.DataCollector
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.ui.service.ServiceCommander
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
            ServiceCommander.ACTION_START_COLLECTION -> handleStartCollection()
            ServiceCommander.ACTION_STOP_COLLECTION -> handleStopCollection()
            ServiceCommander.ACTION_START_UPLOAD -> handleStartUpload(intent)
            ServiceCommander.ACTION_STOP_UPLOAD -> handleStopUpload()
            ServiceCommander.ACTION_FORCE_UPLOAD -> handleForceUpload(intent)
            ServiceCommander.ACTION_SEND_BUFFER -> handleSendBuffer()
            ServiceCommander.ACTION_GET_STATE -> broadcastStateUpdate()
            ServiceCommander.ACTION_POWER_MODE_CHANGED -> handlePowerModeChange(intent)
            ServiceCommander.ACTION_MOTION_STATE_CHANGED -> handleMotionStateChange(intent)
            ServiceCommander.ACTION_BATCHING_SETTINGS_CHANGED -> handleBatchingSettingsChange(intent)
        }
    }

    private fun handleStartCollection() {
        if (collectionActive.compareAndSet(false, true)) {
            dataCollector.start()
            updateAppPreferences(Prefs.KEY_DATA_COLLECTION_ENABLED, true)
            broadcastStateUpdate()
        }
    }

    private fun handleStopCollection() {
        if (collectionActive.compareAndSet(true, false)) {
            dataCollector.stop()
            updateAppPreferences(Prefs.KEY_DATA_COLLECTION_ENABLED, false)
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent(ServiceCommander.ACTION_DATA_UPDATE).putExtra("jsonString", ""))
            broadcastStateUpdate()
        }
    }

    private fun handleStartUpload(intent: Intent) {
        val address = intent.getStringExtra("address")
        if (address != null && NetUtils.isValidServerAddress(address)) {
            if (uploadActive.compareAndSet(false, true)) {
                dataUploader.resetCounter()
                dataUploader.setServer(address)
                dataUploader.start()
                updateAppPreferences(Prefs.KEY_DATA_UPLOAD_ENABLED, true)
                updateAppPreferences(Prefs.KEY_SERVER_ADDRESS, address)
                broadcastStateUpdate()
            }
        } else {
            uploadActive.set(false)
            dataUploader.postStatusNotification("Error", "Invalid Server Address for starting upload.")
            updateAppPreferences(Prefs.KEY_DATA_UPLOAD_ENABLED, false)
            broadcastStateUpdate()
        }
    }

    private fun handleStopUpload() {
        if (uploadActive.compareAndSet(true, false)) {
            dataUploader.stop()
            dataUploader.resetCounter()
            updateAppPreferences(Prefs.KEY_DATA_UPLOAD_ENABLED, false)
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

        updateAppPreferences(Prefs.KEY_BATCH_UPLOAD_ENABLED, enabled)
        updateAppPreferences(Prefs.KEY_BATCH_RECORD_COUNT, recordCount)
        updateAppPreferences(Prefs.KEY_BATCH_TRIGGER_BY_COUNT_ENABLED, byCount)
        updateAppPreferences(Prefs.KEY_BATCH_TIMEOUT_SEC, timeout)
        updateAppPreferences(Prefs.KEY_BATCH_TRIGGER_BY_TIMEOUT_ENABLED, byTimeout)
        updateAppPreferences(Prefs.KEY_BATCH_MAX_SIZE_KB, maxSize)
        updateAppPreferences(Prefs.KEY_BATCH_TRIGGER_BY_MAX_SIZE_ENABLED, byMaxSize)
        updateAppPreferences(Prefs.KEY_COMPRESSION_LEVEL, compLevel)
        if (!enabled && uploadActive.get()) {
            dataUploader.forceSendBuffer()
        }
    }
}