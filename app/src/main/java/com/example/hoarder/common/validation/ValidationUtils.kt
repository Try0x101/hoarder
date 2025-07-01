package com.example.hoarder.common.validation

object ValidationUtils {
    fun validateLatitude(lat: Double): Boolean {
        return lat >= -90.0 && lat <= 90.0
    }

    fun validateLongitude(lon: Double): Boolean {
        return lon >= -180.0 && lon <= 180.0
    }

    fun validateAccuracy(acc: Float): Boolean {
        return acc >= 0.0f && acc <= 10000.0f
    }

    fun validateSpeed(speed: Float): Boolean {
        return speed >= 0.0f && speed <= 150.0f
    }

    fun validateAltitude(alt: Double): Boolean {
        return alt >= -500.0 && alt <= 10000.0
    }

    fun validateRSSI(rssi: Int): Boolean {
        return rssi >= -150 && rssi <= -30
    }

    fun validateBatteryLevel(level: Int): Boolean {
        return level >= 0 && level <= 100
    }

    fun validateNetworkSpeed(speedKbps: Int): Boolean {
        return speedKbps >= 0 && speedKbps <= 10000000
    }

    fun validateTimestamp(timestamp: Long): Boolean {
        val tenYears = 10L * 365 * 24 * 60 * 60
        return timestamp >= -tenYears && timestamp <= tenYears
    }
}