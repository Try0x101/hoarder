package com.example.hoarder.sensors.collectors

import com.example.hoarder.collection.source.BatteryCollector

class BatteryDataSubCollector(
    private val batteryCollector: BatteryCollector,
    private val precisionCache: Map<String, Int>,
    private val lastDataSnapshot: Map<String, Any>
) {
    private var lastBatteryLevel = -1

    fun collectDirect(dataMap: MutableMap<String, Any>) {
        batteryCollector.collect(dataMap, precisionCache["battery"] ?: -1)
    }

    fun collectIntelligent(dataMap: MutableMap<String, Any>) {
        val tempMap = mutableMapOf<String, Any>()
        batteryCollector.collect(tempMap, precisionCache["battery"] ?: -1)

        val currentBatteryLevel = tempMap["p"] as? Int ?: -1
        if (currentBatteryLevel != lastBatteryLevel || lastBatteryLevel == -1) {
            dataMap.putAll(tempMap)
            lastBatteryLevel = currentBatteryLevel
        } else if (lastDataSnapshot.containsKey("p")) {
            dataMap["p"] = lastDataSnapshot["p"]!!
            tempMap["c"]?.let { dataMap["c"] = it }
        }
    }
}