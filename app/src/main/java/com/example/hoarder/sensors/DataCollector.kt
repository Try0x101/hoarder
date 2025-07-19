package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import com.example.hoarder.collection.source.BatteryCollector
import com.example.hoarder.collection.source.LocationCollector
import com.example.hoarder.data.DataUploader
import com.example.hoarder.power.PowerManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DataCollector(
    private val ctx: Context,
    private val h: Handler,
    private val powerManager: PowerManager,
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
    private val lastDataSnapshot = mutableMapOf<String, Any>()

    private val precisionCache = mutableMapOf<String, Int>()
    private var lastPrecisionUpdate = 0L
    private var dataChangeCount = 0
    private var lastDataChangeTime = 0L

    companion object {
        private const val TAG = "DataCollector"
        private const val BASE_PRECISION_CACHE_TTL = 10000L
        private const val MIN_PRECISION_CACHE_TTL = 5000L
        private const val MAX_PRECISION_CACHE_TTL = 60000L
        private const val DATA_CHANGE_WINDOW_MS = 30000L
    }

    private val dataUploader = AtomicReference<DataUploader?>(null)
    private val collectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var collectionJob: Job? = null

    private val sensorMgr = SensorManager(ctx, h, powerManager)
    private val networkCollector = NetworkDataCollector(ctx)
    private val batteryCollector = BatteryCollector(ctx)
    private val locationCollector = LocationCollector(sensorMgr)

    private var lastCollectionTime = 0L
    private var identicalReadingsCount = 0
    private var lastBatteryLevel = -1
    private var lastCellId = ""
    private var lastBssid = ""

    fun init() {
        Log.d(TAG, "=== INITIALIZING DataCollector ===")
        if (isInitialized.compareAndSet(false, true)) {
            sensorMgr.init()
            networkCollector.init()
            batteryCollector.init()
            updatePrecisionCache()
            observePowerStateChanges()
            Log.d(TAG, "DataCollector initialized successfully")
        }
    }

    private fun observePowerStateChanges() {
        Log.d(TAG, "Setting up power state observation")
        collectionScope.launch {
            powerManager.powerState.collect { state ->
                Log.d(TAG, "Power state changed: isMoving=${state.isMoving}")
                if (ca.get()) {
                    collectionJob?.cancel()
                    val interval = powerManager.getCollectionInterval()
                    Log.d(TAG, "Restarting collection with interval: ${interval}ms")
                    startCollectionWithInterval(interval, state.isMoving)
                }
            }
        }
    }

    private fun startCollectionWithInterval(interval: Long, isMoving: Boolean) {
        Log.d(TAG, "Starting collection loop - interval: ${interval}ms, isMoving: $isMoving")
        collectionJob = collectionScope.launch {
            while (ca.get()) {
                if (shouldCollectData(isMoving)) {
                    Log.d(TAG, "--- COLLECTING DATA CYCLE START ---")
                    collectDataOnce(isMoving)
                    Log.d(TAG, "--- COLLECTING DATA CYCLE END ---")
                } else {
                    Log.d(TAG, "Skipping data collection - shouldCollectData returned false")
                }
                delay(interval)
            }
        }
    }

    private fun shouldCollectData(isMoving: Boolean): Boolean {
        val currentTime = System.currentTimeMillis()
        val powerMode = getCurrentPowerMode()

        Log.d(TAG, "shouldCollectData check: powerMode=$powerMode, isMoving=$isMoving, identicalReadings=$identicalReadingsCount")

        if (powerMode == "continuous") {
            Log.d(TAG, "Continuous mode - always collect")
            return true
        }

        if (!isMoving && identicalReadingsCount >= 2) {
            val timeSinceLastCollection = currentTime - lastCollectionTime
            val shouldCollect = timeSinceLastCollection > 120000L
            Log.d(TAG, "Stationary with ${identicalReadingsCount} identical readings - shouldCollect: $shouldCollect (timeSince: ${timeSinceLastCollection}ms)")
            return shouldCollect
        }

        Log.d(TAG, "Normal collection conditions met")
        return true
    }

    private fun getCurrentPowerMode(): String {
        val mode = sharedPrefs.getString("powerMode", "continuous") ?: "continuous"
        Log.d(TAG, "Current power mode: $mode")
        return mode
    }

    fun start() {
        Log.d(TAG, "=== STARTING DataCollector ===")
        if (!isInitialized.get()) init()
        if (ca.compareAndSet(false, true)) {
            val state = powerManager.powerState.value
            val interval = powerManager.getCollectionInterval()
            Log.d(TAG, "Starting with state: isMoving=${state.isMoving}, interval=${interval}ms")
            startCollectionWithInterval(interval, state.isMoving)
        }
    }

    fun stop() {
        Log.d(TAG, "=== STOPPING DataCollector ===")
        if (ca.compareAndSet(true, false)) {
            collectionJob?.cancel()
        }
    }

    fun cleanup() {
        Log.d(TAG, "=== CLEANING UP DataCollector ===")
        stop()
        sensorMgr.cleanup()
        batteryCollector.cleanup()
        networkCollector.cleanup()
        collectionScope.cancel()
        isInitialized.set(false)
    }

    fun setDataUploader(uploader: DataUploader) {
        Log.d(TAG, "DataUploader set")
        dataUploader.set(uploader)
    }

    private fun updatePrecisionCache() {
        val currentTime = System.currentTimeMillis()
        val adaptiveTTL = calculateAdaptiveCacheTTL()

        if (currentTime - lastPrecisionUpdate > adaptiveTTL) {
            precisionCache.clear()
            precisionCache["battery"] = sharedPrefs.getInt("batteryPrecision", -1)
            precisionCache["gps"] = sharedPrefs.getInt("gpsPrecision", -1)
            precisionCache["speed"] = sharedPrefs.getInt("speedPrecision", -1)
            precisionCache["altitude"] = sharedPrefs.getInt("gpsAltitudePrecision", -1)
            lastPrecisionUpdate = currentTime
            Log.d(TAG, "Precision cache updated: $precisionCache")
        }
    }

    private fun calculateAdaptiveCacheTTL(): Long {
        val currentTime = System.currentTimeMillis()

        val recentChanges = if (currentTime - lastDataChangeTime < DATA_CHANGE_WINDOW_MS) {
            dataChangeCount
        } else {
            0
        }

        return when {
            recentChanges >= 10 -> MIN_PRECISION_CACHE_TTL
            recentChanges >= 5 -> BASE_PRECISION_CACHE_TTL
            recentChanges >= 2 -> BASE_PRECISION_CACHE_TTL * 2
            else -> MAX_PRECISION_CACHE_TTL
        }.coerceIn(MIN_PRECISION_CACHE_TTL, MAX_PRECISION_CACHE_TTL)
    }

    private fun trackDataChange() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastDataChangeTime > DATA_CHANGE_WINDOW_MS) {
            dataChangeCount = 1
            lastDataChangeTime = currentTime
        } else {
            dataChangeCount++
        }
        Log.d(TAG, "Data change tracked - count: $dataChangeCount")
    }

    fun collectDataOnce(isMoving: Boolean = true) {
        if (!ca.get() || !isInitialized.get()) {
            Log.w(TAG, "Collection skipped - ca: ${ca.get()}, initialized: ${isInitialized.get()}")
            return
        }

        val currentTime = System.currentTimeMillis()
        val powerMode = getCurrentPowerMode()
        reusableDataMap.clear()

        Log.d(TAG, "collectDataOnce START - powerMode: $powerMode, isMoving: $isMoving")

        try {
            reusableDataMap["i"] = deviceId
            reusableDataMap["n"] = deviceModel
            Log.d(TAG, "Added device info: i=$deviceId, n=$deviceModel")

            updatePrecisionCache()

            Log.d(TAG, "=== COLLECTING BATTERY DATA ===")
            if (powerMode == "continuous") {
                collectBatteryDataDirect()
            } else {
                collectBatteryDataIntelligent()
            }

            Log.d(TAG, "=== COLLECTING LOCATION DATA ===")
            if (powerMode == "continuous") {
                collectLocationDataDirect(isMoving)
            } else {
                collectLocationDataIntelligent(isMoving)
            }

            Log.d(TAG, "=== COLLECTING NETWORK DATA ===")
            if (powerMode == "continuous") {
                collectNetworkDataDirect(isMoving)
            } else {
                collectNetworkDataIntelligent(isMoving)
            }

            Log.d(TAG, "Raw collected data: $reusableDataMap")

            if (shouldProcessData(powerMode)) {
                Log.d(TAG, "Data will be processed and sent")
                trackDataChange()
                val json = gson.toJson(reusableDataMap)
                Log.d(TAG, "Generated JSON: $json")
                processCollectedData(json)
                updateLastDataSnapshot()
                identicalReadingsCount = 0
            } else {
                identicalReadingsCount++
                Log.d(TAG, "Data unchanged - identical readings count: $identicalReadingsCount")
            }

            lastCollectionTime = currentTime
            Log.d(TAG, "collectDataOnce COMPLETE")
        } catch (e: Exception) {
            Log.e(TAG, "Error in collectDataOnce", e)
        }
    }

    private fun shouldProcessData(powerMode: String): Boolean {
        val result = when (powerMode) {
            "continuous" -> true
            else -> hasDataChanged()
        }
        Log.d(TAG, "shouldProcessData: $result (mode: $powerMode)")
        return result
    }

    private fun collectBatteryDataDirect() {
        Log.d(TAG, "collectBatteryDataDirect - always fresh")
        val tempMap = mutableMapOf<String, Any>()
        batteryCollector.collect(tempMap, precisionCache["battery"] ?: -1)
        Log.d(TAG, "Battery data collected: $tempMap")
        reusableDataMap.putAll(tempMap)
    }

    private fun collectBatteryDataIntelligent() {
        Log.d(TAG, "collectBatteryDataIntelligent - with caching")
        val tempMap = mutableMapOf<String, Any>()
        batteryCollector.collect(tempMap, precisionCache["battery"] ?: -1)
        Log.d(TAG, "Battery data from collector: $tempMap")

        val currentBatteryLevel = tempMap["p"] as? Int ?: -1
        Log.d(TAG, "Battery level comparison - current: $currentBatteryLevel, last: $lastBatteryLevel")

        if (currentBatteryLevel != lastBatteryLevel || lastBatteryLevel == -1) {
            Log.d(TAG, "Battery level changed - using new data")
            reusableDataMap.putAll(tempMap)
            lastBatteryLevel = currentBatteryLevel
        } else if (lastDataSnapshot.containsKey("p")) {
            Log.d(TAG, "Battery level unchanged - using cached data")
            reusableDataMap["p"] = lastDataSnapshot["p"]!!
            tempMap["c"]?.let { reusableDataMap["c"] = it }
        }
    }

    private fun collectLocationDataDirect(isMoving: Boolean) {
        Log.d(TAG, "collectLocationDataDirect - always fresh")
        val location = sensorMgr.getLocation()
        Log.d(TAG, "Location from sensor manager: $location")
        if (location != null) {
            locationCollector.collect(
                reusableDataMap,
                precisionCache["gps"] ?: -1,
                precisionCache["speed"] ?: -1,
                precisionCache["altitude"] ?: -1
            )
            Log.d(TAG, "Location data added to map")
        } else {
            Log.d(TAG, "No location available")
        }
    }

    private fun collectLocationDataIntelligent(isMoving: Boolean) {
        Log.d(TAG, "collectLocationDataIntelligent - with movement check")
        val location = sensorMgr.getLocation()
        Log.d(TAG, "Location from sensor manager: $location")

        if (location != null && (isMoving || shouldUpdateLocation())) {
            Log.d(TAG, "Location conditions met - collecting fresh location")
            locationCollector.collect(
                reusableDataMap,
                precisionCache["gps"] ?: -1,
                precisionCache["speed"] ?: -1,
                precisionCache["altitude"] ?: -1
            )
        } else if (lastDataSnapshot.containsKey("y")) {
            Log.d(TAG, "Using cached location data")
            listOf("y", "x", "a", "ac", "s").forEach { key ->
                lastDataSnapshot[key]?.let { reusableDataMap[key] = it }
            }
        } else {
            Log.d(TAG, "No location data available")
        }
    }

    private fun collectNetworkDataDirect(isMoving: Boolean) {
        Log.d(TAG, "collectNetworkDataDirect - always fresh")
        collectWifiDataDirect(isMoving)
        collectMobileNetworkDataDirect(isMoving)
        networkCollector.collectNetworkData(reusableDataMap, sharedPrefs, isMoving)
        Log.d(TAG, "Network data collection complete")
    }

    private fun collectNetworkDataIntelligent(isMoving: Boolean) {
        Log.d(TAG, "collectNetworkDataIntelligent - with caching")
        collectWifiDataIntelligent(isMoving)
        collectMobileNetworkDataIntelligent(isMoving)
        networkCollector.collectNetworkData(reusableDataMap, sharedPrefs, isMoving)
        Log.d(TAG, "Intelligent network data collection complete")
    }

    private fun collectWifiDataDirect(isMoving: Boolean) {
        Log.d(TAG, "collectWifiDataDirect")
        val tempMap = mutableMapOf<String, Any>()
        networkCollector.collectWifiData(tempMap, isMoving)
        Log.d(TAG, "WiFi data: $tempMap")
        reusableDataMap.putAll(tempMap)
    }

    private fun collectWifiDataIntelligent(isMoving: Boolean) {
        Log.d(TAG, "collectWifiDataIntelligent")
        val tempMap = mutableMapOf<String, Any>()
        networkCollector.collectWifiData(tempMap, isMoving)
        Log.d(TAG, "WiFi data from collector: $tempMap")

        val currentBssid = tempMap["b"]?.toString() ?: "0"
        Log.d(TAG, "BSSID comparison - current: $currentBssid, last: $lastBssid")

        if (currentBssid != lastBssid) {
            Log.d(TAG, "BSSID changed - using new data")
            reusableDataMap["b"] = currentBssid
            lastBssid = currentBssid
        } else if (lastDataSnapshot.containsKey("b")) {
            Log.d(TAG, "BSSID unchanged - using cached data")
            reusableDataMap["b"] = lastDataSnapshot["b"]!!
        }
    }

    private fun collectMobileNetworkDataDirect(isMoving: Boolean) {
        Log.d(TAG, "collectMobileNetworkDataDirect")
        val tempMap = mutableMapOf<String, Any>()
        networkCollector.collectMobileNetworkData(tempMap, isMoving)
        Log.d(TAG, "Mobile network data: $tempMap")
        reusableDataMap.putAll(tempMap)
    }

    private fun collectMobileNetworkDataIntelligent(isMoving: Boolean) {
        Log.d(TAG, "collectMobileNetworkDataIntelligent")
        val tempMap = mutableMapOf<String, Any>()
        networkCollector.collectMobileNetworkData(tempMap, isMoving)
        Log.d(TAG, "Mobile network data from collector: $tempMap")

        val currentCellId = tempMap["ci"]?.toString() ?: ""
        Log.d(TAG, "Cell ID comparison - current: '$currentCellId', last: '$lastCellId'")

        if (currentCellId != lastCellId) {
            Log.d(TAG, "Cell ID changed - using new data")
            reusableDataMap.putAll(tempMap)
            lastCellId = currentCellId
        } else if (lastDataSnapshot.containsKey("ci")) {
            Log.d(TAG, "Cell ID unchanged - using cached cellular data")
            listOf("o", "t", "ci", "tc", "mc", "mn", "r").forEach { key ->
                lastDataSnapshot[key]?.let {
                    reusableDataMap[key] = it
                    Log.d(TAG, "Restored cached $key = $it")
                }
            }
        }
    }

    private fun shouldUpdateLocation(): Boolean {
        val location = sensorMgr.getLocation() ?: return false
        val lastLat = lastDataSnapshot["y"] as? Double ?: return true
        val lastLon = lastDataSnapshot["x"] as? Double ?: return true

        val distance = calculateDistance(lastLat, lastLon, location.latitude, location.longitude)
        Log.d(TAG, "Location distance check: ${distance}m (threshold: 10m)")
        return distance > 10.0
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c * 1000
    }

    private fun hasDataChanged(): Boolean {
        if (lastDataSnapshot.isEmpty()) {
            Log.d(TAG, "hasDataChanged: true (no previous snapshot)")
            return true
        }

        val currentKeys = reusableDataMap.keys
        val lastKeys = lastDataSnapshot.keys

        if (currentKeys.size != lastKeys.size || (currentKeys - lastKeys).any { it !in listOf("i", "n") }) {
            Log.d(TAG, "hasDataChanged: true (key mismatch)")
            Log.d(TAG, "Current keys: $currentKeys")
            Log.d(TAG, "Last keys: $lastKeys")
            return true
        }

        for (key in currentKeys) {
            if (key == "i" || key == "n") continue
            if (reusableDataMap[key] != lastDataSnapshot[key]) {
                Log.d(TAG, "hasDataChanged: true (value changed for key '$key')")
                Log.d(TAG, "Current $key: ${reusableDataMap[key]}")
                Log.d(TAG, "Last $key: ${lastDataSnapshot[key]}")
                return true
            }
        }
        Log.d(TAG, "hasDataChanged: false (no changes detected)")
        return false
    }

    private fun updateLastDataSnapshot() {
        lastDataSnapshot.clear()
        lastDataSnapshot.putAll(reusableDataMap)
        Log.d(TAG, "Data snapshot updated: $lastDataSnapshot")
    }

    private fun processCollectedData(json: String) {
        Log.d(TAG, "Processing collected data and sending to callback")
        dataUploader.get()?.queueData(json)
        h.post {
            if (ca.get()) {
                Log.d(TAG, "Calling UI callback with data")
                callback(json)
            }
        }
    }
}