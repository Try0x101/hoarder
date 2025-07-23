package com.example.hoarder.ui.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.transport.network.NetUtils

class ServiceCommander(private val context: Context) {

    private val lbm = LocalBroadcastManager.getInstance(context)
    private val h = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_START_COLLECTION = "com.example.hoarder.START_COLLECTION"
        const val ACTION_STOP_COLLECTION = "com.example.hoarder.STOP_COLLECTION"
        const val ACTION_START_UPLOAD = "com.example.hoarder.START_UPLOAD"
        const val ACTION_STOP_UPLOAD = "com.example.hoarder.STOP_UPLOAD"
        const val ACTION_FORCE_UPLOAD = "com.example.hoarder.FORCE_UPLOAD"
        const val ACTION_SEND_BUFFER = "com.example.hoarder.SEND_BUFFER"
        const val ACTION_GET_STATE = "com.example.hoarder.GET_STATE"
        const val ACTION_POWER_MODE_CHANGED = "com.example.hoarder.POWER_MODE_CHANGED"
        const val ACTION_BATCHING_SETTINGS_CHANGED = "com.example.hoarder.BATCHING_SETTINGS_CHANGED"
        const val ACTION_MOTION_STATE_CHANGED = "com.example.hoarder.MOTION_STATE_CHANGED"
        const val ACTION_PERMISSIONS_REQUIRED = "com.example.hoarder.PERMISSIONS_REQUIRED"
        const val ACTION_DATA_UPDATE = "com.example.hoarder.DATA_UPDATE"
        const val ACTION_UPLOAD_STATUS = "com.example.hoarder.UPLOAD_STATUS"
        const val ACTION_SERVICE_STATE_UPDATE = "com.example.hoarder.SERVICE_STATE_UPDATE"
    }

    fun startCollection() {
        lbm.sendBroadcast(Intent(ACTION_START_COLLECTION))
    }

    fun stopCollection() {
        lbm.sendBroadcast(Intent(ACTION_STOP_COLLECTION))
    }

    fun startUpload(serverAddress: String, currentData: String?) {
        if (NetUtils.isValidServerAddress(serverAddress)) {
            lbm.sendBroadcast(Intent(ACTION_START_UPLOAD).putExtra("address", serverAddress))

            if (currentData != null && currentData.isNotBlank()) {
                h.postDelayed({
                    forceUpload(currentData)
                }, 500)
            }
        }
    }

    private fun forceUpload(data: String) {
        lbm.sendBroadcast(Intent(ACTION_FORCE_UPLOAD).putExtra("forcedData", data))
    }

    fun stopUpload() {
        lbm.sendBroadcast(Intent(ACTION_STOP_UPLOAD))
    }

    fun sendBuffer() {
        lbm.sendBroadcast(Intent(ACTION_SEND_BUFFER))
    }

    fun notifyPowerModeChanged(newMode: Int) {
        val intent = Intent(ACTION_POWER_MODE_CHANGED).apply {
            putExtra("newMode", newMode)
        }
        lbm.sendBroadcast(intent)
    }

    fun notifyBatchingSettingsChanged(
        enabled: Boolean, recordCount: Int, byCount: Boolean,
        timeout: Int, byTimeout: Boolean, maxSize: Int, byMaxSize: Boolean,
        compLevel: Int
    ) {
        val intent = Intent(ACTION_BATCHING_SETTINGS_CHANGED).apply {
            putExtra("enabled", enabled)
            putExtra("recordCount", recordCount)
            putExtra("byCount", byCount)
            putExtra("timeout", timeout)
            putExtra("byTimeout", byTimeout)
            putExtra("maxSize", maxSize)
            putExtra("byMaxSize", byMaxSize)
            putExtra("compLevel", compLevel)
        }
        lbm.sendBroadcast(intent)
    }
}