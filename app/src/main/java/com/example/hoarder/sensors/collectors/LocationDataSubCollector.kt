package com.example.hoarder.sensors.collectors

import com.example.hoarder.collection.source.LocationCollector
import com.example.hoarder.sensors.SensorManager

class LocationDataSubCollector(
    private val sensorMgr: SensorManager,
    private val locationCollector: LocationCollector,
    private val precisionCache: Map<String, Int>,
    private val lastDataSnapshot: Map<String, Any>
) {

    fun collectDirect(dataMap: MutableMap<String, Any>) {
        val location = sensorMgr.getLocation()
        if (location != null) {
            locationCollector.collect(
                dataMap,
                precisionCache["gps"] ?: -1,
                precisionCache["speed"] ?: -1,
                precisionCache["altitude"] ?: -1
            )
        }
    }

    fun collectIntelligent(dataMap: MutableMap<String, Any>, isMoving: Boolean) {
        val location = sensorMgr.getLocation()
        if (location != null && (isMoving || shouldUpdateLocation())) {
            locationCollector.collect(
                dataMap,
                precisionCache["gps"] ?: -1,
                precisionCache["speed"] ?: -1,
                precisionCache["altitude"] ?: -1
            )
        } else if (lastDataSnapshot.containsKey("y")) {
            listOf("y", "x", "a", "ac", "s").forEach { key ->
                lastDataSnapshot[key]?.let { dataMap[key] = it }
            }
        }
    }

    private fun shouldUpdateLocation(): Boolean {
        val location = sensorMgr.getLocation() ?: return false
        val lastLat = lastDataSnapshot["y"] as? Double ?: return true
        val lastLon = lastDataSnapshot["x"] as? Double ?: return true

        val results = FloatArray(1)
        android.location.Location.distanceBetween(lastLat, lastLon, location.latitude, location.longitude, results)
        return results[0] > 10.0
    }
}