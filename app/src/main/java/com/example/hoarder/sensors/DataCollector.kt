// app/src/main/java/com/example/hoarder/sensors/DataCollector.kt
package com.example.hoarder.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.example.hoarder.data.DataUtils
import com.google.gson.GsonBuilder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class DataCollector(private val ctx: Context, private val h: Handler, private val callback: (String) -> Unit) {
    private lateinit var tm: TelephonyManager
    private lateinit var wm: WifiManager
    private lateinit var bm: BatteryManager
    private lateinit var cm: ConnectivityManager
    private var bd: Map<String, Any>? = null
    private var ca = false
    private val dr = object : Runnable { override fun run() { if (ca) { c(); h.postDelayed(this, 1000L) } } }

    private val sensorMgr = SensorManager(ctx, h)

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
                    if (cc > 0 && cp > 0) { c2 = (cc / 1000 * 100) / cp; c2 = (c2 / 100) * 100 }
                }
                bd = buildMap { put("perc", p.toInt()); if (c2 != null) put("cap", c2) }
            }
        }
    }

    fun init() {
        tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        sensorMgr.init()
        ctx.registerReceiver(br, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun start() { h.removeCallbacks(dr); ca = true; h.post(dr) }
    fun stop() { ca = false; h.removeCallbacks(dr) }

    fun cleanup() {
        stop()
        try {
            ctx.unregisterReceiver(br)
            sensorMgr.cleanup()
        } catch (e: Exception) {}
    }

    private fun c() {
        if (!ca) return
        val dm = mutableMapOf<String, Any>()
        dm["id"] = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).take(4)
        dm["n"] = Build.MODEL

        val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
        val bp = sp.getInt("batteryPrecision", 5)
        bd?.let {
            it["perc"]?.let { v -> when (v) { is Int -> { dm["perc"] = if (bp == -1) DataUtils.smartBattery(v) else DataUtils.rb(v, bp) }; else -> dm["perc"] = v } }
            it["cap"]?.let { v -> dm["cap"] = v }
        }

        collectLocationData(dm, sp)
        collectNetworkData(dm, sp)
        collectWifiData(dm)
        collectMobileNetworkData(dm)

        callback(GsonBuilder().setPrettyPrinting().create().toJson(dm))
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
            val (rl, rlo, ac) = when (prec) {
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
                        maxOf(20, (location.accuracy / 20).roundToInt() * 20)
                    )
                }
                100 -> {
                    Triple(
                        (location.latitude * 1000).roundToInt() / 1000.0,
                        (location.longitude * 1000).roundToInt() / 1000.0,
                        maxOf(100, (location.accuracy / 100).roundToInt() * 100)
                    )
                }
                1000 -> {
                    Triple(
                        (location.latitude * 100).roundToInt() / 100.0,
                        (location.longitude * 100).roundToInt() / 100.0,
                        maxOf(1000, (location.accuracy / 1000).roundToInt() * 1000)
                    )
                }
                10000 -> {
                    Triple(
                        (location.latitude * 10).roundToInt() / 10.0,
                        (location.longitude * 10).roundToInt() / 10.0,
                        maxOf(10000, (location.accuracy / 10000).roundToInt() * 10000)
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
            dm["lat"] = rl; dm["lon"] = rlo; dm["acc"] = ac; dm["spd"] = rs
        }
    }

    private fun collectWifiData(dm: MutableMap<String, Any>) {
        val wi = wm.connectionInfo
        val bssidValue = if (wi != null && wi.bssid != null && wi.bssid != "02:00:00:00:00:00" && wi.bssid != "00:00:00:00:00:00") wi.bssid else 0
        dm["bssid"] = bssidValue
    }

    private fun collectNetworkData(dm: MutableMap<String, Any>, sp: SharedPreferences) {
        val an = cm.activeNetwork
        val nc = cm.getNetworkCapabilities(an)
        if (nc != null) {
            val np = sp.getInt("networkPrecision", 0)

            if (np == -2) {
                dm["dn"] = DataUtils.rn(nc.linkDownstreamBandwidthKbps, np)
                dm["up"] = DataUtils.rn(nc.linkUpstreamBandwidthKbps, np)
            } else {
                val ldm = ceil(nc.linkDownstreamBandwidthKbps.toDouble() / 1024.0).toInt()
                val lum = ceil(nc.linkUpstreamBandwidthKbps.toDouble() / 1024.0).toInt()
                dm["dn"] = DataUtils.rn(nc.linkDownstreamBandwidthKbps, np)
                dm["up"] = DataUtils.rn(nc.linkUpstreamBandwidthKbps, np)
            }
        }
    }

    private fun collectMobileNetworkData(dm: MutableMap<String, Any>) {
        try {
            dm["op"] = tm.networkOperatorName
            val ant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"; TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"; TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                    TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"; TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                    TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"; TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                    TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"; TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                    TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"; TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"; TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"; TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
                    TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"; TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G NR"; else -> "Unknown"
                }
            } else "Unknown"
            dm["nt"] = ant

            val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            val rp = sp.getInt("rssiPrecision", -1)

            val cl: List<CellInfo>? = tm.allCellInfo
            var fo = false
            cl?.forEach { ci ->
                if (ci.isRegistered && !fo) {
                    fo = true
                    when (ci) {
                        is CellInfoLte -> {
                            dm["ci"] = ci.cellIdentity.ci; dm["tac"] = ci.cellIdentity.tac
                            dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"; dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
                            val ss = ci.cellSignalStrength
                            if (ss.dbm != Int.MAX_VALUE) dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
                        }
                        is CellInfoWcdma -> {
                            dm["ci"] = ci.cellIdentity.cid; dm["tac"] = ci.cellIdentity.lac
                            dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"; dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
                            val ss = ci.cellSignalStrength
                            if (ss.dbm != Int.MAX_VALUE) dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
                        }
                        is CellInfoGsm -> {
                            dm["ci"] = ci.cellIdentity.cid; dm["tac"] = ci.cellIdentity.lac
                            dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"; dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
                            val ss = ci.cellSignalStrength
                            if (ss.dbm != Int.MAX_VALUE) dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
                        }
                        is CellInfoNr -> {
                            val cin = ci.cellIdentity as? CellIdentityNr
                            dm["ci"] = cin?.nci ?: "N/A"; dm["tac"] = cin?.tac ?: -1
                            dm["mcc"] = cin?.mccString ?: "N/A"; dm["mnc"] = cin?.mncString ?: "N/A"
                            val ss = ci.cellSignalStrength as? CellSignalStrengthNr
                            if (ss != null && ss.ssRsrp != Int.MIN_VALUE)
                                dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.ssRsrp) else DataUtils.rs(ss.ssRsrp, rp)
                        }
                    }
                }
            }
            if (!fo) { dm["ci"] = "N/A"; dm["tac"] = "N/A"; dm["mcc"] = "N/A"; dm["mnc"] = "N/A"; dm["rssi"] = "N/A" }
        } catch (e: SecurityException) {}
    }
}