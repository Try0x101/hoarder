package com.example.hoarder.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class SensorManager(private val ctx: Context, private val handler: Handler) {
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val barometricValue = AtomicReference<Float?>(null)
    private val lastLocation = AtomicReference<Location?>(null)
    private val isInitialized = AtomicBoolean(false)
    private val listenersRegistered = AtomicBoolean(false)

    private val altitudeFilter = AltitudeKalmanFilter()
    private val lastGpsAltitude = AtomicReference<Double>(Double.NaN)
    private val lastBarometerAltitude = AtomicReference<Double>(Double.NaN)
    private val lastGpsAccuracy = AtomicReference<Float>(10f)

    private val lastReportedAltitude = AtomicReference<Double>(Double.NaN)
    private val MAX_ALTITUDE_CHANGE_PER_TICK = 15.0

    private var lastAltitudeUpdateTime: Long = 0L
    private var lastEmittedAltitude: Int = 0
    private val ALTITUDE_UPDATE_INTERVAL_MS = 10000L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            try {
                lastLocation.set(location)
                lastGpsAltitude.set(location.altitude)
                lastGpsAccuracy.set(location.accuracy)
            } catch (e: Exception) {
                // Error processing location update
            }
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                try {
                    val pressureInHpa = event.values[0]
                    barometricValue.set(pressureInHpa)
                    val standardPressure = 1013.25f
                    val altitude = 44330.0 * (1.0 - Math.pow((pressureInHpa / standardPressure).toDouble(), 0.1903))
                    lastBarometerAltitude.set(altitude)
                } catch (e: Exception) {
                    // Error processing pressure sensor data
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

                registerListeners()
            } catch (e: Exception) {
                isInitialized.set(false)
                throw e
            }
        }
    }

    private fun registerListeners() {
        if (listenersRegistered.compareAndSet(false, true)) {
            try {
                // Register location listeners
                try {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            1000,
                            0f,
                            locationListener
                        )
                    }
                } catch (e: SecurityException) {
                    // GPS permission not granted
                }

                try {
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            1000,
                            0f,
                            locationListener
                        )
                    }
                } catch (e: SecurityException) {
                    // Network location permission not granted
                }

                // Register pressure sensor
                val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
                if (pressureSensor != null) {
                    val registered = sensorManager.registerListener(
                        sensorEventListener,
                        pressureSensor,
                        SensorManager.SENSOR_DELAY_NORMAL
                    )
                    if (!registered) {
                        // Pressure sensor registration failed
                    }
                }
            } catch (e: Exception) {
                listenersRegistered.set(false)
                throw e
            }
        }
    }

    fun cleanup() {
        if (isInitialized.compareAndSet(true, false)) {
            unregisterListeners()
            resetState()
        }
    }

    private fun unregisterListeners() {
        if (listenersRegistered.compareAndSet(true, false)) {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                // Location manager already cleaned up or permission revoked
            }

            try {
                sensorManager.unregisterListener(sensorEventListener)
            } catch (e: Exception) {
                // Sensor manager already cleaned up
            }
        }
    }

    private fun resetState() {
        altitudeFilter.reset()
        lastLocation.set(null)
        barometricValue.set(null)
        lastGpsAltitude.set(Double.NaN)
        lastBarometerAltitude.set(Double.NaN)
        lastGpsAccuracy.set(10f)
        lastReportedAltitude.set(Double.NaN)
        lastAltitudeUpdateTime = 0L
        lastEmittedAltitude = 0
    }

    fun getLocation(): Location? = lastLocation.get()
    fun getBarometricValue(): Float? = barometricValue.get()

    fun getFilteredAltitude(altitudePrecision: Int): Int {
        if (!isInitialized.get()) return 0

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAltitudeUpdateTime < ALTITUDE_UPDATE_INTERVAL_MS && lastAltitudeUpdateTime != 0L) {
            return lastEmittedAltitude
        }

        val gpsAlt = lastGpsAltitude.get()
        val baroAlt = lastBarometerAltitude.get()
        val gpsAcc = lastGpsAccuracy.get()

        val currentAltitude = altitudeFilter.update(gpsAlt, baroAlt, gpsAcc)

        val reportedAlt = lastReportedAltitude.get()
        val newReportedAlt = if (reportedAlt.isNaN()) {
            currentAltitude
        } else {
            val change = currentAltitude - reportedAlt
            if (Math.abs(change) > MAX_ALTITUDE_CHANGE_PER_TICK) {
                reportedAlt + Math.signum(change) * MAX_ALTITUDE_CHANGE_PER_TICK
            } else {
                currentAltitude
            }
        }

        lastReportedAltitude.set(newReportedAlt)

        val processedAltitude = when (altitudePrecision) {
            0 -> newReportedAlt.toInt()
            -1 -> altitudeFilter.applySmartRounding(newReportedAlt, -1)
            else -> (Math.floor(newReportedAlt / altitudePrecision) * altitudePrecision).toInt()
        }

        lastEmittedAltitude = max(0, processedAltitude)
        lastAltitudeUpdateTime = currentTime

        return lastEmittedAltitude
    }

    fun isLocationAvailable(): Boolean {
        return isInitialized.get() && lastLocation.get() != null
    }

    fun isSensorAvailable(): Boolean {
        return isInitialized.get() && barometricValue.get() != null
    }
}