package com.example.hoarder.sensors

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.net.wifi.WifiManager
import com.example.hoarder.data.DataUtils
import kotlin.math.ceil

class NetworkDataCollector(private val ctx: Context) {
    private lateinit var tm: TelephonyManager
    private lateinit var wm: WifiManager
    private lateinit var cm: ConnectivityManager

    fun init() {
        tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    fun collectWifiData(dm: MutableMap<String, Any>) {
        val wi = wm.connectionInfo
        val bssidValue = if (wi != null && wi.bssid != null && wi.bssid != "02:00:00:00:00:00" && wi.bssid != "00:00:00:00:00:00") {
            wi.bssid.replace(":", "")
        } else {
            "0"
        }
        dm["bssid"] = bssidValue
    }

    fun collectNetworkData(dm: MutableMap<String, Any>, sp: SharedPreferences) {
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

    fun collectMobileNetworkData(dm: MutableMap<String, Any>) {
        try {
            dm["op"] = tm.networkOperatorName
            val ant = getNetworkTypeName()
            dm["nt"] = ant

            val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            val rp = sp.getInt("rssiPrecision", -1)

            val cl: List<CellInfo>? = tm.allCellInfo
            var fo = false
            cl?.forEach { ci ->
                if (ci.isRegistered && !fo) {
                    fo = true
                    processCellInfo(ci, dm, rp)
                }
            }
            if (!fo) {
                setDefaultCellValues(dm)
            }
        } catch (e: SecurityException) {
            setDefaultCellValues(dm)
        }
    }

    private fun getNetworkTypeName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
                TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
                TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                else -> "Unknown"
            }
        } else "Unknown"
    }

    private fun processCellInfo(ci: CellInfo, dm: MutableMap<String, Any>, rp: Int) {
        when (ci) {
            is CellInfoLte -> {
                dm["ci"] = ci.cellIdentity.ci
                dm["tac"] = ci.cellIdentity.tac
                dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
                dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
                val ss = ci.cellSignalStrength
                if (ss.dbm != Int.MAX_VALUE) {
                    dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
                }
            }
            is CellInfoWcdma -> {
                dm["ci"] = ci.cellIdentity.cid
                dm["tac"] = ci.cellIdentity.lac
                dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
                dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
                val ss = ci.cellSignalStrength
                if (ss.dbm != Int.MAX_VALUE) {
                    dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
                }
            }
            is CellInfoGsm -> {
                dm["ci"] = ci.cellIdentity.cid
                dm["tac"] = ci.cellIdentity.lac
                dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
                dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
                val ss = ci.cellSignalStrength
                if (ss.dbm != Int.MAX_VALUE) {
                    dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
                }
            }
            is CellInfoNr -> {
                val cin = ci.cellIdentity as? CellIdentityNr
                dm["ci"] = cin?.nci ?: "N/A"
                dm["tac"] = cin?.tac ?: -1
                dm["mcc"] = cin?.mccString ?: "N/A"
                dm["mnc"] = cin?.mncString ?: "N/A"
                val ss = ci.cellSignalStrength as? CellSignalStrengthNr
                if (ss != null && ss.ssRsrp != Int.MIN_VALUE) {
                    dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.ssRsrp) else DataUtils.rs(ss.ssRsrp, rp)
                }
            }
        }
    }

    private fun setDefaultCellValues(dm: MutableMap<String, Any>) {
        dm["ci"] = "N/A"
        dm["tac"] = "N/A"
        dm["mcc"] = "N/A"
        dm["mnc"] = "N/A"
        dm["rssi"] = "N/A"
    }
}