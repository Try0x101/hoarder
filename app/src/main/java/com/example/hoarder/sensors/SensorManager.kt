package com.example.hoarder.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager as AndroidSensorManager
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
    private lateinit var androidSensorManager: AndroidSensorManager
    private lateinit var listenerManager: SensorListenerManager

    private val barometricValue = AtomicReference<Float?>(null)
    private val lastLocation = AtomicReference<Location?>(null)
    private val isInitialized = AtomicBoolean(false)

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
            lastLocation.set(location)
            lastGpsAltitude.set(location.altitude)
            lastGpsAccuracy.set(location.accuracy)
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                val pressureInHpa = event.values[0]
                barometricValue.set(pressureInHpa)
                val altitude = AndroidSensorManager.getAltitude(AndroidSensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureInHpa)
                lastBarometerAltitude.set(altitude.toDouble())
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            androidSensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager
            listenerManager = SensorListenerManager(ctx, locationManager, androidSensorManager, locationListener, sensorEventListener)
            listenerManager.registerListeners()
        }
    }

    fun cleanup() {
        if (isInitialized.compareAndSet(true, false)) {
            listenerManager.unregisterListeners()
            resetState()
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
}