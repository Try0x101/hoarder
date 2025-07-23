package com.example.hoarder.data.notifiers

import android.content.Context
import com.example.hoarder.ui.state.ServiceStateRepository
import com.example.hoarder.ui.state.UploadState

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
        val newState = UploadState(
            status = s,
            message = m,
            totalUploadedBytes = ub,
            totalActualNetworkBytes = anb,
            bufferedDataSize = bufferSize,
            isBulkInProgress = bulkInProgress
        )
        ServiceStateRepository.updateUploadState(newState)
    }
}