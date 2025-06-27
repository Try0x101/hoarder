// app/src/main/java/com/example/hoarder/sensors/AltitudeKalmanFilter.kt
package com.example.hoarder.sensors

import kotlin.math.floor
import kotlin.math.max

class AltitudeKalmanFilter {
    private var x = 0.0
    private var p = 10.0
    private var q = 0.1
    private var r = 5.0
    private var r2 = 1.0
    private var isInitialized = false

    fun reset() {
        isInitialized = false
    }

    fun update(gpsAltitude: Double, barometerAltitude: Double, gpsAccuracy: Float): Double {
        if (!isInitialized && !gpsAltitude.isNaN()) {
            x = gpsAltitude
            isInitialized = true
            return x
        }

        r = maxOf(5.0, gpsAccuracy.toDouble())

        p += q

        if (!gpsAltitude.isNaN()) {
            val k = p / (p + r)
            x += k * (gpsAltitude - x)
            p *= (1 - k)
        }

        if (!barometerAltitude.isNaN()) {
            val k2 = p / (p + r2)
            x += k2 * (barometerAltitude - x)
            p *= (1 - k2)
        }

        return x
    }

    fun applySmartRounding(altitude: Double, precisionType: Int): Int {
        if (precisionType == 0) {
            return altitude.toInt()
        }

        return when {
            altitude < 100 -> max(0, (floor(altitude / 10.0) * 10).toInt())
            altitude < 1000 -> max(0, (floor(altitude / 50.0) * 50).toInt())
            else -> max(0, (floor(altitude / 100.0) * 100).toInt())
        }
    }
}