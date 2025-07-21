package com.example.hoarder.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.hoarder.ui.MainActivity
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.example.hoarder.ACTIVITY_TRANSITION" && context != null) {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)
                result?.transitionEvents?.forEach { event ->
                    val isMoving = event.activityType != DetectedActivity.STILL
                    val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                        action = "com.example.hoarder.MOTION_STATE_CHANGED"
                        putExtra("isMoving", isMoving)
                    }
                    context.startService(serviceIntent)
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context != null) {
            val chanId = "hoarder_persist"
            val notifManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    chanId,
                    "Hoarder Persistent",
                    NotificationManager.IMPORTANCE_HIGH
                )
                channel.setShowBadge(false)
                notifManager.createNotificationChannel(channel)
            }
            val i = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pi = android.app.PendingIntent.getActivity(
                context,
                0,
                i,
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, chanId)
                .setContentTitle("Hoarder activation required!")
                .setContentText("Tap to unlock full functionality after reboot.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(false)
                .build()
            notifManager.notify(99999, notif)
        }
    }
}