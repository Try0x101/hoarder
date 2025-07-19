package com.example.hoarder.transport.queue

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

class UploadScheduler(
    private val context: Context,
    private val handler: Handler,
    private val task: () -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isActive = AtomicBoolean(false)
    private val failureCount = AtomicInteger(0)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val ACTION_UPLOAD_ALARM = "com.example.hoarder.UPLOAD_ALARM"
        private const val BASE_DELAY_MS = 300000L
        private const val MAX_DELAY_MS = 900000L
        private const val MAX_FAILURES = 5
    }

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPLOAD_ALARM && isActive.get()) {
                executor.submit {
                    try {
                        task()
                        failureCount.set(0)
                        scheduleNext(BASE_DELAY_MS)
                    } catch (e: Exception) {
                        handleFailure()
                    }
                }
            }
        }
    }

    private val alarmIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_UPLOAD_ALARM)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private val runnable = object : Runnable {
        override fun run() {
            if (!isActive.get()) return

            executor.submit {
                try {
                    task()
                    failureCount.set(0)
                } catch (e: Exception) {
                    handleFailure()
                } finally {
                    if (isActive.get()) {
                        handler.postDelayed(this, BASE_DELAY_MS)
                    }
                }
            }
        }
    }

    fun start() {
        if (isActive.compareAndSet(false, true)) {
            failureCount.set(0)
            try {
                context.registerReceiver(alarmReceiver, IntentFilter(ACTION_UPLOAD_ALARM))
            } catch (e: Exception) {
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                scheduleNext(BASE_DELAY_MS)
            } else {
                handler.removeCallbacks(runnable)
                handler.post(runnable)
            }
        }
    }

    fun stop() {
        if (isActive.compareAndSet(true, false)) {
            handler.removeCallbacks(runnable)
            try {
                alarmManager.cancel(alarmIntent)
            } catch (e: Exception) {
            }
            try {
                context.unregisterReceiver(alarmReceiver)
            } catch (e: Exception) {
            }
        }
    }

    fun cleanup() {
        stop()
        executor.shutdownNow()
    }

    private fun handleFailure() {
        val currentFailures = failureCount.incrementAndGet()
        if (currentFailures >= MAX_FAILURES) {
            failureCount.set(MAX_FAILURES - 1)
        }

        val delay = calculateBackoffDelay(currentFailures)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isActive.get()) {
            scheduleNext(delay)
        } else if (isActive.get()) {
            handler.postDelayed(runnable, delay)
        }
    }

    private fun calculateBackoffDelay(failures: Int): Long {
        val exponentialDelay = BASE_DELAY_MS * 2.0.pow(failures.toDouble()).toLong()
        return min(exponentialDelay, MAX_DELAY_MS)
    }

    private fun scheduleNext(delayMs: Long) {
        if (!isActive.get()) return

        try {
            val triggerTime = System.currentTimeMillis() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    alarmIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    alarmIntent
                )
            }
        } catch (e: Exception) {
            handler.postDelayed(runnable, delayMs)
        }
    }

    fun submitOneTimeTask(oneTimeTask: () -> Unit) {
        if (isActive.get()) {
            executor.submit {
                try {
                    oneTimeTask()
                } catch (e: Exception) {
                }
            }
        }
    }
}