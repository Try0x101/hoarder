package com.example.hoarder.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ServiceUtils {
    fun scheduleRestart(context: Context) {
        val i = Intent(context, BackgroundService::class.java)
        val pi = PendingIntent.getService(context, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000, pi
        )
    }

    fun restartService(context: Context) {
        val i = Intent(context, BackgroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i)
            else
                context.startService(i)
        } catch (e: Exception) {
            scheduleRestart(context)
        }
    }
}