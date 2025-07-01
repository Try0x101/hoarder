package com.example.hoarder.service

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.DataUploader
import com.example.hoarder.data.processing.DeltaManager
import com.example.hoarder.sensors.DataCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ServiceCommandHandler(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val dataCollector: DataCollector,
    private val dataUploader: DataUploader,
    private val deltaManager: DeltaManager,
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
            "com.example.hoarder.GET_DB_STATS" -> handleGetDbStats()
            "com.example.hoarder.CLEANUP_OLD_RECORDS" -> handleCleanupOldRecords()
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
            dataUploader.notifyStatus("Error", "Invalid Server IP:Port for starting upload.", 0L, 0L)
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
                dataUploader.queueForcedData(forcedData)
            }
        }
    }

    private fun handleSendBuffer() {
        if (uploadActive.get()) {
            dataUploader.forceSendBuffer()
        }
    }

    private fun handleGetDbStats() {
        serviceScope.launch {
            try {
                val pendingCount = deltaManager.getPendingRecordsCount()
                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent("com.example.hoarder.DB_STATS_UPDATE").apply {
                        putExtra("pendingRecords", pendingCount)
                    })
            } catch (e: Exception) {
                // Error getting stats
            }
        }
    }

    private fun handleCleanupOldRecords() {
        serviceScope.launch {
            try {
                deltaManager.cleanupOldRecords(7)
            } catch (e: Exception) {
                // Error during cleanup
            }
        }
    }
}