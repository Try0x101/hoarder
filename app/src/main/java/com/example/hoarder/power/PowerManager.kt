package com.example.hoarder.power

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.hoarder.data.storage.app.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PowerState(
    val mode: Int = Prefs.POWER_MODE_CONTINUOUS,
    val isMoving: Boolean = true,
    val stationaryDuration: Long = 0L,
    val movementMomentum: Int = 0
)

class PowerManager(
    private val context: Context,
    private val prefs: Prefs
) {
    private val motionDetector = MotionDetector(context)
    private var lastMotionChangeTime = System.currentTimeMillis()
    private var movementHistory = mutableListOf<Boolean>()

    private val _powerState = MutableStateFlow(PowerState(mode = prefs.getPowerMode()))
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()

    companion object {
        const val ACTION_ACTIVITY_TRANSITION = "com.example.hoarder.ACTIVITY_TRANSITION"
    }

    private val transitionPendingIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_ACTIVITY_TRANSITION)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun start() {
        if (powerState.value.mode == Prefs.POWER_MODE_OPTIMIZED) {
            motionDetector.start(transitionPendingIntent)
        }
    }

    fun stop() {
        if (powerState.value.mode == Prefs.POWER_MODE_OPTIMIZED) {
            try {
                motionDetector.stop(transitionPendingIntent)
            } catch (e: Exception) {
                // Ignore errors on stop
            }
        }
    }

    fun updateMode(newMode: Int) {
        prefs.setPowerMode(newMode)
        _powerState.update { it.copy(mode = newMode) }
        if (newMode == Prefs.POWER_MODE_OPTIMIZED) {
            start()
        } else {
            stop()
        }
    }

    fun getCollectionInterval(): Long {
        val state = powerState.value
        return when (state.mode) {
            Prefs.POWER_MODE_OPTIMIZED -> {
                if (state.isMoving) {
                    when (state.movementMomentum) {
                        in 0..2 -> 5000L
                        in 3..5 -> 10000L
                        else -> 30000L
                    }
                } else {
                    when (state.stationaryDuration) {
                        in 0..120000L -> 60000L
                        in 120001..600000L -> 120000L
                        else -> 300000L
                    }
                }
            }
            Prefs.POWER_MODE_PASSIVE -> 300000L
            else -> 1000L
        }
    }

    fun onMotionStateChanged(isNowMoving: Boolean) {
        if (powerState.value.isMoving == isNowMoving) return

        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastMotionChangeTime

        updateMovementHistory(isNowMoving)

        val stationaryDuration = if (!isNowMoving) timeSinceLastChange else 0L
        val momentum = calculateMovementMomentum()

        _powerState.update {
            it.copy(
                isMoving = isNowMoving,
                stationaryDuration = stationaryDuration,
                movementMomentum = momentum
            )
        }

        lastMotionChangeTime = currentTime
    }

    private fun updateMovementHistory(isMoving: Boolean) {
        movementHistory.add(isMoving)
        if (movementHistory.size > 10) {
            movementHistory.removeAt(0)
        }
    }

    private fun calculateMovementMomentum(): Int {
        if (movementHistory.size < 3) return 0
        val recentMovement = movementHistory.takeLast(5)
        return recentMovement.count { it }
    }
}