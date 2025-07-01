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
import com.google.gson.GsonBuilder
import java.util.Locale
import kotlin.math.roundToInt

class DataCollector(private val ctx: Context, private val h: Handler, private val callback: (String) -> Unit) {
    private lateinit var bm: BatteryManager
    private var bd: Map<String, Any>? = null
    private var ca = false
    private val dr = object : Runnable {
        override fun run() {
            if (ca) {
                collectData(); h.postDelayed(this, 1000L)
            }
        }
    }

    private val sensorMgr = SensorManager(ctx, h)
    private val networkCollector = NetworkDataCollector(ctx)

    private val br = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_BATTERY_CHANGED) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val p = l * 100 / s.toFloat()
                var c2: Int? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val cp = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if (cc > 0 && cp > 0) {
                        c2 = (cc / 1000 * 100) / cp; c2 = (c2 / 100) * 100
                    }
                }
                bd = buildMap {
                    put("perc", p.toInt());
                    if (c2 != null) put("cap", c2)
                }
            }
        }
    }

    fun init() {
        bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        sensorMgr.init()
        networkCollector.init()
        ctx.registerReceiver(br, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun start() {
        h.removeCallbacks(dr); ca = true; h.post(dr)
    }

    fun stop() {
        ca = false; h.removeCallbacks(dr)
    }

    fun cleanup() {
        stop()
        try {
            ctx.unregisterReceiver(br)
            sensorMgr.cleanup()
        } catch (e: Exception) {}
    }

    private fun collectData() {
        if (!ca) return
        val dm = mutableMapOf<String, Any>()

        dm["id"] = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).take(4)
        dm["n"] = Build.MODEL

        val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
        collectBatteryData(dm, sp)
        collectLocationData(dm, sp)
        networkCollector.collectNetworkData(dm, sp)
        networkCollector.collectWifiData(dm)
        networkCollector.collectMobileNetworkData(dm)

        val stringMap = convertToStringMap(dm)
        callback(GsonBuilder().setPrettyPrinting().create().toJson(stringMap))
    }

    private fun collectBatteryData(dm: MutableMap<String, Any>, sp: SharedPreferences) {
        val bp = sp.getInt("batteryPrecision", 5)
        bd?.let {
            it["perc"]?.let { v ->
                when (v) {
                    is Int -> {
                        dm["perc"] = if (bp == -1) DataUtils.smartBattery(v) else DataUtils.rb(v, bp)
                    }
                    else -> dm["perc"] = v
                }
            }
            it["cap"]?.let { v -> dm["cap"] = v }
        }
    }

    private fun collectLocationData(dm: MutableMap<String, Any>, sp: SharedPreferences) {
        sensorMgr.getLocation()?.let { location ->
            val gp = sp.getInt("gpsPrecision", -1)
            val spdp = sp.getInt("speedPrecision", -1)
            val altP = sp.getInt("gpsAltitudePrecision", -1)

            dm["alt"] = sensorMgr.getFilteredAltitude(altP)

            val sk = (location.speed * 3.6).roundToInt()
            val rs = DataUtils.rsp(sk, spdp)
            val (prec, acc2) = if (gp == -1) DataUtils.smartGPSPrecision(location.speed) else Pair(gp, gp)

            val (rl, rlo, ac) = calculateLocationPrecision(location, prec)
            dm["lat"] = rl; dm["lon"] = rlo; dm["acc"] = ac; dm["spd"] = rs
        }
    }

    private fun calculateLocationPrecision(location: android.location.Location, prec: Int): Triple<Double, Double, Int> {
        return when (prec) {
            0 -> {
                Triple(
                    String.Companion.format(Locale.US, "%.6f", location.latitude).toDouble(),
                    String.Companion.format(Locale.US, "%.6f", location.longitude).toDouble(),
                    (location.accuracy / 1).roundToInt() * 1
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
                    String.Companion.format(Locale.US, "%.6f", location.latitude).toDouble(),
                    String.Companion.format(Locale.US, "%.6f", location.longitude).toDouble(),
                    (location.accuracy / 1).roundToInt() * 1
                )
            }
        }
    }

    private fun convertToStringMap(data: Map<String, Any>): Map<String, String> {
        return data.mapValues { (_, value) ->
            when (value) {
                is String -> value
                is Number -> value.toString()
                else -> value.toString()
            }
        }
    }
}