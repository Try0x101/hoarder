package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.hoarder.collection.source.CellularCollector
import com.example.hoarder.collection.source.WifiCollector
import com.example.hoarder.common.math.RoundingUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NetworkDataCollector(private val ctx: Context) {
    private lateinit var cm: ConnectivityManager
    private val wifiCollector = WifiCollector(ctx)
    private val cellularCollector = CellularCollector(ctx)
    private val isInitialized = AtomicBoolean(false)
    private val networkCapabilitiesCache = AtomicReference<NetworkCapabilities?>()

    private val cellularDataCache = AtomicReference<Map<String, Any>?>(null)
    private val wifiDataCache = AtomicReference<Map<String, Any>?>(null)
    private val networkSpeedCache = AtomicReference<Pair<Any, Any>?>(null)

    private var lastCellularUpdate = 0L
    private var lastWifiUpdate = 0L
    private var lastNetworkSpeedUpdate = 0L

    companion object {
        private const val TAG = "NetworkDataCollector"
        private const val OPTIMIZED_CELLULAR_INTERVAL = 30000L
        private const val OPTIMIZED_WIFI_INTERVAL = 60000L
        private const val OPTIMIZED_SPEED_INTERVAL = 45000L
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            networkCapabilitiesCache.set(networkCapabilities)
            invalidateNetworkCache()
        }
        override fun onLost(network: Network) {
            super.onLost(network)
            networkCapabilitiesCache.set(null)
            invalidateNetworkCache()
        }
    }

    private fun invalidateNetworkCache() {
        networkSpeedCache.set(null)
        lastNetworkSpeedUpdate = 0L
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.registerDefaultNetworkCallback(networkCallback)
                wifiCollector.init()
                cellularCollector.init()
                Log.d(TAG, "NetworkDataCollector initialized")
            } catch (e: Exception) {
                isInitialized.set(false)
                throw RuntimeException("Failed to initialize network collectors", e)
            }
        }
    }

    fun cleanup() {
        if (isInitialized.get()) {
            try {
                cm.unregisterNetworkCallback(networkCallback)
                cellularCollector.cleanup()
            } catch (e: Exception) { }
        }
    }

    private fun getCurrentPowerMode(): String {
        return ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            .getString("powerMode", "continuous") ?: "continuous"
    }

    fun collectWifiData(dm: MutableMap<String, Any>, isMoving: Boolean = true) {
        val powerMode = getCurrentPowerMode()
        Log.d(TAG, "collectWifiData - powerMode: $powerMode")

        if (powerMode == "continuous") {
            Log.d(TAG, "Continuous mode - collecting fresh WiFi data")
            wifiCollector.collect(dm)
        } else {
            val currentTime = System.currentTimeMillis()
            val shouldUpdate = isMoving || (currentTime - lastWifiUpdate) > OPTIMIZED_WIFI_INTERVAL

            if (shouldUpdate) {
                Log.d(TAG, "Optimized mode - cache expired, collecting fresh WiFi data")
                val tempMap = mutableMapOf<String, Any>()
                wifiCollector.collect(tempMap)
                wifiDataCache.set(tempMap)
                lastWifiUpdate = currentTime
            } else {
                Log.d(TAG, "Optimized mode - using cached WiFi data")
            }

            wifiDataCache.get()?.let { cachedData ->
                dm.putAll(cachedData)
            }
        }
    }

    fun collectMobileNetworkData(dm: MutableMap<String, Any>, isMoving: Boolean = true) {
        val powerMode = getCurrentPowerMode()
        Log.d(TAG, "collectMobileNetworkData - powerMode: $powerMode")

        if (powerMode == "continuous") {
            Log.d(TAG, "Continuous mode - collecting fresh cellular data")
            val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            val rp = sp.getInt("rssiPrecision", -1)
            cellularCollector.collect(dm, rp)
            Log.d(TAG, "Fresh cellular data collected: $dm")
        } else {
            val currentTime = System.currentTimeMillis()
            val shouldUpdate = isMoving || (currentTime - lastCellularUpdate) > OPTIMIZED_CELLULAR_INTERVAL

            if (shouldUpdate) {
                Log.d(TAG, "Optimized mode - cache expired, collecting fresh cellular data")
                val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
                val rp = sp.getInt("rssiPrecision", -1)
                val tempMap = mutableMapOf<String, Any>()
                cellularCollector.collect(tempMap, rp)
                cellularDataCache.set(tempMap)
                lastCellularUpdate = currentTime
            } else {
                Log.d(TAG, "Optimized mode - using cached cellular data")
            }

            cellularDataCache.get()?.let { cachedData ->
                dm.putAll(cachedData)
            }
        }
    }

    fun collectNetworkData(dm: MutableMap<String, Any>, sp: SharedPreferences, isMoving: Boolean = true) {
        if (!isInitialized.get()) {
            return
        }

        val powerMode = getCurrentPowerMode()
        Log.d(TAG, "collectNetworkData - powerMode: $powerMode")

        if (powerMode == "continuous") {
            Log.d(TAG, "Continuous mode - collecting fresh network speed data")
            try {
                var nc = cm.getNetworkCapabilities(cm.activeNetwork)
                if (nc != null) {
                    val np = sp.getInt("networkPrecision", 0)
                    val downstreamKbps = nc.linkDownstreamBandwidthKbps
                    val upstreamKbps = nc.linkUpstreamBandwidthKbps

                    if (downstreamKbps > 0 && upstreamKbps > 0) {
                        val downSpeed = RoundingUtils.rn(downstreamKbps, np)
                        val upSpeed = RoundingUtils.rn(upstreamKbps, np)

                        if (downSpeed is Number && upSpeed is Number &&
                            downSpeed.toDouble() > 0 && upSpeed.toDouble() > 0) {
                            dm["d"] = downSpeed
                            dm["u"] = upSpeed
                            Log.d(TAG, "Fresh network speeds: d=$downSpeed, u=$upSpeed")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting fresh network data", e)
            }
        } else {
            val currentTime = System.currentTimeMillis()
            val shouldUpdate = isMoving || (currentTime - lastNetworkSpeedUpdate) > OPTIMIZED_SPEED_INTERVAL

            if (shouldUpdate) {
                Log.d(TAG, "Optimized mode - cache expired, collecting fresh network speed data")
                try {
                    var nc = networkCapabilitiesCache.get()
                    if (nc == null) {
                        nc = cm.getNetworkCapabilities(cm.activeNetwork)
                        networkCapabilitiesCache.set(nc)
                    }

                    if (nc != null) {
                        val np = sp.getInt("networkPrecision", 0)
                        val downstreamKbps = nc.linkDownstreamBandwidthKbps
                        val upstreamKbps = nc.linkUpstreamBandwidthKbps

                        if (downstreamKbps > 0 && upstreamKbps > 0) {
                            val downSpeed = RoundingUtils.rn(downstreamKbps, np)
                            val upSpeed = RoundingUtils.rn(upstreamKbps, np)

                            val speeds = if (downSpeed is Number && upSpeed is Number &&
                                downSpeed.toDouble() > 0 && upSpeed.toDouble() > 0) {
                                Pair(downSpeed, upSpeed)
                            } else {
                                null
                            }

                            networkSpeedCache.set(speeds)
                            lastNetworkSpeedUpdate = currentTime
                        } else {
                            networkSpeedCache.set(null)
                            lastNetworkSpeedUpdate = currentTime
                        }
                    } else {
                        networkSpeedCache.set(null)
                        lastNetworkSpeedUpdate = currentTime
                    }
                } catch (e: Exception) {
                    networkSpeedCache.set(null)
                    lastNetworkSpeedUpdate = currentTime
                }
            } else {
                Log.d(TAG, "Optimized mode - using cached network speed data")
            }

            networkSpeedCache.get()?.let { (downSpeed, upSpeed) ->
                dm["d"] = downSpeed
                dm["u"] = upSpeed
            }
        }
    }

    fun forceClearCache() {
        cellularDataCache.set(null)
        wifiDataCache.set(null)
        networkSpeedCache.set(null)
        lastCellularUpdate = 0L
        lastWifiUpdate = 0L
        lastNetworkSpeedUpdate = 0L
    }
}