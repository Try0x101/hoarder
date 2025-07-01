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
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.hoarder.data.DataUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class NetworkDataCollector(private val ctx: Context) {
    private lateinit var tm: TelephonyManager
    private lateinit var wm: WifiManager
    private lateinit var cm: ConnectivityManager
    private val isInitialized = AtomicBoolean(false)
    private val hasPermissions = AtomicBoolean(false)

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                checkPermissions()
            } catch (e: Exception) {
                isInitialized.set(false)
                throw RuntimeException("Failed to initialize network collectors", e)
            }
        }
    }

    private fun checkPermissions() {
        hasPermissions.set(
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    fun collectWifiData(dm: MutableMap<String, Any>) {
        if (!isInitialized.get()) {
            dm["bssid"] = "0"
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
            dm["bssid"] = bssidValue
        } catch (e: SecurityException) {
            dm["bssid"] = "0"
        } catch (e: Exception) {
            dm["bssid"] = "0"
        }
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
                    dm["dn"] = DataUtils.rn(downstreamKbps, np)
                    dm["up"] = DataUtils.rn(upstreamKbps, np)
                } else {
                    setDefaultNetworkValues(dm)
                }
            } else {
                setDefaultNetworkValues(dm)
            }
        } catch (e: SecurityException) {
            setDefaultNetworkValues(dm)
        } catch (e: Exception) {
            setDefaultNetworkValues(dm)
        }
    }

    private fun setDefaultNetworkValues(dm: MutableMap<String, Any>) {
        dm["dn"] = 0
        dm["up"] = 0
    }

    fun collectMobileNetworkData(dm: MutableMap<String, Any>) {
        if (!isInitialized.get()) {
            setDefaultCellValues(dm)
            return
        }

        try {
            if (!hasPermissions.get()) {
                setDefaultCellValues(dm)
                return
            }

            dm["op"] = getOperatorName()
            dm["nt"] = getNetworkTypeName()

            val sp = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            val rp = sp.getInt("rssiPrecision", -1)

            val cellFound = processCellInfo(dm, rp)
            if (!cellFound) {
                setDefaultCellValues(dm)
            }
        } catch (e: SecurityException) {
            setDefaultCellValues(dm)
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun getOperatorName(): String {
        return try {
            tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getNetworkTypeName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun processCellInfo(dm: MutableMap<String, Any>, rp: Int): Boolean {
        return try {
            val cl: List<CellInfo>? = tm.allCellInfo
            var foundRegistered = false

            cl?.forEach { ci ->
                if (ci.isRegistered && !foundRegistered) {
                    foundRegistered = true
                    when (ci) {
                        is CellInfoLte -> processLteCell(ci, dm, rp)
                        is CellInfoWcdma -> processWcdmaCell(ci, dm, rp)
                        is CellInfoGsm -> processGsmCell(ci, dm, rp)
                        is CellInfoNr -> processNrCell(ci, dm, rp)
                        else -> return@forEach
                    }
                }
            }
            foundRegistered
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun processLteCell(ci: CellInfoLte, dm: MutableMap<String, Any>, rp: Int) {
        try {
            dm["ci"] = ci.cellIdentity.ci.takeIf { it != Int.MAX_VALUE } ?: "N/A"
            dm["tac"] = ci.cellIdentity.tac.takeIf { it != Int.MAX_VALUE } ?: "N/A"
            dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
            dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"

            val ss = ci.cellSignalStrength
            if (ss.dbm != Int.MAX_VALUE) {
                dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
            } else {
                dm["rssi"] = "N/A"
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun processWcdmaCell(ci: CellInfoWcdma, dm: MutableMap<String, Any>, rp: Int) {
        try {
            dm["ci"] = ci.cellIdentity.cid.takeIf { it != Int.MAX_VALUE } ?: "N/A"
            dm["tac"] = ci.cellIdentity.lac.takeIf { it != Int.MAX_VALUE } ?: "N/A"
            dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
            dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"

            val ss = ci.cellSignalStrength
            if (ss.dbm != Int.MAX_VALUE) {
                dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
            } else {
                dm["rssi"] = "N/A"
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun processGsmCell(ci: CellInfoGsm, dm: MutableMap<String, Any>, rp: Int) {
        try {
            dm["ci"] = ci.cellIdentity.cid.takeIf { it != Int.MAX_VALUE } ?: "N/A"
            dm["tac"] = ci.cellIdentity.lac.takeIf { it != Int.MAX_VALUE } ?: "N/A"
            dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
            dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"

            val ss = ci.cellSignalStrength
            if (ss.dbm != Int.MAX_VALUE) {
                dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.dbm) else DataUtils.rs(ss.dbm, rp)
            } else {
                dm["rssi"] = "N/A"
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun processNrCell(ci: CellInfoNr, dm: MutableMap<String, Any>, rp: Int) {
        try {
            val cin = ci.cellIdentity as? CellIdentityNr
            dm["ci"] = cin?.nci ?: "N/A"
            dm["tac"] = cin?.tac?.takeIf { it != Int.MAX_VALUE } ?: "N/A"
            dm["mcc"] = cin?.mccString ?: "N/A"
            dm["mnc"] = cin?.mncString ?: "N/A"

            val ss = ci.cellSignalStrength as? CellSignalStrengthNr
            if (ss?.ssRsrp != null && ss.ssRsrp != Int.MIN_VALUE) {
                dm["rssi"] = if (rp == -1) DataUtils.smartRSSI(ss.ssRsrp) else DataUtils.rs(ss.ssRsrp, rp)
            } else {
                dm["rssi"] = "N/A"
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun setDefaultCellValues(dm: MutableMap<String, Any>) {
        dm["ci"] = "N/A"
        dm["tac"] = "N/A"
        dm["mcc"] = "N/A"
        dm["mnc"] = "N/A"
        dm["rssi"] = "N/A"
    }

    fun isNetworkDataAvailable(): Boolean {
        return isInitialized.get() && try {
            cm.activeNetwork != null
        } catch (e: Exception) {
            false
        }
    }

    fun isCellularDataAvailable(): Boolean {
        return isInitialized.get() && hasPermissions.get() && try {
            tm.allCellInfo?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
    }
}