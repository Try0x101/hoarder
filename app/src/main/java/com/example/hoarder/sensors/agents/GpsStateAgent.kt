package com.example.hoarder.sensors.agents

import android.location.Location
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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