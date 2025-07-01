package com.example.hoarder.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.provider.Settings
import com.example.hoarder.data.DataUtils
import com.example.hoarder.data.RealTimeDeltaManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class DataCollector(
    private val ctx: Context,
    private val h: Handler,
    private val callback: (String) -> Unit
) {
    private lateinit var bm: BatteryManager
    private val bd = AtomicReference<Map<String, Any>?>(null)
    private val ca = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private var receiverRegistered = AtomicBoolean(false)

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val deviceId: String by lazy {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).take(4)
    }
    private val deviceModel: String = Build.MODEL
    private val sharedPrefs: SharedPreferences by lazy {
        ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
    }

    private val stringBuilder = StringBuilder(512)
    private val reusableDataMap = mutableMapOf<String, Any>()
    private val reusableStringMap = mutableMapOf<String, String>()

    private val precisionCache = mutableMapOf<String, Int>()
    private var lastPrecisionUpdate = 0L
    private val PRECISION_CACHE_TTL = 10000L

    private val deltaManager = AtomicReference<RealTimeDeltaManager?>(null)
    private val collectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val dr = object : Runnable {
        override fun run() {
            if (ca.get()) {
                try {
                    collectData()
                } catch (e: Exception) {
                    // Log error but continue collection
                }
                if (ca.get()) {
                    h.postDelayed(this, 1000L)
                }
            }
        }
    }

    private val sensorMgr = SensorManager(ctx, h)
    private val networkCollector = NetworkDataCollector(ctx)

    private val br = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_BATTERY_CHANGED) {
                try {
                    val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (l >= 0 && s > 0) {
                        val p = l * 100 / s.toFloat()
                        var c2: Int? = null

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try {
                                val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                                val cp = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                if (cc > 0 && cp > 0) {
                                    c2 = (cc / 1000 * 100) / cp
                                    c2 = (c2 / 100) * 100
                                }
                            } catch (e: Exception) {
                                // Battery properties not available
                            }
                        }

                        val batteryData = if (c2 != null) {
                            mapOf("perc" to p.toInt(), "cap" to c2)
                        } else {
                            mapOf("perc" to p.toInt())
                        }
                        bd.set(batteryData)
                    }
                } catch (e: Exception) {
                    // Error processing battery data
                }
            }
        }
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                sensorMgr.init()
                networkCollector.init()

                if (receiverRegistered.compareAndSet(false, true)) {
                    ctx.registerReceiver(br, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }

                updatePrecisionCache()
            } catch (e: Exception) {
                isInitialized.set(false)
                throw e
            }
        }
    }

    fun start() {
        if (!isInitialized.get()) {
            init()
        }
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
        try {
            if (receiverRegistered.compareAndSet(true, false)) {
                ctx.unregisterReceiver(br)
            }
            sensorMgr.cleanup()
            collectionScope.cancel()
            isInitialized.set(false)
        } catch (e: Exception) {
            // Already unregistered or other cleanup error
        }
    }

    fun setDeltaManager(manager: RealTimeDeltaManager) {
        deltaManager.set(manager)
        manager.start(deviceId)
    }

    private fun updatePrecisionCache() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPrecisionUpdate > PRECISION_CACHE_TTL) {
            precisionCache.clear()
            precisionCache["battery"] = sharedPrefs.getInt("batteryPrecision", 5)
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
            collectBatteryData(reusableDataMap)
            collectLocationData(reusableDataMap)
            networkCollector.collectNetworkData(reusableDataMap, sharedPrefs)
            networkCollector.collectWifiData(reusableDataMap)
            networkCollector.collectMobileNetworkData(reusableDataMap)

            convertToStringMapOptimized(reusableDataMap, reusableStringMap)
            val json = gson.toJson(reusableStringMap)

            processCollectedData(json)

        } catch (e: Exception) {
            // Error in data collection, skip this cycle
        }
    }

    private fun processCollectedData(json: String) {
        collectionScope.launch {
            try {
                val manager = deltaManager.get()
                if (manager != null) {
                    val deltaJson = manager.processTelemetryData(json)

                    h.post {
                        if (ca.get()) {
                            callback(json)
                        }
                    }
                } else {
                    h.post {
                        if (ca.get()) {
                            callback(json)
                        }
                    }
                }
            } catch (e: Exception) {
                h.post {
                    if (ca.get()) {
                        callback(json)
                    }
                }
            }
        }
    }

    private fun collectBatteryData(dm: MutableMap<String, Any>) {
        val bp = precisionCache["battery"] ?: 5
        bd.get()?.let { batteryData ->
            batteryData["perc"]?.let { v ->
                when (v) {
                    is Int -> {
                        dm["perc"] = if (bp == -1) DataUtils.smartBattery(v) else DataUtils.rb(v, bp)
                    }
                    else -> dm["perc"] = v
                }
            }
            batteryData["cap"]?.let { v -> dm["cap"] = v }
        }
    }

    private fun collectLocationData(dm: MutableMap<String, Any>) {
        try {
            sensorMgr.getLocation()?.let { location ->
                val gp = precisionCache["gps"] ?: -1
                val spdp = precisionCache["speed"] ?: -1
                val altP = precisionCache["altitude"] ?: -1

                dm["alt"] = sensorMgr.getFilteredAltitude(altP)

                val sk = (location.speed * 3.6).roundToInt()
                val rs = DataUtils.rsp(sk, spdp)
                val (prec, _) = if (gp == -1) DataUtils.smartGPSPrecision(location.speed) else Pair(gp, gp)

                val (rl, rlo, ac) = calculateLocationPrecisionOptimized(location, prec)
                dm["lat"] = rl
                dm["lon"] = rlo
                dm["acc"] = ac
                dm["spd"] = rs
            }
        } catch (e: Exception) {
            // Location data not available
        }
    }

    private fun calculateLocationPrecisionOptimized(location: android.location.Location, prec: Int): Triple<Double, Double, Int> {
        return try {
            when (prec) {
                0 -> {
                    Triple(
                        Math.round(location.latitude * 1000000.0) / 1000000.0,
                        Math.round(location.longitude * 1000000.0) / 1000000.0,
                        location.accuracy.roundToInt()
                    )
                }
                20 -> {
                    Triple(
                        (location.latitude * 10000).roundToInt() / 10000.0,
                        (location.longitude * 10000).roundToInt() / 10000.0,
                        Math.max(20, (location.accuracy / 20).roundToInt() * 20)
                    )
                }
                100 -> {
                    Triple(
                        (location.latitude * 1000).roundToInt() / 1000.0,
                        (location.longitude * 1000).roundToInt() / 1000.0,
                        Math.max(100, (location.accuracy / 100).roundToInt() * 100)
                    )
                }
                1000 -> {
                    Triple(
                        (location.latitude * 100).roundToInt() / 100.0,
                        (location.longitude * 100).roundToInt() / 100.0,
                        Math.max(1000, (location.accuracy / 1000).roundToInt() * 1000)
                    )
                }
                10000 -> {
                    Triple(
                        (location.latitude * 10).roundToInt() / 10.0,
                        (location.longitude * 10).roundToInt() / 10.0,
                        Math.max(10000, (location.accuracy / 10000).roundToInt() * 10000)
                    )
                }
                else -> {
                    Triple(
                        Math.round(location.latitude * 1000000.0) / 1000000.0,
                        Math.round(location.longitude * 1000000.0) / 1000000.0,
                        location.accuracy.roundToInt()
                    )
                }
            }
        } catch (e: Exception) {
            Triple(0.0, 0.0, 0)
        }
    }

    private fun convertToStringMapOptimized(data: Map<String, Any>, target: MutableMap<String, String>) {
        target.clear()
        data.forEach { (key, value) ->
            target[key] = when (value) {
                is String -> value
                is Int -> value.toString()
                is Long -> value.toString()
                is Float -> value.toString()
                is Double -> value.toString()
                else -> value.toString()
            }
        }
    }
}