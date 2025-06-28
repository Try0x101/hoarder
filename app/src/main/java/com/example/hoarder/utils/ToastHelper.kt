// Create this file: app/src/main/java/com/example/hoarder/utils/ToastHelper.kt
package com.example.hoarder.utils

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

object ToastHelper {
    private val lastToastTimes = ConcurrentHashMap<String, Long>()
    private val minimumInterval = 5000L // 5 seconds between identical toasts

    fun showToast(context: Context, message: String, length: Int = Toast.LENGTH_SHORT) {
        if (context !is Activity || !isActivityActive(context)) {
            return
        }

        val key = message.hashCode().toString()
        val now = System.currentTimeMillis()
        val lastTime = lastToastTimes.getOrDefault(key, 0L)

        if (now - lastTime > minimumInterval) {
            lastToastTimes[key] = now
            Handler(context.mainLooper).post {
                Toast.makeText(context, message, length).show()
            }
        }
    }

    private fun isActivityActive(activity: Activity): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    fun clearHistory() {
        lastToastTimes.clear()
    }
}