package com.example.hoarder.collection.source

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.example.hoarder.common.math.RoundingUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CellularCollector(private val ctx: Context) {
    private lateinit var tm: TelephonyManager
    private val isInitialized = AtomicBoolean(false)
    private val hasPermissions = AtomicBoolean(false)
    private val cellInfoCache = AtomicReference<List<CellInfo>?>(null)
    private val lastCellularData = AtomicReference<Map<String, Any>?>(null)
    private val lastProcessedCellId = AtomicReference<String?>(null)
    private var lastCellInfoUpdate = 0L

    companion object {
        private const val CELL_INFO_CACHE_TTL = 5000L
    }

    @Suppress("DEPRECATION")
    private val phoneStateListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                processCellInfoUpdate(cellInfo)
            }
        }
    } else {
        object : PhoneStateListener() {
            override fun onCellInfoChanged(cellInfo: List<CellInfo>?) {
                cellInfo?.let { processCellInfoUpdate(it) }
            }
        }
    }

    private fun processCellInfoUpdate(cellInfo: List<CellInfo>) {
        val primaryCellId = extractPrimaryCellId(cellInfo)
        val lastCellId = lastProcessedCellId.get()

        if (primaryCellId != lastCellId) {
            cellInfoCache.set(cellInfo)
            lastCellInfoUpdate = System.currentTimeMillis()
            lastProcessedCellId.set(primaryCellId)
            lastCellularData.set(null)
        }
    }

    private fun extractPrimaryCellId(cellInfoList: List<CellInfo>): String? {
        return cellInfoList.firstOrNull { it.isRegistered }?.let { cellInfo ->
            when (cellInfo) {
                is CellInfoLte -> cellInfo.cellIdentity.ci.takeIf { it != Int.MAX_VALUE }?.toString()
                is CellInfoWcdma -> cellInfo.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }?.toString()
                is CellInfoGsm -> cellInfo.cellIdentity.cid.takeIf { it != Int.MAX_VALUE }?.toString()
                is CellInfoNr -> {
                    val cin = cellInfo.cellIdentity as? CellIdentityNr
                    cin?.nci?.toString()
                }
                else -> null
            }
        }
    }

    fun init() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                checkPermissions()
                if (hasPermissions.get()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        tm.registerTelephonyCallback(ctx.mainExecutor, phoneStateListener as TelephonyCallback)
                    } else {
                        tm.listen(phoneStateListener as PhoneStateListener, PhoneStateListener.LISTEN_CELL_INFO)
                    }
                }
            } catch (e: Exception) {
                isInitialized.set(false)
            }
        }
    }

    fun cleanup() {
        if (isInitialized.get() && hasPermissions.get()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tm.unregisterTelephonyCallback(phoneStateListener as TelephonyCallback)
                } else {
                    tm.listen(phoneStateListener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun checkPermissions() {
        hasPermissions.set(
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    fun collect(dm: MutableMap<String, Any>, rp: Int) {
        if (!isInitialized.get() || !hasPermissions.get()) {
            setDefaultCellValues(dm)
            return
        }

        try {
            val cachedData = lastCellularData.get()
            if (cachedData != null && !shouldRefreshCellData()) {
                dm.putAll(cachedData)
                return
            }

            dm["op"] = getOperatorName()
            dm["nt"] = getNetworkTypeName()

            var currentCellInfo = cellInfoCache.get()
            if (currentCellInfo == null || shouldRefreshFromTelephony()) {
                currentCellInfo = tm.allCellInfo
                if (currentCellInfo != null) {
                    processCellInfoUpdate(currentCellInfo)
                }
            }

            if (currentCellInfo == null || !processCellInfo(currentCellInfo, dm, rp)) {
                setDefaultCellValues(dm)
            } else {
                val collectedData = dm.filterKeys { it in setOf("op", "nt", "ci", "tac", "mcc", "mnc", "rssi") }
                lastCellularData.set(collectedData)
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun shouldRefreshCellData(): Boolean {
        return System.currentTimeMillis() - lastCellInfoUpdate > CELL_INFO_CACHE_TTL
    }

    private fun shouldRefreshFromTelephony(): Boolean {
        return cellInfoCache.get() == null || shouldRefreshCellData()
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

    private fun processCellInfo(cl: List<CellInfo>, dm: MutableMap<String, Any>, rp: Int): Boolean {
        var foundRegistered = false
        cl.forEach { ci ->
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