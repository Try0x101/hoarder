// ConcurrentDataManager.kt
package com.example.hoarder

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe data manager for handling shared data between components
 */
class ConcurrentDataManager {
    private val dataMap = ConcurrentHashMap<String, Any>()
    private val lastJsonData = AtomicReference<String?>(null)

    fun setData(key: String, value: Any) {
        dataMap[key] = value
    }

    fun getData(key: String): Any? {
        return dataMap[key]
    }

    fun clearData() {
        dataMap.clear()
        lastJsonData.set(null)
    }

    fun setJsonData(json: String?) {
        lastJsonData.set(json)
    }

    fun getJsonData(): String? {
        return lastJsonData.get()
    }

    fun containsKey(key: String): Boolean {
        return dataMap.containsKey(key)
    }
}