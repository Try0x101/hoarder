package com.example.hoarder.collection.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.example.hoarder.common.math.RoundingUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BatteryCollector(private val ctx: Context) {
    private lateinit var bm: BatteryManager
    private val batteryData = AtomicReference<Map<String, Any>?>(null)
    private val isInitialized = AtomicBoolean(false)
    private var receiverRegistered = AtomicBoolean(false)

    private val br = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_BATTERY_CHANGED) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (l < 0 || s <= 0) return

                val p = l * 100 / s.toFloat()
                var capacityMah: Int? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                        if (chargeCounter != Int.MIN_VALUE) {
                            capacityMah = chargeCounter / 1000
                        }
                    } catch (e: Exception) { }
                }

                val resultMap = mutableMapOf<String, Any>()
                val percentage = if (precision == -1) RoundingUtils.smartBattery(p.toInt()) else RoundingUtils.rb(p.toInt(), precision)
                resultMap["p"] = percentage

                capacityMah?.let {
                    val finalCapacity = RoundingUtils.smartCapacity(it)
                    if (finalCapacity > 0) {
                        resultMap["c"] = finalCapacity
                    }
                }

                batteryData.set(resultMap)
            }
        }
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            if (receiverRegistered.compareAndSet(false, true)) {
                ctx.registerReceiver(br, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        }
    }

    fun cleanup() {
        if (receiverRegistered.compareAndSet(true, false)) {
            try {
                ctx.unregisterReceiver(br)
            } catch (e: Exception) { }
        }
        isInitialized.set(false)
    }

    private var precision = -1

    fun collect(dm: MutableMap<String, Any>, precision: Int) {
        this.precision = precision
        batteryData.get()?.let { data ->
            dm.putAll(data)
        }
    }
}