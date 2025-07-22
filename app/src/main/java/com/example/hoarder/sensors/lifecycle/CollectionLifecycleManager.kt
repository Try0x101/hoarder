package com.example.hoarder.sensors.lifecycle

import com.example.hoarder.power.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CollectionLifecycleManager(
    private val powerManager: PowerManager,
    private val coroutineScope: CoroutineScope,
    private val collectionTask: suspend (isMoving: Boolean) -> Unit
) {
    private val collectionActive = AtomicBoolean(false)
    private var collectionJob: Job? = null

    fun start() {
        if (collectionActive.compareAndSet(false, true)) {
            observePowerStateChanges()
            val state = powerManager.powerState.value
            startCollectionLoop(powerManager.getCollectionInterval(), state.isMoving)
        }
    }

    fun stop() {
        if (collectionActive.compareAndSet(true, false)) {
            collectionJob?.cancel()
        }
    }

    private fun observePowerStateChanges() {
        coroutineScope.launch {
            powerManager.powerState.collect { state ->
                if (collectionActive.get()) {
                    collectionJob?.cancel()
                    startCollectionLoop(powerManager.getCollectionInterval(), state.isMoving)
                }
            }
        }
    }

    private fun startCollectionLoop(interval: Long, isMoving: Boolean) {
        collectionJob = coroutineScope.launch {
            while (collectionActive.get()) {
                collectionTask(isMoving)
                delay(interval)
            }
        }
    }

    fun isActive() = collectionActive.get()
}