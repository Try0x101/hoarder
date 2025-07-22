package com.example.hoarder.collection.source.cellular

import android.telephony.CellIdentityNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.example.hoarder.common.math.RoundingUtils

object CellProcessor {

    private fun processCell(
        cellId: Number?,
        tacLac: Int?,
        mcc: String?,
        mnc: String?,
        dbm: Int?,
        dm: MutableMap<String, Any>,
        rp: Int
    ) {
        cellId?.let {
            val longVal = it.toLong()
            if (longVal != Int.MAX_VALUE.toLong() && longVal != Long.MAX_VALUE) {
                dm["ci"] = it
            }
        }
        tacLac?.takeIf { it != Int.MAX_VALUE }?.let { dm["tc"] = it }
        mcc?.let { mccVal ->
            dm["mc"] = mccVal.toIntOrNull() ?: mccVal
        }
        mnc?.let { dm["mn"] = it }
        dbm?.takeIf { it != Int.MAX_VALUE }?.let {
            dm["r"] = if (rp == -1) RoundingUtils.smartRSSI(it) else RoundingUtils.rs(it, rp)
        }
    }

    fun processLteCell(ci: CellInfoLte, dm: MutableMap<String, Any>, rp: Int) {
        val identity = ci.cellIdentity
        processCell(identity.ci, identity.tac, identity.mccString, identity.mncString, ci.cellSignalStrength.dbm, dm, rp)
    }

    fun processWcdmaCell(ci: CellInfoWcdma, dm: MutableMap<String, Any>, rp: Int) {
        val identity = ci.cellIdentity
        processCell(identity.cid, identity.lac, identity.mccString, identity.mncString, ci.cellSignalStrength.dbm, dm, rp)
    }

    fun processGsmCell(ci: CellInfoGsm, dm: MutableMap<String, Any>, rp: Int) {
        val identity = ci.cellIdentity
        processCell(identity.cid, identity.lac, identity.mccString, identity.mncString, ci.cellSignalStrength.dbm, dm, rp)
    }

    fun processNrCell(ci: CellInfoNr, dm: MutableMap<String, Any>, rp: Int) {
        val cin = ci.cellIdentity as? CellIdentityNr
        val ss = ci.cellSignalStrength as? CellSignalStrengthNr
        processCell(cin?.nci, cin?.tac, cin?.mccString, cin?.mncString, ss?.dbm, dm, rp)
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