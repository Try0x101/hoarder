package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.provider.Settings
import com.example.hoarder.collection.source.BatteryCollector
import com.example.hoarder.collection.source.LocationCollector
import com.example.hoarder.data.DataUploader
import com.example.hoarder.power.PowerManager
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
        if (isInitialized.compareAndSet(false, true)) {
            sensorMgr.init()
            networkCollector.init()
            batteryCollector.init()
            updatePrecisionCache()
            observePowerStateChanges()
        }
    }

    private fun observePowerStateChanges() {
        collectionScope.launch {
            powerManager.powerState.collect { state ->
                if (ca.get()) {
                    collectionJob?.cancel()
                    val interval = powerManager.getCollectionInterval()
                    startCollectionWithInterval(interval, state.isMoving)
                }
            }
        }
    }

    private fun startCollectionWithInterval(interval: Long, isMoving: Boolean) {
        collectionJob = collectionScope.launch {
            while (ca.get()) {
                if (shouldCollectData(isMoving)) {
                    collectDataOnce(isMoving)
                }
                delay(interval)
            }
        }
    }

    private fun shouldCollectData(isMoving: Boolean): Boolean {
        val currentTime = System.currentTimeMillis()

        if (!isMoving && identicalReadingsCount >= 2) {
            val timeSinceLastCollection = currentTime - lastCollectionTime
            return timeSinceLastCollection > 120000L
        }

        return true
    }

    fun start() {
        if (!isInitialized.get()) init()
        if (ca.compareAndSet(false, true)) {
            val state = powerManager.powerState.value
            val interval = powerManager.getCollectionInterval()
            startCollectionWithInterval(interval, state.isMoving)
        }
    }

    fun stop() {
        if (ca.compareAndSet(true, false)) {
            collectionJob?.cancel()
        }
    }

    fun cleanup() {
        stop()
        sensorMgr.cleanup()
        batteryCollector.cleanup()
        networkCollector.cleanup()
        collectionScope.cancel()
        isInitialized.set(false)
    }

    fun setDataUploader(uploader: DataUploader) {
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
    }

    fun collectDataOnce(isMoving: Boolean = true) {
        if (!ca.get() || !isInitialized.get()) return

        val currentTime = System.currentTimeMillis()
        reusableDataMap.clear()

        try {
            reusableDataMap["i"] = deviceId
            reusableDataMap["n"] = deviceModel

            updatePrecisionCache()

            collectBatteryDataIntelligent()
            collectLocationDataIntelligent(isMoving)
            collectNetworkDataIntelligent(isMoving)

            if (hasDataChanged()) {
                trackDataChange()
                val json = gson.toJson(reusableDataMap)
                processCollectedData(json)
                updateLastDataSnapshot()
                identicalReadingsCount = 0
            } else {
                identicalReadingsCount++
            }

            lastCollectionTime = currentTime
        } catch (e: Exception) { }
    }

    private fun collectBatteryDataIntelligent() {
        val tempMap = mutableMapOf<String, Any>()
        batteryCollector.collect(tempMap, precisionCache["battery"] ?: -1)

        val currentBatteryLevel = tempMap["p"] as? Int ?: -1
        if (currentBatteryLevel != lastBatteryLevel || lastBatteryLevel == -1) {
            reusableDataMap.putAll(tempMap)
            lastBatteryLevel = currentBatteryLevel
        } else if (lastDataSnapshot.containsKey("p")) {
            reusableDataMap["p"] = lastDataSnapshot["p"]!!
            tempMap["c"]?.let { reusableDataMap["c"] = it }
        }
    }

    private fun collectLocationDataIntelligent(isMoving: Boolean) {
        val location = sensorMgr.getLocation()

        if (location != null && (isMoving || shouldUpdateLocation())) {
            locationCollector.collect(
                reusableDataMap,
                precisionCache["gps"] ?: -1,
                precisionCache["speed"] ?: -1,
                precisionCache["altitude"] ?: -1
            )
        } else if (lastDataSnapshot.containsKey("y")) {
            listOf("y", "x", "a", "ac", "s").forEach { key ->
                lastDataSnapshot[key]?.let { reusableDataMap[key] = it }
            }
        }
    }

    private fun shouldUpdateLocation(): Boolean {
        val location = sensorMgr.getLocation() ?: return false
        val lastLat = lastDataSnapshot["y"] as? Double ?: return true
        val lastLon = lastDataSnapshot["x"] as? Double ?: return true

        val distance = FloatArray(1)
        android.location.Location.distanceBetween(
            lastLat, lastLon,
            location.latitude, location.longitude,
            distance
        )

        return distance[0] > 10f
    }

    private fun collectNetworkDataIntelligent(isMoving: Boolean) {
        networkCollector.collectNetworkData(reusableDataMap, sharedPrefs, isMoving)
        collectWifiDataIntelligent(isMoving)
        collectMobileNetworkDataIntelligent(isMoving)
    }

    private fun collectWifiDataIntelligent(isMoving: Boolean) {
        val tempMap = mutableMapOf<String, Any>()
        networkCollector.collectWifiData(tempMap, isMoving)

        val currentBssid = tempMap["b"] as? String ?: "0"
        if (currentBssid != lastBssid) {
            reusableDataMap["b"] = currentBssid
            lastBssid = currentBssid
        } else if (lastDataSnapshot.containsKey("b")) {
            reusableDataMap["b"] = lastDataSnapshot["b"]!!
        }
    }

    private fun collectMobileNetworkDataIntelligent(isMoving: Boolean) {
        val tempMap = mutableMapOf<String, Any>()
        networkCollector.collectMobileNetworkData(tempMap, isMoving)

        val currentCellId = tempMap["ci"]?.toString() ?: ""
        if (currentCellId != lastCellId) {
            reusableDataMap.putAll(tempMap)
            lastCellId = currentCellId
        } else if (lastDataSnapshot.containsKey("ci")) {
            listOf("o", "t", "ci", "tc", "mc", "mn", "r").forEach { key ->
                lastDataSnapshot[key]?.let { reusableDataMap[key] = it }
            }
        }
    }

    private fun hasDataChanged(): Boolean {
        if (lastDataSnapshot.isEmpty()) return true

        val currentKeys = reusableDataMap.keys
        val lastKeys = lastDataSnapshot.keys

        if (currentKeys.size != lastKeys.size || (currentKeys - lastKeys).any { it !in listOf("i", "n") }) {
            return true
        }

        for (key in currentKeys) {
            if (key == "i" || key == "n") continue
            if (reusableDataMap[key] != lastDataSnapshot[key]) {
                return true
            }
        }
        return false
    }

    private fun updateLastDataSnapshot() {
        lastDataSnapshot.clear()
        lastDataSnapshot.putAll(reusableDataMap)
    }

    private fun processCollectedData(json: String) {
        dataUploader.get()?.queueData(json)
        h.post {
            if (ca.get()) callback(json)
        }
    }
}