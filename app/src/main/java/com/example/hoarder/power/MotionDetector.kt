package com.example.hoarder.power

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

class MotionDetector(context: Context) {

    private val activityRecognitionClient = ActivityRecognition.getClient(context)

    private val transitions = mutableListOf<ActivityTransition>().apply {
        add(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        add(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
    }

    private val request = ActivityTransitionRequest(transitions)

    @SuppressLint("MissingPermission")
    fun start(pendingIntent: PendingIntent) {
        activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
    }

    fun stop(pendingIntent: PendingIntent) {
        activityRecognitionClient.removeActivityTransitionUpdates(pendingIntent)
    }
}