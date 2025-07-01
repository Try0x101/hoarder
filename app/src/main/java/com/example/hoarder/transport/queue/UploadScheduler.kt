package com.example.hoarder.transport.queue

import android.os.Handler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class UploadScheduler(
    private val handler: Handler,
    private val task: () -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentUploadTask: Future<*>? = null
    private val isActive = AtomicBoolean(false)

    private val runnable = object : Runnable {
        override fun run() {
            if (isActive.get()) {
                currentUploadTask?.cancel(false)
                currentUploadTask = executor.submit(task)
                if (isActive.get()) {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    fun start() {
        if (isActive.compareAndSet(false, true)) {
            handler.post(runnable)
        }
    }

    fun stop() {
        if (isActive.compareAndSet(true, false)) {
            handler.removeCallbacks(runnable)
            currentUploadTask?.cancel(true)
        }
    }

    fun cleanup() {
        stop()
        executor.shutdown()
    }
}