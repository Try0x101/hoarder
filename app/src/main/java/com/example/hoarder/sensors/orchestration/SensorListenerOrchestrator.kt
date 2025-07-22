package com.example.hoarder.sensors.orchestration

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.hoarder.sensors.agents.BarometerAgent
import android.hardware.SensorManager as AndroidSensorManager

class SensorListenerOrchestrator(
    private val ctx: Context,
    private val locationManager: LocationManager,
    private val androidSensorManager: AndroidSensorManager,
    private val barometerAgent: BarometerAgent,
    private val locationListener: LocationListener
) {
    private var requestInterval: Long = 1000L
    private var requestDistance: Float = 0f

    fun registerListeners() {
        registerGpsListener()
        registerPressureSensor()
    }

    fun unregisterListeners() {
        unregisterGpsListener()
        unregisterPressureSensor()
    }

    fun registerGpsListener() {
        try {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, requestInterval, requestDistance, locationListener)
            }
        } catch (_: Exception) {}
    }

    fun unregisterGpsListener() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }

    fun registerPassiveListener() {
        try {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, locationListener)
        } catch (_: SecurityException) {}
    }

    private fun registerPressureSensor() {
        val pressureSensor = androidSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        pressureSensor?.let {
            androidSensorManager.registerListener(barometerAgent, it, AndroidSensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterPressureSensor() {
        try {
            androidSensorManager.unregisterListener(barometerAgent)
        } catch (_: Exception) {}
    }

    fun updateRequestParams(interval: Long, distance: Float) {
        this.requestInterval = interval
        this.requestDistance = distance
    }
}