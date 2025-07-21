package com.example.hoarder.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class SensorListenerManager(
    private val context: Context,
    private val locationManager: LocationManager,
    private val sensorManager: SensorManager,
    private val locationListener: LocationListener,
    private val sensorEventListener: SensorEventListener,
    private var requestInterval: Long,
    private var requestDistance: Float
) {
    private val listenersRegistered = AtomicBoolean(false)
    private val gpsRegistered = AtomicBoolean(false)

    companion object {
        private const val PRESSURE_BATCH_LATENCY_US = 1_000_000L
    }

    fun registerListeners() {
        if (listenersRegistered.compareAndSet(false, true)) {
            registerPressureSensor()
            if (!gpsRegistered.getAndSet(true)) {
                registerLocationListenersInternal()
            }
        }
    }

    fun unregisterListeners() {
        if (listenersRegistered.compareAndSet(true, false)) {
            if (gpsRegistered.getAndSet(false)) {
                try {
                    locationManager.removeUpdates(locationListener)
                } catch (e: Exception) {
                    Log.e("SensorListenerManager", "Error removing location updates", e)
                }
            }

            try {
                sensorManager.unregisterListener(sensorEventListener)
            } catch (e: Exception) {
                Log.e("SensorListenerManager", "Error unregistering sensor listener", e)
            }
        }
    }

    fun pauseGps() {
        if (gpsRegistered.getAndSet(false)) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                Log.e("SensorListenerManager", "Error removing location updates (pauseGps)", e)
            }
        }
    }

    fun resumeGps() {
        if (listenersRegistered.get() && !gpsRegistered.getAndSet(true)) {
            registerLocationListenersInternal()
        }
    }

    fun updateRequestParams(interval: Long, distance: Float) {
        this.requestInterval = interval
        this.requestDistance = distance
    }

    private fun registerLocationListenersInternal() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, requestInterval, requestDistance, locationListener)
            }
        } catch (e: SecurityException) {
            Log.e("SensorListenerManager", "Security error requesting GPS location", e)
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, requestInterval, requestDistance, locationListener)
            }
        } catch (e: SecurityException) {
            Log.e("SensorListenerManager", "Security error requesting network location", e)
        }
    }

    private fun registerPressureSensor() {
        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        pressureSensor?.let {
            sensorManager.registerListener(
                sensorEventListener,
                it,
                SensorManager.SENSOR_DELAY_NORMAL,
                PRESSURE_BATCH_LATENCY_US.toInt()
            )
        }
    }

    fun registerPassiveListener() {
        if (listenersRegistered.compareAndSet(false, true)) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    0L,
                    0f,
                    locationListener
                )
            } catch (e: SecurityException) {
            }
        }
    }
}