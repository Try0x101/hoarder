package com.example.hoarder.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import java.util.concurrent.atomic.AtomicBoolean

class SensorListenerManager(
    private val context: Context,
    private val locationManager: LocationManager,
    private val sensorManager: SensorManager,
    private val locationListener: LocationListener,
    private val sensorEventListener: SensorEventListener
) {
    private val listenersRegistered = AtomicBoolean(false)

    fun registerListeners() {
        if (listenersRegistered.compareAndSet(false, true)) {
            registerLocationListeners()
            registerPressureSensor()
        }
    }

    fun unregisterListeners() {
        if (listenersRegistered.compareAndSet(true, false)) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) { /* Ignored */ }

            try {
                sensorManager.unregisterListener(sensorEventListener)
            } catch (e: Exception) { /* Ignored */ }
        }
    }

    private fun registerLocationListeners() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
            }
        } catch (e: SecurityException) { /* Ignored */ }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0f, locationListener)
            }
        } catch (e: SecurityException) { /* Ignored */ }
    }

    private fun registerPressureSensor() {
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        pressureSensor?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }
}