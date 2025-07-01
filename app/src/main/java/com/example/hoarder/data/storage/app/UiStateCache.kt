package com.example.hoarder.data.storage.app

import com.example.hoarder.data.models.TelemetryRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class UiStateCache {
    private val dataMap = ConcurrentHashMap<String, Any>()
    private val lastJsonData = AtomicReference<String?>(null)
    private val lastTelemetryRecord = AtomicReference<TelemetryRecord?>(null)

    fun setData(key: String, value: Any) {
        dataMap[key] = value
    }

    fun getData(key: String): Any? {
        return dataMap[key]
    }

    fun clearData() {
        dataMap.clear()
        lastJsonData.set(null)
        lastTelemetryRecord.set(null)
    }

    fun setJsonData(json: String?) {
        lastJsonData.set(json)
    }

    fun getJsonData(): String? {
        return lastJsonData.get()
    }

    fun setLastTelemetryRecord(record: TelemetryRecord?) {
        lastTelemetryRecord.set(record)
    }

    fun getLastTelemetryRecord(): TelemetryRecord? {
        return lastTelemetryRecord.get()
    }

    fun containsKey(key: String): Boolean {
        return dataMap.containsKey(key)
    }
}