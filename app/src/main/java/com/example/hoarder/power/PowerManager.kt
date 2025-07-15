package com.example.hoarder.power

import android.content.Context
import com.example.hoarder.data.storage.app.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PowerState(
    val mode: Int = Prefs.POWER_MODE_CONTINUOUS,
    val isMoving: Boolean = true
)

class PowerManager(
    context: Context,
    private val prefs: Prefs
) {
    private val motionDetector = MotionDetector(context, ::onMotionStateChanged)

    private val _powerState = MutableStateFlow(PowerState(mode = prefs.getPowerMode()))
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()

    fun start() {
        if (powerState.value.mode == Prefs.POWER_MODE_OPTIMIZED) {
            motionDetector.start()
        }
    }

    fun stop() {
        motionDetector.stop()
    }

    fun updateMode(newMode: Int) {
        prefs.setPowerMode(newMode)
        _powerState.update { it.copy(mode = newMode) }
        if (newMode == Prefs.POWER_MODE_OPTIMIZED) {
            motionDetector.start()
        } else {
            motionDetector.stop()
        }
    }

    private fun onMotionStateChanged(isNowMoving: Boolean) {
        _powerState.update { it.copy(isMoving = isNowMoving) }
    }
}