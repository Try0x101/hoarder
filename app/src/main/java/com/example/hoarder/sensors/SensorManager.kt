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
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class SensorManager(
    private val ctx: Context,
    private val handler: Handler,
    powerManager: PowerManager
) {
    private lateinit var locationManager: LocationManager
    private lateinit var androidSensorManager: AndroidSensorManager
    private lateinit var listenerManager: SensorListenerManager

    private val barometricValue = AtomicReference<Float?>(null)
    private val lastLocation = AtomicReference<Location?>(null)
    private val isInitialized = AtomicBoolean(false)
    private val gpsActive = AtomicBoolean(true)
    private val lastStationaryTime = AtomicReference<Long>(0L)
    private val locationHistory = mutableListOf<Location>()

    private val altitudeFilter = AltitudeKalmanFilter()
    private val lastGpsAltitude = AtomicReference<Double>(Double.NaN)
    private val lastBarometerAltitude = AtomicReference<Double>(Double.NaN)
    private val lastGpsAccuracy = AtomicReference<Float>(10f)

    private val lastReportedAltitude = AtomicReference<Double>(Double.NaN)
    private val MAX_ALTITUDE_CHANGE_PER_TICK = 15.0

    private val sensorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (shouldAcceptLocation(location)) {
                lastLocation.set(location)
                lastGpsAltitude.set(location.altitude)
                lastGpsAccuracy.set(location.accuracy)
                updateLocationHistory(location)
            }
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

    init {
        powerManager.powerState
            .onEach { state ->
                if (isInitialized.get()) {
                    listenerManager.unregisterListeners()
                    if (state.mode == com.example.hoarder.data.storage.app.Prefs.POWER_MODE_PASSIVE) {
                        listenerManager.registerPassiveListener()
                    } else {
                        configureListeners(state.mode, state.isMoving)
                        manageGpsState(state.isMoving)
                        listenerManager.registerListeners()
                    }
                }
            }
            .launchIn(sensorScope)
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            androidSensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager
            listenerManager = SensorListenerManager(ctx, locationManager, androidSensorManager, locationListener, sensorEventListener, 1000, 0f)
            listenerManager.registerListeners()
        }
    }

    private fun configureListeners(mode: Int, isMoving: Boolean) {
        val (interval, distance) = when (mode) {
            Prefs.POWER_MODE_OPTIMIZED -> if (isMoving) Pair(5000L, 5f) else Pair(60000L, 100f)
            else -> Pair(1000L, 0f)
        }
        listenerManager.updateRequestParams(interval, distance)
    }

    private fun manageGpsState(isMoving: Boolean) {
        val currentTime = System.currentTimeMillis()

        if (!isMoving) {
            if (lastStationaryTime.get() == 0L) {
                lastStationaryTime.set(currentTime)
            } else if ((currentTime - lastStationaryTime.get()) > 600000L && gpsActive.get()) {
                gpsActive.set(false)
                listenerManager.pauseGps()
            }
        } else {
            lastStationaryTime.set(0L)
            if (!gpsActive.get()) {
                gpsActive.set(true)
                listenerManager.resumeGps()
            }
        }
    }

    private fun shouldAcceptLocation(location: Location): Boolean {
        if (locationHistory.size >= 3) {
            val recent = locationHistory.takeLast(3)
            if (recent.all {
                    location.distanceTo(it) < 10f &&
                            Math.abs(location.altitude - it.altitude) < 5.0
                }) {
                return false
            }
        }
        return true
    }

    private fun updateLocationHistory(location: Location) {
        locationHistory.add(location)
        if (locationHistory.size > 5) {
            locationHistory.removeAt(0)
        }
    }

    fun cleanup() {
        if (isInitialized.compareAndSet(true, false)) {
            listenerManager.unregisterListeners()
            resetState()
            sensorScope.cancel()
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
        locationHistory.clear()
        gpsActive.set(true)
        lastStationaryTime.set(0L)
    }

    fun getLocation(): Location? = lastLocation.get()

    fun getFilteredAltitude(altitudePrecision: Int): Int {
        if (!isInitialized.get()) return 0

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

        return max(0, processedAltitude)
    }
}