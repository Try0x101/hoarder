package com.example.hoarder.transport.queue

import android.os.Handler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UploadScheduler(
    private val handler: Handler,
    private val task: () -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isActive = AtomicBoolean(false)

    private val runnable = object : Runnable {
        override fun run() {
            if (!isActive.get()) return

            executor.submit {
                try {
                    task()
                } catch (e: Exception) {
                    // Catch exceptions to prevent silent failures of the scheduler
                } finally {
                    if (isActive.get()) {
                        handler.postDelayed(this, 1000L)
                    }
                }
            }
        }
    }

    fun start() {
        if (isActive.compareAndSet(false, true)) {
            handler.removeCallbacks(runnable)
            handler.post(runnable)
        }
    }

    fun stop() {
        if (isActive.compareAndSet(true, false)) {
            handler.removeCallbacks(runnable)
        }
    }

    fun cleanup() {
        stop()
        executor.shutdownNow()
    }

    fun submitOneTimeTask(oneTimeTask: () -> Unit) {
        if (isActive.get()) {
            executor.submit {
                try {
                    oneTimeTask()
                } catch (e: Exception) {
                    // Catch exceptions from one-time tasks
                }
            }
        }
    }
}