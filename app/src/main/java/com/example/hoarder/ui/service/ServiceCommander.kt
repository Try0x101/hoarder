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

    fun startCollection() {
        lbm.sendBroadcast(Intent("com.example.hoarder.START_COLLECTION"))
    }

    fun stopCollection() {
        lbm.sendBroadcast(Intent("com.example.hoarder.STOP_COLLECTION"))
    }

    fun startUpload(serverAddress: String, currentData: String?) {
        if (NetUtils.isValidIpPort(serverAddress)) {
            lbm.sendBroadcast(Intent("com.example.hoarder.START_UPLOAD").putExtra("ipPort", serverAddress))

            if (currentData != null && currentData.isNotBlank()) {
                h.postDelayed({
                    forceUpload(currentData)
                }, 500)
            }
        }
    }

    private fun forceUpload(data: String) {
        lbm.sendBroadcast(Intent("com.example.hoarder.FORCE_UPLOAD").putExtra("forcedData", data))
    }

    fun stopUpload() {
        lbm.sendBroadcast(Intent("com.example.hoarder.STOP_UPLOAD"))
    }

    fun sendBuffer() {
        lbm.sendBroadcast(Intent("com.example.hoarder.SEND_BUFFER"))
    }

    fun notifyPowerModeChanged(newMode: Int) {
        val intent = Intent("com.example.hoarder.POWER_MODE_CHANGED").apply {
            putExtra("newMode", newMode)
        }
        lbm.sendBroadcast(intent)
    }

    fun notifyBatchingSettingsChanged(
        enabled: Boolean, recordCount: Int, byCount: Boolean,
        timeout: Int, byTimeout: Boolean, maxSize: Int, byMaxSize: Boolean,
        compLevel: Int
    ) {
        val intent = Intent("com.example.hoarder.BATCHING_SETTINGS_CHANGED").apply {
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