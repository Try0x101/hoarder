package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.registerDefaultNetworkCallback(networkCallback)
                wifiCollector.init()
                cellularCollector.init()
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

    fun collectWifiData(dm: MutableMap<String, Any>, isMoving: Boolean = true) {
        val currentTime = System.currentTimeMillis()
        val shouldUpdate = isMoving || (currentTime - lastWifiUpdate) > 60000L

        if (shouldUpdate) {
            val tempMap = mutableMapOf<String, Any>()
            wifiCollector.collect(tempMap)
            wifiDataCache.set(tempMap)
            lastWifiUpdate = currentTime
        }

        wifiDataCache.get()?.let { cachedData ->
            dm.putAll(cachedData)
        }
    }

    fun collectMobileNetworkData(dm: MutableMap<String, Any>, isMoving: Boolean = true) {
        val currentTime = System.currentTimeMillis()
        val shouldUpdate = isMoving || (currentTime - lastCellularUpdate) > 30000L

        if (shouldUpdate) {
            val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            val rp = sp.getInt("rssiPrecision", -1)
            val tempMap = mutableMapOf<String, Any>()
            cellularCollector.collect(tempMap, rp)
            cellularDataCache.set(tempMap)
            lastCellularUpdate = currentTime
        }

        cellularDataCache.get()?.let { cachedData ->
            dm.putAll(cachedData)
        }
    }

    fun collectNetworkData(dm: MutableMap<String, Any>, sp: SharedPreferences, isMoving: Boolean = true) {
        if (!isInitialized.get()) {
            setDefaultNetworkValues(dm)
            return
        }

        val currentTime = System.currentTimeMillis()
        val shouldUpdate = isMoving || (currentTime - lastNetworkSpeedUpdate) > 45000L

        if (shouldUpdate) {
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
                        val speeds = Pair(
                            RoundingUtils.rn(downstreamKbps, np),
                            RoundingUtils.rn(upstreamKbps, np)
                        )
                        networkSpeedCache.set(speeds)
                        lastNetworkSpeedUpdate = currentTime
                    }
                }
            } catch (e: Exception) {
                setDefaultNetworkValues(dm)
                return
            }
        }

        networkSpeedCache.get()?.let { (dn, up) ->
            dm["dn"] = dn
            dm["up"] = up
        } ?: setDefaultNetworkValues(dm)
    }

    private fun invalidateNetworkCache() {
        networkSpeedCache.set(null)
        lastNetworkSpeedUpdate = 0L
    }

    private fun setDefaultNetworkValues(dm: MutableMap<String, Any>) {
        dm["dn"] = 0
        dm["up"] = 0
    }

    fun isNetworkDataAvailable(): Boolean {
        return isInitialized.get() && try {
            cm.activeNetwork != null
        } catch (e: Exception) {
            false
        }
    }
}