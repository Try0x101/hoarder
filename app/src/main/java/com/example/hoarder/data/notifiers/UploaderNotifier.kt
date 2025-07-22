package com.example.hoarder.data.notifiers

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.ui.service.ServiceCommander

class UploaderNotifier(private val ctx: Context) {

    private var lastStatusTime = 0L
    private var lastStatusMessage = ""
    private val STATUS_UPDATE_DEBOUNCE_MS = 2000L

    fun notifyStatusDebounced(s: String, m: String, ub: Long, anb: Long, bufferSize: Long, bulkInProgress: Boolean) {
        val currentTime = System.currentTimeMillis()
        if ("$s|$m" != lastStatusMessage || (currentTime - lastStatusTime) > STATUS_UPDATE_DEBOUNCE_MS) {
            lastStatusTime = currentTime
            lastStatusMessage = "$s|$m"
            notifyStatus(s, m, ub, anb, bufferSize, bulkInProgress)
        }
    }

    fun notifyStatus(s: String, m: String, ub: Long, anb: Long, bufferSize: Long, bulkInProgress: Boolean) {
        val intent = Intent(ServiceCommander.ACTION_UPLOAD_STATUS).apply {
            putExtra("status", s)
            putExtra("message", m)
            putExtra("totalUploadedBytes", ub)
            putExtra("totalActualNetworkBytes", anb)
            putExtra("bufferedDataSize", bufferSize)
            putExtra("bulkInProgress", bulkInProgress)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }
}