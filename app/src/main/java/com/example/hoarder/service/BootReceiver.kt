package com.example.hoarder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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