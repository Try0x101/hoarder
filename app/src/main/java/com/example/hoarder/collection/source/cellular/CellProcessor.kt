package com.example.hoarder.collection.source.cellular

import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.example.hoarder.common.math.RoundingUtils

object CellProcessor {
    fun processLteCell(ci: CellInfoLte, dm: MutableMap<String, Any>, rp: Int) {
        ci.cellIdentity.ci.takeIf { it != Int.MAX_VALUE }?.let { dm["ci"] = it }
        ci.cellIdentity.tac.takeIf { it != Int.MAX_VALUE }?.let { dm["tc"] = it }
        ci.cellIdentity.mccString?.let { mcc ->
            val mccNum = mcc.toIntOrNull()
            dm["mc"] = mccNum ?: mcc
        }
        ci.cellIdentity.mncString?.let { dm["mn"] = it }
        val ss = ci.cellSignalStrength
        if (ss.dbm != Int.MAX_VALUE) {
            val rssi = if (rp == -1) RoundingUtils.smartRSSI(ss.dbm) else RoundingUtils.rs(ss.dbm, rp)
            dm["r"] = rssi
        }
    }

    fun processWcdmaCell(ci: CellInfoWcdma, dm: MutableMap<String, Any>, rp: Int) {
        ci.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }?.let { dm["ci"] = it }
        ci.cellIdentity.lac.takeIf { it != Int.MAX_VALUE }?.let { dm["tc"] = it }
        ci.cellIdentity.mccString?.let { mcc ->
            val mccNum = mcc.toIntOrNull()
            dm["mc"] = mccNum ?: mcc
        }
        ci.cellIdentity.mncString?.let { dm["mn"] = it }
        val ss = ci.cellSignalStrength
        if (ss.dbm != Int.MAX_VALUE) {
            val rssi = if (rp == -1) RoundingUtils.smartRSSI(ss.dbm) else RoundingUtils.rs(ss.dbm, rp)
            dm["r"] = rssi
        }
    }

    fun processGsmCell(ci: CellInfoGsm, dm: MutableMap<String, Any>, rp: Int) {
        ci.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }?.let { dm["ci"] = it }
        ci.cellIdentity.lac.takeIf { it != Int.MAX_VALUE }?.let { dm["tc"] = it }
        ci.cellIdentity.mccString?.let { mcc ->
            val mccNum = mcc.toIntOrNull()
            dm["mc"] = mccNum ?: mcc
        }
        ci.cellIdentity.mncString?.let { dm["mn"] = it }
        val ss = ci.cellSignalStrength
        if (ss.dbm != Int.MAX_VALUE) {
            val rssi = if (rp == -1) RoundingUtils.smartRSSI(ss.dbm) else RoundingUtils.rs(ss.dbm, rp)
            dm["r"] = rssi
        }
    }

    fun processNrCell(ci: CellInfoNr, dm: MutableMap<String, Any>, rp: Int) {
        val cin = ci.cellIdentity as? CellIdentityNr
        cin?.nci?.let { dm["ci"] = it }
        cin?.tac?.takeIf { it != Int.MAX_VALUE }?.let { dm["tc"] = it }
        cin?.mccString?.let { mcc ->
            val mccNum = mcc.toIntOrNull()
            dm["mc"] = mccNum ?: mcc
        }
        cin?.mncString?.let { dm["mn"] = it }
        val ss = ci.cellSignalStrength as? CellSignalStrengthNr
        ss?.dbm?.takeIf { it != Int.MAX_VALUE }?.let {
            val rssi = if (rp == -1) RoundingUtils.smartRSSI(it) else RoundingUtils.rs(it, rp)
            dm["r"] = rssi
        }
    }

    @Suppress("DEPRECATION")
    fun getNetworkTypeName(dataNetworkType: Int): String = when (dataNetworkType) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSDPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        else -> "unknown"
    }
}