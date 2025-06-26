package com.example.hoarder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotifUtils {
    fun createSilentChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ec = nm.getNotificationChannel("HoarderServiceChannel")

            val ch = ec ?: NotificationChannel(
                "HoarderServiceChannel",
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