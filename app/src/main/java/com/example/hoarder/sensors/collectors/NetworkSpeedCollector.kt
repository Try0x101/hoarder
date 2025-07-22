package com.example.hoarder.sensors.collectors

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.hoarder.common.math.RoundingUtils
import com.example.hoarder.data.storage.app.Prefs
import java.util.concurrent.atomic.AtomicReference

class NetworkSpeedCollector(private val ctx: Context, private val sp: SharedPreferences) {
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCapabilitiesCache = AtomicReference<NetworkCapabilities?>()
    private val networkSpeedCache = AtomicReference<Pair<Any, Any>?>(null)
    private var lastNetworkSpeedUpdate = 0L

    companion object {
        private const val OPTIMIZED_SPEED_INTERVAL = 45000L
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            networkCapabilitiesCache.set(networkCapabilities)
            lastNetworkSpeedUpdate = 0L
        }
        override fun onLost(network: Network) {
            networkCapabilitiesCache.set(null)
            lastNetworkSpeedUpdate = 0L
        }
    }

    init {
        cm.registerDefaultNetworkCallback(networkCallback)
    }

    fun cleanup() {
        try { cm.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
    }

    fun collect(dm: MutableMap<String, Any>, isMoving: Boolean) {
        val powerMode = sp.getInt(Prefs.KEY_POWER_SAVING_MODE, Prefs.POWER_MODE_CONTINUOUS)
        if (powerMode == Prefs.POWER_MODE_CONTINUOUS) {
            collectFresh(dm)
        } else {
            collectCached(dm, isMoving)
        }
    }

    private fun collectFresh(dm: MutableMap<String, Any>) {
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        updateDataMapWithCapabilities(dm, nc)
    }

    private fun collectCached(dm: MutableMap<String, Any>, isMoving: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (isMoving || (currentTime - lastNetworkSpeedUpdate) > OPTIMIZED_SPEED_INTERVAL) {
            var nc = networkCapabilitiesCache.get()
            if (nc == null) {
                nc = cm.getNetworkCapabilities(cm.activeNetwork)
                networkCapabilitiesCache.set(nc)
            }
            updateCacheWithCapabilities(nc)
            lastNetworkSpeedUpdate = currentTime
        }
        networkSpeedCache.get()?.let { (down, up) ->
            dm["d"] = down
            dm["u"] = up
        }
    }

    private fun updateCacheWithCapabilities(nc: NetworkCapabilities?) {
        if (nc == null) {
            networkSpeedCache.set(null)
            return
        }
        val np = sp.getInt(Prefs.KEY_NETWORK_PRECISION, 0)
        val downKbps = nc.linkDownstreamBandwidthKbps
        val upKbps = nc.linkUpstreamBandwidthKbps
        if (downKbps > 0 && upKbps > 0) {
            val down = RoundingUtils.rn(downKbps, np)
            val up = RoundingUtils.rn(upKbps, np)
            if (down.toDouble() > 0 && up.toDouble() > 0) {
                networkSpeedCache.set(Pair(down, up))
                return
            }
        }
        networkSpeedCache.set(null)
    }

    private fun updateDataMapWithCapabilities(dm: MutableMap<String, Any>, nc: NetworkCapabilities?) {
        if (nc == null) return
        val np = sp.getInt(Prefs.KEY_NETWORK_PRECISION, 0)
        val downKbps = nc.linkDownstreamBandwidthKbps
        val upKbps = nc.linkUpstreamBandwidthKbps
        if (downKbps > 0 && upKbps > 0) {
            val down = RoundingUtils.rn(downKbps, np)
            val up = RoundingUtils.rn(upKbps, np)
            if (down.toDouble() > 0 && up.toDouble() > 0) {
                dm["d"] = down
                dm["u"] = up
            }
        }
    }
}