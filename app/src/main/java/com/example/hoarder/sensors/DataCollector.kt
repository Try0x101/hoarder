package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.provider.Settings
import com.example.hoarder.collection.source.BatteryCollector
import com.example.hoarder.collection.source.LocationCollector
import com.example.hoarder.data.processing.DeltaManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DataCollector(
    private val ctx: Context,
    private val h: Handler,
    private val callback: (String) -> Unit
) {
    private val ca = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val deviceId: String by lazy {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).take(4)
    }
    private val deviceModel: String = Build.MODEL
    private val sharedPrefs: SharedPreferences by lazy {
        ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
    }

    private val reusableDataMap = mutableMapOf<String, Any>()
    private val reusableStringMap = mutableMapOf<String, String>()

    private val precisionCache = mutableMapOf<String, Int>()
    private var lastPrecisionUpdate = 0L
    private val PRECISION_CACHE_TTL = 10000L

    private val deltaManager = AtomicReference<DeltaManager?>(null)
    private val collectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val sensorMgr = SensorManager(ctx, h)
    private val networkCollector = NetworkDataCollector(ctx)
    private val batteryCollector = BatteryCollector(ctx)
    private val locationCollector = LocationCollector(sensorMgr)

    private val dr = object : Runnable {
        override fun run() {
            if (ca.get()) {
                try {
                    collectData()
                } catch (e: Exception) { /* Continue collection */ }
                if (ca.get()) {
                    h.postDelayed(this, 1000L)
                }
            }
        }
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            sensorMgr.init()
            networkCollector.init()
            batteryCollector.init()
            updatePrecisionCache()
        }
    }

    fun start() {
        if (!isInitialized.get()) init()
        h.removeCallbacks(dr)
        ca.set(true)
        h.post(dr)
    }

    fun stop() {
        ca.set(false)
        h.removeCallbacks(dr)
        deltaManager.get()?.stop()
    }

    fun cleanup() {
        stop()
        sensorMgr.cleanup()
        batteryCollector.cleanup()
        collectionScope.cancel()
        isInitialized.set(false)
    }

    fun setDeltaManager(manager: DeltaManager) {
        deltaManager.set(manager)
        manager.start(deviceId)
    }

    private fun updatePrecisionCache() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPrecisionUpdate > PRECISION_CACHE_TTL) {
            precisionCache.clear()
            precisionCache["battery"] = sharedPrefs.getInt("batteryPrecision", -1)
            precisionCache["gps"] = sharedPrefs.getInt("gpsPrecision", -1)
            precisionCache["speed"] = sharedPrefs.getInt("speedPrecision", -1)
            precisionCache["altitude"] = sharedPrefs.getInt("gpsAltitudePrecision", -1)
            lastPrecisionUpdate = currentTime
        }
    }

    private fun collectData() {
        if (!ca.get() || !isInitialized.get()) return
        reusableDataMap.clear()

        try {
            reusableDataMap["id"] = deviceId
            reusableDataMap["n"] = deviceModel

            updatePrecisionCache()
            batteryCollector.collect(reusableDataMap, precisionCache["battery"] ?: -1)
            locationCollector.collect(
                reusableDataMap,
                precisionCache["gps"] ?: -1,
                precisionCache["speed"] ?: -1,
                precisionCache["altitude"] ?: -1
            )
            networkCollector.collectNetworkData(reusableDataMap, sharedPrefs)
            networkCollector.collectWifiData(reusableDataMap)
            networkCollector.collectMobileNetworkData(reusableDataMap)

            convertToStringMapOptimized(reusableDataMap, reusableStringMap)
            val json = gson.toJson(reusableStringMap)
            processCollectedData(json)
        } catch (e: Exception) { /* Error in data collection, skip this cycle */ }
    }

    private fun processCollectedData(json: String) {
        collectionScope.launch {
            try {
                deltaManager.get()?.processTelemetryData(json)
            } finally {
                h.post {
                    if (ca.get()) callback(json)
                }
            }
        }
    }

    private fun convertToStringMapOptimized(data: Map<String, Any>, target: MutableMap<String, String>) {
        target.clear()
        data.forEach { (key, value) ->
            target[key] = value.toString()
        }
    }
}