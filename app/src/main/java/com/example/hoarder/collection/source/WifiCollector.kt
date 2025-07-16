package com.example.hoarder.collection.source

import android.content.Context
import android.net.wifi.WifiManager
import java.util.concurrent.atomic.AtomicBoolean

class WifiCollector(private val ctx: Context) {
    private lateinit var wm: WifiManager
    private val isInitialized = AtomicBoolean(false)

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            } catch (e: Exception) {
                isInitialized.set(false)
            }
        }
    }

    fun collect(dm: MutableMap<String, Any>) {
        if (!isInitialized.get()) {
            dm["b"] = "0"
            return
        }

        try {
            val wi = wm.connectionInfo
            val bssidValue = if (wi?.bssid != null &&
                wi.bssid != "02:00:00:00:00:00" &&
                wi.bssid != "00:00:00:00:00:00") {
                wi.bssid.replace(":", "")
            } else {
                "0"
            }
            dm["b"] = bssidValue
        } catch (e: Exception) {
            dm["b"] = "0"
        }
    }
}