package com.example.hoarder.collection.source

import android.location.Location
import com.example.hoarder.common.math.RoundingUtils
import com.example.hoarder.sensors.SensorManager
import kotlin.math.roundToInt

class LocationCollector(private val sensorMgr: SensorManager) {

    fun collect(dm: MutableMap<String, Any>, gpsPrecision: Int, speedPrecision: Int, altPrecision: Int) {
        try {
            sensorMgr.getLocation()?.let { location ->
                dm["alt"] = sensorMgr.getFilteredAltitude(altPrecision)

                val sk = (location.speed * 3.6).roundToInt()
                dm["spd"] = RoundingUtils.rsp(sk, speedPrecision)

                val (prec, _) = if (gpsPrecision == -1) RoundingUtils.smartGPSPrecision(location.speed) else Pair(gpsPrecision, gpsPrecision)
                val (rl, rlo, ac) = calculateLocationPrecision(location, prec)
                dm["lat"] = rl
                dm["lon"] = rlo
                dm["acc"] = ac
            }
        } catch (e: Exception) { /* Location data not available */ }
    }

    private fun calculateLocationPrecision(location: Location, prec: Int): Triple<Double, Double, Int> {
        return try {
            when (prec) {
                0 -> Triple(
                    (location.latitude * 1000000.0).roundToInt() / 1000000.0,
                    (location.longitude * 1000000.0).roundToInt() / 1000000.0,
                    location.accuracy.roundToInt()
                )
                20 -> Triple(
                    (location.latitude * 10000).roundToInt() / 10000.0,
                    (location.longitude * 10000).roundToInt() / 10000.0,
                    (location.accuracy / 20).roundToInt() * 20
                )
                100 -> Triple(
                    (location.latitude * 1000).roundToInt() / 1000.0,
                    (location.longitude * 1000).roundToInt() / 1000.0,
                    (location.accuracy / 100).roundToInt() * 100
                )
                1000 -> Triple(
                    (location.latitude * 100).roundToInt() / 100.0,
                    (location.longitude * 100).roundToInt() / 100.0,
                    (location.accuracy / 1000).roundToInt() * 1000
                )
                10000 -> Triple(
                    (location.latitude * 10).roundToInt() / 10.0,
                    (location.longitude * 10).roundToInt() / 10.0,
                    (location.accuracy / 10000).roundToInt() * 10000
                )
                else -> Triple(
                    (location.latitude * 1000000.0).roundToInt() / 1000000.0,
                    (location.longitude * 1000000.0).roundToInt() / 1000000.0,
                    location.accuracy.roundToInt()
                )
            }
        } catch (e: Exception) {
            Triple(0.0, 0.0, 0)
        }
    }
}