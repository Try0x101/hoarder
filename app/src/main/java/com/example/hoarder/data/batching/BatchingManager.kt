package com.example.hoarder.data.batching

import android.os.Handler
import com.example.hoarder.data.storage.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class BatchingManager(
    private val appPrefs: Prefs,
    private val handler: Handler,
    private val coroutineScope: CoroutineScope,
    private val forceSendBuffer: () -> Unit
) {
    private var isEnabled = true
    private var recordCountThreshold = 20
    private var isTriggerByCountEnabled = true
    private var timeoutMillis = 60000L
    private var isTriggerByTimeoutEnabled = true
    private var maxSizeKiloBytes = 100
    private var isTriggerByMaxSizeEnabled = true
    private val isTimeoutScheduled = AtomicBoolean(false)

    private val timeoutRunnable = Runnable { coroutineScope.launch { if (isEnabled && isTriggerByTimeoutEnabled) forceSendBuffer() } }

    init {
        updateConfiguration()
    }

    fun updateConfiguration() {
        onForceSendBuffer()
        isEnabled = appPrefs.isBatchUploadEnabled()
        recordCountThreshold = appPrefs.getBatchRecordCount()
        isTriggerByCountEnabled = appPrefs.isBatchTriggerByCountEnabled()
        timeoutMillis = appPrefs.getBatchTimeout() * 1000L
        isTriggerByTimeoutEnabled = appPrefs.isBatchTriggerByTimeoutEnabled()
        maxSizeKiloBytes = appPrefs.getBatchMaxSizeKb()
        isTriggerByMaxSizeEnabled = appPrefs.isBatchTriggerByMaxSizeEnabled()
    }

    fun isEnabled(): Boolean = isEnabled

    fun checkTriggers(bufferedCount: Int, currentBufferedSizeKb: Long) {
        if (!isEnabled || bufferedCount == 0) {
            onForceSendBuffer()
            return
        }

        val countTrigger = isTriggerByCountEnabled && bufferedCount >= recordCountThreshold
        val sizeTrigger = isTriggerByMaxSizeEnabled && currentBufferedSizeKb >= maxSizeKiloBytes

        if (countTrigger || sizeTrigger) {
            forceSendBuffer()
            return
        }

        if (isTriggerByTimeoutEnabled && isTimeoutScheduled.compareAndSet(false, true)) {
            coroutineScope.launch {
                withContext(Dispatchers.Main) {
                    handler.postDelayed(timeoutRunnable, timeoutMillis)
                }
            }
        }
    }

    fun onForceSendBuffer() {
        if (isTimeoutScheduled.getAndSet(false)) {
            handler.removeCallbacks(timeoutRunnable)
        }
    }
}