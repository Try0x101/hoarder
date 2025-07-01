package com.example.hoarder.collection.source

import android.content.Context
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.example.hoarder.common.math.RoundingUtils
import java.util.concurrent.atomic.AtomicBoolean

class CellularCollector(private val ctx: Context) {
    private lateinit var tm: TelephonyManager
    private val isInitialized = AtomicBoolean(false)
    private val hasPermissions = AtomicBoolean(false)

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                checkPermissions()
            } catch (e: Exception) {
                isInitialized.set(false)
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

    fun collect(dm: MutableMap<String, Any>, rp: Int) {
        if (!isInitialized.get() || !hasPermissions.get()) {
            setDefaultCellValues(dm)
            return
        }

        try {
            dm["op"] = getOperatorName()
            dm["nt"] = getNetworkTypeName()

            if (!processCellInfo(dm, rp)) {
                setDefaultCellValues(dm)
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun getOperatorName(): String = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "Unknown"

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

    private fun processCellInfo(dm: MutableMap<String, Any>, rp: Int): Boolean {
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
        return foundRegistered
    }

    private fun processLteCell(ci: CellInfoLte, dm: MutableMap<String, Any>, rp: Int) {
        dm["ci"] = ci.cellIdentity.ci.takeIf { it != Int.MAX_VALUE } ?: "N/A"
        dm["tac"] = ci.cellIdentity.tac.takeIf { it != Int.MAX_VALUE } ?: "N/A"
        dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
        dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
        val ss = ci.cellSignalStrength
        dm["rssi"] = if (ss.dbm != Int.MAX_VALUE) (if (rp == -1) RoundingUtils.smartRSSI(ss.dbm) else RoundingUtils.rs(ss.dbm, rp)) else "N/A"
    }

    private fun processWcdmaCell(ci: CellInfoWcdma, dm: MutableMap<String, Any>, rp: Int) {
        dm["ci"] = ci.cellIdentity.cid.takeIf { it != Int.MAX_VALUE } ?: "N/A"
        dm["tac"] = ci.cellIdentity.lac.takeIf { it != Int.MAX_VALUE } ?: "N/A"
        dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
        dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
        val ss = ci.cellSignalStrength
        dm["rssi"] = if (ss.dbm != Int.MAX_VALUE) (if (rp == -1) RoundingUtils.smartRSSI(ss.dbm) else RoundingUtils.rs(ss.dbm, rp)) else "N/A"
    }

    private fun processGsmCell(ci: CellInfoGsm, dm: MutableMap<String, Any>, rp: Int) {
        dm["ci"] = ci.cellIdentity.cid.takeIf { it != Int.MAX_VALUE } ?: "N/A"
        dm["tac"] = ci.cellIdentity.lac.takeIf { it != Int.MAX_VALUE } ?: "N/A"
        dm["mcc"] = ci.cellIdentity.mccString ?: "N/A"
        dm["mnc"] = ci.cellIdentity.mncString ?: "N/A"
        val ss = ci.cellSignalStrength
        dm["rssi"] = if (ss.dbm != Int.MAX_VALUE) (if (rp == -1) RoundingUtils.smartRSSI(ss.dbm) else RoundingUtils.rs(ss.dbm, rp)) else "N/A"
    }

    private fun processNrCell(ci: CellInfoNr, dm: MutableMap<String, Any>, rp: Int) {
        val cin = ci.cellIdentity as? CellIdentityNr
        dm["ci"] = cin?.nci ?: "N/A"
        dm["tac"] = cin?.tac?.takeIf { it != Int.MAX_VALUE } ?: "N/A"
        dm["mcc"] = cin?.mccString ?: "N/A"
        dm["mnc"] = cin?.mncString ?: "N/A"
        val ss = ci.cellSignalStrength as? CellSignalStrengthNr
        dm["rssi"] = if (ss?.ssRsrp != null && ss.ssRsrp != Int.MIN_VALUE) (if (rp == -1) RoundingUtils.smartRSSI(ss.ssRsrp) else RoundingUtils.rs(ss.ssRsrp, rp)) else "N/A"
    }

    private fun setDefaultCellValues(dm: MutableMap<String, Any>) {
        dm["op"] = "Unknown"
        dm["nt"] = "Unknown"
        dm["ci"] = "N/A"
        dm["tac"] = "N/A"
        dm["mcc"] = "N/A"
        dm["mnc"] = "N/A"
        dm["rssi"] = "N/A"
    }
}