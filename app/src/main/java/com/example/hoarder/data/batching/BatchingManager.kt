package com.example.hoarder.data.batching

import com.example.hoarder.data.storage.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BatchingManager(
    private val appPrefs: Prefs,
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
    private var timeoutJob: Job? = null

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
            timeoutJob = coroutineScope.launch {
                delay(timeoutMillis)
                if (isEnabled && isTriggerByTimeoutEnabled) {
                    forceSendBuffer()
                }
            }
        }
    }

    fun onForceSendBuffer() {
        if (isTimeoutScheduled.getAndSet(false)) {
            timeoutJob?.cancel()
            timeoutJob = null
        }
    }
}