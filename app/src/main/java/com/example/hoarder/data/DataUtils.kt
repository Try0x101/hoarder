package com.example.hoarder.data

import android.app.ActivityManager
import android.content.Context
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object DataUtils{
    fun rs(rv: Int, rp: Int): Int {
        if (rp <= 0) return rv
        return (rv / rp) * rp
    }

    fun smartRSSI(v: Int): Int {
        return when {
            v >= -10 -> v // Invalid/very strong signal, keep exact
            v < -110 -> v // Very weak signal, keep exact for precision
            v < -90 -> (v / 5) * 5 // Moderate signal, round to 5 dBm
            else -> (v / 10) * 10 // Strong signal, round to 10 dBm
        }
    }

    fun smartBattery(p: Int): Int {
        return when {
            p < 0 -> 0 // Invalid negative battery
            p > 100 -> 100 // Invalid over-100% battery
            p <= 10 -> p // Critical battery, keep exact
            p <= 50 -> (p / 5) * 5 // Low battery, round to 5%
            else -> (p / 10) * 10 // Normal battery, round to 10%
        }
    }

    fun rb(p: Int, pr: Int): Int {
        if (pr <= 0) return p
        if (p < 0) return 0
        if (p > 100) return 100
        if (p <= 10 && pr > 1) return p // Keep precision for critical battery
        return (p / pr) * pr
    }

    fun rn(v: Int, pr: Int): Number {
        if (v < 0) return 0 // Invalid negative speed

        val mbps = v.toDouble() / 1024.0

        // If speed is <= 0.1 Mbps, return 0
        if (mbps <= 0.1) {
            return 0
        }

        // Float precision mode (-2): Return as float with 1 decimal place
        if (pr == -2) {
            return (Math.round(mbps * 10) / 10.0).toFloat()
        }

        // Smart rounding (0): Apply the tiered precision logic
        if (pr == 0) {
            return when {
                mbps < 2.0 -> (Math.round(mbps * 10) / 10.0).toFloat()
                mbps < 7.0 -> Math.floor(mbps).toInt()
                else -> (Math.floor(mbps / 5.0) * 5).toInt()
            }
        }

        // Fixed precision: Round to nearest lower multiple of precision value
        if (pr < 0) return 0 // Invalid precision

        val rounded = (Math.floor(mbps / pr) * pr).toInt()
        return if (mbps > 0.1 && rounded == 0 && pr >= 1) pr else rounded
    }

    fun rsp(s: Int, pr: Int): Int {
        if (s < 0) return 0 // Invalid negative speed

        if (pr == -1) {
            return when {
                s < 2 -> 0 // Very slow, show as stationary
                s < 10 -> ((s + 2) / 3) * 3 // Round up to nearest 3 km/h
                else -> ((s + 9) / 10) * 10 // Round up to nearest 10 km/h
            }
        }
        if (pr <= 0) return s
        return (s / pr) * pr
    }

    fun smartGPSPrecision(s: Float): Pair<Int, Int> {
        if (s < 0) return Pair(1000, 1000) // Invalid speed

        val sk = (s * 3.6).toInt() // Convert m/s to km/h
        return when {
            sk < 4 -> Pair(1000, 1000) // Stationary/very slow: low precision
            sk < 40 -> Pair(20, 20) // Walking/cycling: medium precision
            sk < 140 -> Pair(100, 100) // Driving: lower precision
            else -> Pair(1000, 1000) // High speed: lowest precision
        }
    }

    fun smartBarometer(v: Int): Int {
        return when {
            v < -1000 -> v // Extreme negative altitude (possible), keep exact
            v < 0 -> v // Below sea level, keep exact for precision
            v < 100 -> max(0, (floor(v / 5.0) * 5).toInt()) // Low altitude: 5m precision
            v < 1000 -> max(0, (floor(v / 10.0) * 10).toInt()) // Medium altitude: 10m precision
            else -> max(0, (floor(v / 50.0) * 50).toInt()) // High altitude: 50m precision
        }
    }

    fun roundBarometer(v: Int, precision: Int): Int {
        if (precision <= 0) return v

        return when {
            v < -1000 -> v // Extreme values, keep exact
            v < 0 && precision > 10 -> v // Negative altitude with coarse precision, keep exact
            else -> (floor(v / precision.toDouble()) * precision).toInt()
        }
    }

    fun isServiceRunning(ctx: Context, cls: Class<*>): Boolean {
        return try {
            val m = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = m.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == cls.name }
        } catch (e: Exception) {
            false
        }
    }

    // New helper functions for consistent validation
    fun validateLatitude(lat: Double): Boolean {
        return lat >= -90.0 && lat <= 90.0
    }

    fun validateLongitude(lon: Double): Boolean {
        return lon >= -180.0 && lon <= 180.0
    }

    fun validateAccuracy(acc: Float): Boolean {
        return acc >= 0.0f && acc <= 10000.0f // Reasonable GPS accuracy range
    }

    fun validateSpeed(speed: Float): Boolean {
        return speed >= 0.0f && speed <= 150.0f // Max ~540 km/h seems reasonable
    }

    fun validateAltitude(alt: Double): Boolean {
        return alt >= -500.0 && alt <= 10000.0 // From Dead Sea to Mount Everest+ margin
    }

    fun validateRSSI(rssi: Int): Boolean {
        return rssi >= -150 && rssi <= -30 // Reasonable cellular signal range
    }

    fun validateBatteryLevel(level: Int): Boolean {
        return level >= 0 && level <= 100
    }

    fun validateNetworkSpeed(speedKbps: Int): Boolean {
        return speedKbps >= 0 && speedKbps <= 10000000 // Up to 10 Gbps seems reasonable
    }

    // Timestamp utilities for consistent epoch handling
    fun getCurrentTimestamp(): Long {
        val fixedEpoch = 1719705600L // June 30, 2025 00:00:00 UTC
        return System.currentTimeMillis() / 1000 - fixedEpoch
    }

    fun timestampToUnixEpoch(timestamp: Long): Long {
        val fixedEpoch = 1719705600L // June 30, 2025 00:00:00 UTC
        return timestamp + fixedEpoch
    }

    fun validateTimestamp(timestamp: Long): Boolean {
        // Timestamp should be reasonable (not more than 10 years in past/future from epoch)
        val tenYears = 10L * 365 * 24 * 60 * 60 // 10 years in seconds
        return timestamp >= -tenYears && timestamp <= tenYears
    }

    // Safe conversion utilities
    fun safeStringToInt(str: String?, default: Int = 0): Int {
        return try {
            str?.toIntOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun safeStringToLong(str: String?, default: Long = 0L): Long {
        return try {
            str?.toLongOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun safeStringToFloat(str: String?, default: Float = 0.0f): Float {
        return try {
            str?.toFloatOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun safeStringToDouble(str: String?, default: Double = 0.0): Double {
        return try {
            str?.toDoubleOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }
}