package com.example.hoarder.sensors

import kotlin.math.floor
import kotlin.math.max

class AltitudeKalmanFilter {
    private var x = 0.0 // Estimated altitude
    private var p = 10.0 // Estimated error
    private var q = 0.1 // Process noise
    private var r = 5.0 // Measurement noise (GPS)
    private var r2 = 1.0 // Measurement noise (Barometer)
    private var isInitialized = false

    fun reset() {
        isInitialized = false
    }

    fun update(gpsAltitude: Double, barometerAltitude: Double, gpsAccuracy: Float): Double {
        // Initialize if needed
        if (!isInitialized && !gpsAltitude.isNaN()) {
            x = gpsAltitude
            isInitialized = true
            return x
        }

        // Adjust measurement noise based on GPS accuracy
        r = maxOf(5.0, gpsAccuracy.toDouble())

        // Prediction step
        p += q

        // Update step for GPS
        if (!gpsAltitude.isNaN()) {
            val k = p / (p + r)
            x += k * (gpsAltitude - x)
            p *= (1 - k)
        }

        // Update step for barometer (if available)
        if (!barometerAltitude.isNaN()) {
            val k2 = p / (p + r2)
            x += k2 * (barometerAltitude - x)
            p *= (1 - k2)
        }

        return x
    }

    // Apply smart rounding based on the new precision levels
    fun applySmartRounding(altitude: Double, precisionType: Int): Int {
        // If maximum precision (0), return the actual value from Kalman filter
        if (precisionType == 0) {
            return altitude.toInt()
        }

        // Smart precision (-1)
        return when {
            altitude < 100 -> max(0, (floor(altitude / 25.0) * 25).toInt()) // Below 100m: 25m precision
            altitude < 1000 -> max(0, (floor(altitude / 50.0) * 50).toInt()) // 100-1000m: 50m precision
            else -> max(0, (floor(altitude / 100.0) * 100).toInt()) // Above 1000m: 100m precision
        }
    }
}