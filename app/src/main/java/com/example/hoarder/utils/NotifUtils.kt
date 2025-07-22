package com.example.hoarder.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.hoarder.R
import com.example.hoarder.ui.MainActivity

object NotifUtils {
    const val SERVICE_CHANNEL_ID = "HoarderServiceChannel"

    fun createSilentChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "Hoarder Service Channel",
                    NotificationManager.IMPORTANCE_MIN
                )
                ch.apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                    setSound(null, null)
                    description = "Silent background operation"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun createServiceNotification(ctx: Context): Notification {
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, SERVICE_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText("Running in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}