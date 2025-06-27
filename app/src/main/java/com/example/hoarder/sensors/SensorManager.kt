// app/src/main/java/com/example/hoarder/sensors/SensorManager.kt
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
import kotlin.math.max

class SensorManager(private val ctx: Context, private val handler: Handler) {
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var barometricValue: Float? = null
    private var lastLocation: Location? = null

    private val altitudeFilter = AltitudeKalmanFilter()
    var lastGpsAltitude: Double = Double.NaN
    var lastBarometerAltitude: Double = Double.NaN
    var lastGpsAccuracy: Float = 10f

    private var lastReportedAltitude: Double = Double.NaN
    private val MAX_ALTITUDE_CHANGE_PER_TICK = 15.0

    private var lastAltitudeUpdateTime: Long = 0L
    private var lastEmittedAltitude: Int = 0
    private val ALTITUDE_UPDATE_INTERVAL_MS = 10000L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            lastGpsAltitude = location.altitude
            lastGpsAccuracy = location.accuracy
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                barometricValue = event.values[0]
                val pressureInHpa = event.values[0]
                val standardPressure = 1013.25f
                lastBarometerAltitude = 44330.0 * (1.0 - Math.pow((pressureInHpa / standardPressure).toDouble(), 0.1903))
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun init() {
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0f, locationListener)
        } catch (e: SecurityException) {}

        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor != null) {
            sensorManager.registerListener(sensorEventListener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun cleanup() {
        try {
            locationManager.removeUpdates(locationListener)
            sensorManager.unregisterListener(sensorEventListener)
            altitudeFilter.reset()
            lastReportedAltitude = Double.NaN
            lastAltitudeUpdateTime = 0L
            lastEmittedAltitude = 0
        } catch (e: Exception) {}
    }

    fun getLocation(): Location? = lastLocation
    fun getBarometricValue(): Float? = barometricValue

    fun getFilteredAltitude(altitudePrecision: Int): Int {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAltitudeUpdateTime < ALTITUDE_UPDATE_INTERVAL_MS && lastAltitudeUpdateTime != 0L) {
            return lastEmittedAltitude
        }

        val currentAltitude = altitudeFilter.update(lastGpsAltitude, lastBarometerAltitude, lastGpsAccuracy)

        if (lastReportedAltitude.isNaN()) {
            lastReportedAltitude = currentAltitude
        } else {
            val change = currentAltitude - lastReportedAltitude
            if (Math.abs(change) > MAX_ALTITUDE_CHANGE_PER_TICK) {
                lastReportedAltitude += Math.signum(change) * MAX_ALTITUDE_CHANGE_PER_TICK
            } else {
                lastReportedAltitude = currentAltitude
            }
        }

        val processedAltitude = when (altitudePrecision) {
            0 -> lastReportedAltitude.toInt()
            -1 -> altitudeFilter.applySmartRounding(lastReportedAltitude, -1)
            else -> (Math.floor(lastReportedAltitude / altitudePrecision) * altitudePrecision).toInt()
        }

        lastEmittedAltitude = max(0, processedAltitude)
        lastAltitudeUpdateTime = currentTime

        return lastEmittedAltitude
    }
}