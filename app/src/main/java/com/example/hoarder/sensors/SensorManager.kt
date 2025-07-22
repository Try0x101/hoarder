package com.example.hoarder.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager as AndroidSensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import androidx.core.content.ContextCompat
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
        barometricValue = null; lastBarometerAltitude = Double.NaN
    }
}

class GpsStateAgent {
    val lastLocation = AtomicReference<Location?>(null)
    var gpsActive = AtomicBoolean(true)
    var lastStationaryTime = AtomicReference(0L)
    val locationHistory = mutableListOf<Location>()
    fun shouldAcceptLocation(location: Location): Boolean {
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

    fun updateLocationHistory(location: Location) {
        locationHistory.add(location)
        if (locationHistory.size > 5) {
            locationHistory.removeAt(0)
        }
    }
}

class SensorListenerOrchestrator(
    private val ctx: Context,
    private val locationManager: LocationManager,
    private val androidSensorManager: AndroidSensorManager,
    private val gpsAgent: GpsStateAgent,
    private val barometerAgent: BarometerAgent,
    private val locationListener: LocationListener
) {
    fun registerListeners() {
        try {
            if (ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0f,
                    locationListener
                )
            }
            androidSensorManager.registerListener(
                barometerAgent,
                androidSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE),
                AndroidSensorManager.SENSOR_DELAY_NORMAL
            )
        } catch (_: Exception) {
        }
    }

    fun unregisterListeners() {
        try {
            locationManager.removeUpdates(locationListener)
            androidSensorManager.unregisterListener(barometerAgent)
        } catch (_: Exception) {
        }
    }

    fun registerPassiveListener() {
        // Implement passive registration if needed, based on refactored logic
    }

    fun pauseGps() {}
    fun resumeGps() {}
    fun updateRequestParams(interval: Long, distance: Float) {}
}

class SensorManagerCore(
    private val ctx: Context,
    private val handler: Handler,
    private val powerManager: PowerManager
) {
    private lateinit var locationManager: LocationManager
    private lateinit var androidSensorManager: AndroidSensorManager
    private val gpsAgent = GpsStateAgent()
    private lateinit var listenerManager: SensorListenerOrchestrator
    private val barometerAgent by lazy { BarometerAgent(androidSensorManager) }
    private val altitudeFilter = AltitudeKalmanFilter()
    private val lastGpsAltitude = AtomicReference<Double>(Double.NaN)
    private val lastGpsAccuracy = AtomicReference<Float>(10f)
    private val lastReportedAltitude = AtomicReference<Double>(Double.NaN)
    private val MAX_ALTITUDE_CHANGE_PER_TICK = 15.0

    private val sensorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        powerManager.powerState
            .onEach { state ->
                if (this::locationManager.isInitialized) {
                    listenerManager.unregisterListeners()
                    if (state.mode == Prefs.POWER_MODE_PASSIVE) {
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
        if (gpsAgent.lastLocation.get() == null) {
            locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            androidSensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager
            listenerManager = SensorListenerOrchestrator(
                ctx,
                locationManager,
                androidSensorManager,
                gpsAgent,
                barometerAgent,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (gpsAgent.shouldAcceptLocation(location)) {
                            gpsAgent.lastLocation.set(location)
                            gpsAgent.updateLocationHistory(location)
                            lastGpsAltitude.set(location.altitude.toDouble())
                            lastGpsAccuracy.set(location.accuracy)
                        }
                    }

                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                })
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
            if (gpsAgent.lastStationaryTime.get() == 0L) {
                gpsAgent.lastStationaryTime.set(currentTime)
            } else if ((currentTime - gpsAgent.lastStationaryTime.get()) > 600000L && gpsAgent.gpsActive.get()) {
                gpsAgent.gpsActive.set(false)
                listenerManager.pauseGps()
            }
        } else {
            gpsAgent.lastStationaryTime.set(0L)
            if (!gpsAgent.gpsActive.get()) {
                gpsAgent.gpsActive.set(true)
                listenerManager.resumeGps()
            }
        }
    }

    fun cleanup() {
        listenerManager.unregisterListeners()
        resetState()
        sensorScope.cancel()
    }

    private fun resetState() {
        altitudeFilter.reset()
        gpsAgent.lastLocation.set(null)
        barometerAgent.reset()
        lastGpsAltitude.set(Double.NaN)
        lastGpsAccuracy.set(10f)
        lastReportedAltitude.set(Double.NaN)
        gpsAgent.locationHistory.clear()
        gpsAgent.gpsActive.set(true)
        gpsAgent.lastStationaryTime.set(0L)
    }

    fun getLocation(): Location? = gpsAgent.lastLocation.get()

    fun getFilteredAltitude(altitudePrecision: Int): Int {
        val gpsAlt = lastGpsAltitude.get()
        val baroAlt = barometerAgent.lastBarometerAltitude
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

typealias SensorManager = SensorManagerCore