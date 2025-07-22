package com.example.hoarder.sensors

import android.content.Context
import android.hardware.SensorManager as AndroidSensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import com.example.hoarder.sensors.agents.BarometerAgent
import com.example.hoarder.sensors.agents.GpsStateAgent
import com.example.hoarder.sensors.orchestration.SensorListenerOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

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
        powerManager.powerState.onEach { state ->
            if (!this::locationManager.isInitialized) return@onEach
            listenerManager.unregisterListeners()
            if (state.mode == Prefs.POWER_MODE_PASSIVE) {
                listenerManager.registerPassiveListener()
            } else {
                configureListeners(state.mode, state.isMoving)
                listenerManager.registerListeners()
                manageGpsState(state.isMoving)
            }
        }.launchIn(sensorScope)
    }

    fun init() {
        if (gpsAgent.lastLocation.get() != null) return
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        androidSensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager
        listenerManager = SensorListenerOrchestrator(ctx, locationManager, androidSensorManager, barometerAgent, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (gpsAgent.shouldAcceptLocation(location)) {
                    gpsAgent.lastLocation.set(location)
                    gpsAgent.updateLocationHistory(location)
                    lastGpsAltitude.set(location.altitude)
                    lastGpsAccuracy.set(location.accuracy)
                }
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        })
        listenerManager.registerListeners()
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
            if (gpsAgent.lastStationaryTime.get() == 0L) gpsAgent.lastStationaryTime.set(currentTime)
            else if ((currentTime - gpsAgent.lastStationaryTime.get()) > 600000L && gpsAgent.gpsActive.getAndSet(false)) {
                listenerManager.unregisterGpsListener()
            }
        } else {
            gpsAgent.lastStationaryTime.set(0L)
            if (!gpsAgent.gpsActive.getAndSet(true)) listenerManager.registerGpsListener()
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
        val currentAltitude = altitudeFilter.update(lastGpsAltitude.get(), barometerAgent.lastBarometerAltitude, lastGpsAccuracy.get())
        val reportedAlt = lastReportedAltitude.get()
        val newReportedAlt = if (reportedAlt.isNaN()) currentAltitude
        else {
            val change = currentAltitude - reportedAlt
            if (Math.abs(change) > MAX_ALTITUDE_CHANGE_PER_TICK) reportedAlt + Math.signum(change) * MAX_ALTITUDE_CHANGE_PER_TICK
            else currentAltitude
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