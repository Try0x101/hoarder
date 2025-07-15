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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            networkCapabilitiesCache.set(networkCapabilities)
        }
        override fun onLost(network: Network) {
            super.onLost(network)
            networkCapabilitiesCache.set(null)
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
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    fun collectWifiData(dm: MutableMap<String, Any>) {
        wifiCollector.collect(dm)
    }

    fun collectMobileNetworkData(dm: MutableMap<String, Any>) {
        val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
        val rp = sp.getInt("rssiPrecision", -1)
        cellularCollector.collect(dm, rp)
    }

    fun collectNetworkData(dm: MutableMap<String, Any>, sp: SharedPreferences) {
        if (!isInitialized.get()) {
            setDefaultNetworkValues(dm)
            return
        }

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
                    dm["dn"] = RoundingUtils.rn(downstreamKbps, np)
                    dm["up"] = RoundingUtils.rn(upstreamKbps, np)
                } else {
                    setDefaultNetworkValues(dm)
                }
            } else {
                setDefaultNetworkValues(dm)
            }
        } catch (e: Exception) {
            setDefaultNetworkValues(dm)
        }
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