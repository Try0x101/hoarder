package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.hoarder.collection.source.CellularCollector
import com.example.hoarder.collection.source.WifiCollector
import com.example.hoarder.common.math.RoundingUtils
import java.util.concurrent.atomic.AtomicBoolean

class NetworkDataCollector(private val ctx: Context) {
    private lateinit var cm: ConnectivityManager
    private val wifiCollector = WifiCollector(ctx)
    private val cellularCollector = CellularCollector(ctx)
    private val isInitialized = AtomicBoolean(false)

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                wifiCollector.init()
                cellularCollector.init()
            } catch (e: Exception) {
                isInitialized.set(false)
                throw RuntimeException("Failed to initialize network collectors", e)
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
            val an = cm.activeNetwork
            val nc = cm.getNetworkCapabilities(an)

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