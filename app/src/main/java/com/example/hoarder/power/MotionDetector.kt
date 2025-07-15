package com.example.hoarder.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class MotionDetector(
    context: Context,
    private val onMotionStateChanged: (Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastUpdate: Long = 0
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastZ: Float = 0f
    private var isMoving = false
    private var motionConfidence = 0
    private var stationaryStartTime: Long = 0
    private var motionDetectionActive = true

    companion object {
        private const val SHAKE_THRESHOLD = 800
        private const val MOTION_CONFIDENCE_THRESHOLD = 3
        private const val STATIONARY_DISABLE_DELAY = 300000L
    }

    fun start() {
        if (motionDetectionActive) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER && motionDetectionActive) {
            val curTime = System.currentTimeMillis()
            if ((curTime - lastUpdate) > 200) {
                val diffTime = (curTime - lastUpdate)
                lastUpdate = curTime

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val speed = Math.abs(x + y + z - lastX - lastY - lastZ) / diffTime * 10000

                if (speed > SHAKE_THRESHOLD) {
                    motionConfidence++
                    if (motionConfidence >= MOTION_CONFIDENCE_THRESHOLD && !isMoving) {
                        isMoving = true
                        motionDetectionActive = true
                        onMotionStateChanged(true)
                    }
                } else {
                    if (motionConfidence > 0) motionConfidence--

                    if (isMoving && motionConfidence == 0) {
                        isMoving = false
                        stationaryStartTime = curTime
                        onMotionStateChanged(false)
                    }

                    if (!isMoving && (curTime - stationaryStartTime) > STATIONARY_DISABLE_DELAY) {
                        motionDetectionActive = false
                        stop()
                        scheduleReactivation()
                    }
                }
                lastX = x
                lastY = y
                lastZ = z
            }
        }
    }

    private fun scheduleReactivation() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            motionDetectionActive = true
            start()
        }, 60000L)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}