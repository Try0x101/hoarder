package com.example.hoarder.ui.formatters

object PrecisionFormatter {
    fun getGpsPrecisionString(v: Int) = when(v) {
        -1 -> "Smart"
        0 -> "Maximum"
        20 -> "20 Meters"
        100 -> "100 Meters"
        1000 -> "1 Kilometer"
        10000 -> "10 Kilometers"
        else -> "Unknown"
    }

    fun getGpsAltitudePrecisionString(v: Int) = when(v) {
        -1 -> "Smart"
        0 -> "Maximum"
        2 -> "2 Meters"
        10 -> "10 Meters"
        25 -> "25 Meters"
        50 -> "50 Meters"
        100 -> "100 Meters"
        else -> "Unknown"
    }

    fun getRssiPrecisionString(v: Int) = when(v) {
        -1 -> "Smart"
        0 -> "Maximum"
        3 -> "3 dBm"
        5 -> "5 dBm"
        10 -> "10 dBm"
        else -> "Unknown"
    }

    fun getBatteryPrecisionString(v: Int) = when(v) {
        -1 -> "Smart"
        0 -> "Maximum"
        2 -> "2 Percent"
        5 -> "5 Percent"
        10 -> "10 Percent"
        else -> "Unknown"
    }

    fun getNetworkPrecisionString(v: Int) = when(v) {
        0 -> "Smart"
        -2 -> "Float"
        1 -> "1 Mbps"
        2 -> "2 Mbps"
        5 -> "5 Mbps"
        else -> "Unknown"
    }

    fun getSpeedPrecisionString(v: Int) = when(v) {
        -1 -> "Smart"
        0 -> "Maximum"
        1 -> "1 km/h"
        3 -> "3 km/h"
        5 -> "5 km/h"
        10 -> "10 km/h"
        else -> "Unknown"
    }
}