package com.example.hoarder.sensors.agents

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager as AndroidSensorManager

class BarometerAgent(private val androidSensorManager: AndroidSensorManager) : SensorEventListener {
    var barometricValue: Float? = null
    var lastBarometerAltitude: Double = Double.NaN

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            barometricValue = event.values[0]
            lastBarometerAltitude = AndroidSensorManager.getAltitude(
                AndroidSensorManager.PRESSURE_STANDARD_ATMOSPHERE, barometricValue!!
            ).toDouble()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun reset() {
        barometricValue = null
        lastBarometerAltitude = Double.NaN
    }
}