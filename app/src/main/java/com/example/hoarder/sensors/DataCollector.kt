package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.provider.Settings
import com.example.hoarder.collection.source.BatteryCollector
import com.example.hoarder.collection.source.CellularCollector
import com.example.hoarder.collection.source.LocationCollector
import com.example.hoarder.collection.source.WifiCollector
import com.example.hoarder.data.DataUploader
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.power.PowerManager
import com.example.hoarder.sensors.collectors.BatteryDataSubCollector
import com.example.hoarder.sensors.collectors.LocationDataSubCollector
import com.example.hoarder.sensors.collectors.NetworkSpeedCollector
import com.example.hoarder.sensors.lifecycle.CollectionLifecycleManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DataCollector(
    private val ctx: Context,
    private val h: Handler,
    private val powerManager: PowerManager,
    private val callback: (String) -> Unit
) {
    private val isInitialized = AtomicBoolean(false)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val deviceId: String by lazy { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).take(4) }
    private val deviceModel: String = Build.MODEL
    private val sharedPrefs: SharedPreferences by lazy { ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE) }

    private val reusableDataMap = mutableMapOf<String, Any>()
    private val lastDataSnapshot = mutableMapOf<String, Any>()
    private val precisionCache = mutableMapOf<String, Int>()
    private var lastPrecisionUpdate = 0L

    private val dataUploader = AtomicReference<DataUploader?>(null)
    private val collectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val sensorMgr = SensorManager(ctx, h, powerManager)
    private val batteryCollector = BatteryCollector(ctx)
    private val locationCollector = LocationCollector(sensorMgr)
    private val wifiCollector = WifiCollector(ctx)
    private val cellularCollector = CellularCollector(ctx)
    private val networkSpeedCollector = NetworkSpeedCollector(ctx, sharedPrefs)

    private val batterySubCollector by lazy { BatteryDataSubCollector(batteryCollector, precisionCache, lastDataSnapshot) }
    private val locationSubCollector by lazy { LocationDataSubCollector(sensorMgr, locationCollector, precisionCache, lastDataSnapshot) }

    private var identicalReadingsCount = 0
    private var lastHeartbeatTime = 0L
    private val HEARTBEAT_INTERVAL_MS = 10 * 60 * 1000L

    private val lifecycleManager = CollectionLifecycleManager(powerManager, collectionScope) { isMoving ->
        if (shouldCollectData(isMoving)) {
            collectDataOnce(isMoving)
        }
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            sensorMgr.init()
            batteryCollector.init()
            wifiCollector.init()
            cellularCollector.init()
            updatePrecisionCache()
        }
    }

    fun start() {
        if (!isInitialized.get()) init()
        lifecycleManager.start()
    }

    fun stop() = lifecycleManager.stop()

    fun cleanup() {
        stop()
        sensorMgr.cleanup()
        batteryCollector.cleanup()
        cellularCollector.cleanup()
        networkSpeedCollector.cleanup()
        collectionScope.cancel()
        isInitialized.set(false)
    }

    fun setDataUploader(uploader: DataUploader) = dataUploader.set(uploader)

    private fun updatePrecisionCache() {
        if (System.currentTimeMillis() - lastPrecisionUpdate > 10000L) {
            precisionCache.clear()
            precisionCache["battery"] = sharedPrefs.getInt(Prefs.KEY_BATTERY_PRECISION, -1)
            precisionCache["gps"] = sharedPrefs.getInt(Prefs.KEY_GPS_PRECISION, -1)
            precisionCache["speed"] = sharedPrefs.getInt(Prefs.KEY_SPEED_PRECISION, -1)
            precisionCache["altitude"] = sharedPrefs.getInt(Prefs.KEY_GPS_ALTITUDE_PRECISION, -1)
            precisionCache["rssi"] = sharedPrefs.getInt(Prefs.KEY_RSSI_PRECISION, -1)
            lastPrecisionUpdate = System.currentTimeMillis()
        }
    }

    private fun shouldCollectData(isMoving: Boolean): Boolean {
        if (sharedPrefs.getInt(Prefs.KEY_POWER_SAVING_MODE, Prefs.POWER_MODE_CONTINUOUS) == Prefs.POWER_MODE_CONTINUOUS) return true
        if (!isMoving && identicalReadingsCount >= 2) {
            if (System.currentTimeMillis() - lastHeartbeatTime > HEARTBEAT_INTERVAL_MS) {
                lastHeartbeatTime = System.currentTimeMillis()
                return true
            }
            return false
        }
        return true
    }

    private fun collectDataOnce(isMoving: Boolean = true) {
        if (!lifecycleManager.isActive() || !isInitialized.get()) return
        reusableDataMap.clear()
        reusableDataMap["i"] = deviceId
        reusableDataMap["n"] = deviceModel
        updatePrecisionCache()
        val powerMode = sharedPrefs.getInt(Prefs.KEY_POWER_SAVING_MODE, Prefs.POWER_MODE_CONTINUOUS)
        if (powerMode == Prefs.POWER_MODE_CONTINUOUS) {
            batterySubCollector.collectDirect(reusableDataMap)
            locationSubCollector.collectDirect(reusableDataMap)
        } else {
            batterySubCollector.collectIntelligent(reusableDataMap)
            locationSubCollector.collectIntelligent(reusableDataMap, isMoving)
        }
        wifiCollector.collect(reusableDataMap)
        cellularCollector.collect(reusableDataMap, precisionCache["rssi"] ?: -1)
        networkSpeedCollector.collect(reusableDataMap, isMoving)
        if (hasDataChanged() || powerMode == Prefs.POWER_MODE_CONTINUOUS) {
            processCollectedData(gson.toJson(reusableDataMap))
            updateLastDataSnapshot()
            identicalReadingsCount = 0
        } else {
            identicalReadingsCount++
        }
        lastHeartbeatTime = System.currentTimeMillis()
    }

    private fun hasDataChanged(): Boolean {
        if (lastDataSnapshot.isEmpty()) return true
        if (reusableDataMap.keys.size != lastDataSnapshot.keys.size) return true
        return reusableDataMap.any { (k, v) -> k !in listOf("i", "n") && v != lastDataSnapshot[k] }
    }

    private fun updateLastDataSnapshot() {
        lastDataSnapshot.clear()
        lastDataSnapshot.putAll(reusableDataMap)
    }

    private fun processCollectedData(json: String) {
        dataUploader.get()?.queueData(json)
        h.post { if (lifecycleManager.isActive()) callback(json) }
    }
}