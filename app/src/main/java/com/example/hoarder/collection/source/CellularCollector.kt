package com.example.hoarder.collection.source

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.example.hoarder.common.math.RoundingUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CellularCollector(private val ctx: Context) {
    private lateinit var tm: TelephonyManager
    private val isInitialized = AtomicBoolean(false)
    private val hasPermissions = AtomicBoolean(false)
    private val cellInfoCache = AtomicReference<List<CellInfo>?>(null)
    private val lastCellularData = AtomicReference<Map<String, Any>?>(null)
    private val lastProcessedCellId = AtomicReference<String?>(null)
    private val lastFreshRequest = AtomicLong(0L)
    private var lastCellInfoUpdate = 0L

    companion object {
        private const val CELL_INFO_CACHE_TTL = 5000L
        private const val FRESH_REQUEST_INTERVAL = 1000L
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

        cellInfoCache.set(cellInfo)
        lastCellInfoUpdate = System.currentTimeMillis()

        if (primaryCellId != lastCellId) {
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

    private fun getCurrentPowerMode(): String {
        return ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            .getString("powerMode", "continuous") ?: "continuous"
    }

    private fun requestFreshCellInfoAsync() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFreshRequest.get() > FRESH_REQUEST_INTERVAL) {
                lastFreshRequest.set(currentTime)

                try {
                    val callback = object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: List<CellInfo>) {
                            processCellInfoUpdate(cellInfo)
                        }
                    }
                    tm.requestCellInfoUpdate(ctx.mainExecutor, callback)
                } catch (e: Exception) {
                }
            }
        }
    }

    fun collect(dm: MutableMap<String, Any>, rp: Int) {
        if (!isInitialized.get() || !hasPermissions.get()) {
            setDefaultCellValues(dm)
            return
        }

        val powerMode = getCurrentPowerMode()

        try {
            if (powerMode == "continuous") {
                collectFreshCellularData(dm, rp)
            } else {
                collectOptimizedCellularData(dm, rp)
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun collectFreshCellularData(dm: MutableMap<String, Any>, rp: Int) {
        dm["o"] = getOperatorName()
        dm["t"] = getNetworkTypeName()

        requestFreshCellInfoAsync()

        val currentCellInfo = cellInfoCache.get() ?: tm.allCellInfo

        if (currentCellInfo != null && processCellInfo(currentCellInfo, dm, rp)) {
            return
        } else {
            setDefaultCellValues(dm)
        }
    }

    private fun collectOptimizedCellularData(dm: MutableMap<String, Any>, rp: Int) {
        val currentTime = System.currentTimeMillis()
        val cachedData = lastCellularData.get()

        if (cachedData != null && (currentTime - lastCellInfoUpdate) < CELL_INFO_CACHE_TTL) {
            dm.putAll(cachedData)
            return
        }

        dm["o"] = getOperatorName()
        dm["t"] = getNetworkTypeName()

        var currentCellInfo = cellInfoCache.get()
        if (currentCellInfo == null || (currentTime - lastCellInfoUpdate) > CELL_INFO_CACHE_TTL) {
            currentCellInfo = tm.allCellInfo
            if (currentCellInfo != null) {
                processCellInfoUpdate(currentCellInfo)
            }
        }

        if (currentCellInfo == null || !processCellInfo(currentCellInfo, dm, rp)) {
            setDefaultCellValues(dm)
        } else {
            val collectedData = dm.filterKeys { it in setOf("o", "t", "ci", "tc", "mc", "mn", "r") }
            lastCellularData.set(collectedData)
        }
    }

    private fun getOperatorName(): String = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"

    private fun getNetworkTypeName(): String = when (tm.dataNetworkType) {
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

    private fun processCellInfo(cellInfoList: List<CellInfo>, dm: MutableMap<String, Any>, rp: Int): Boolean {
        val registeredCell = cellInfoList.firstOrNull { it.isRegistered } ?: return false

        return when (registeredCell) {
            is CellInfoLte -> { processLteCell(registeredCell, dm, rp); true }
            is CellInfoWcdma -> { processWcdmaCell(registeredCell, dm, rp); true }
            is CellInfoGsm -> { processGsmCell(registeredCell, dm, rp); true }
            is CellInfoNr -> { processNrCell(registeredCell, dm, rp); true }
            else -> false
        }
    }

    private fun processLteCell(ci: CellInfoLte, dm: MutableMap<String, Any>, rp: Int) {
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

    private fun processWcdmaCell(ci: CellInfoWcdma, dm: MutableMap<String, Any>, rp: Int) {
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

    private fun processGsmCell(ci: CellInfoGsm, dm: MutableMap<String, Any>, rp: Int) {
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

    private fun processNrCell(ci: CellInfoNr, dm: MutableMap<String, Any>, rp: Int) {
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

    private fun setDefaultCellValues(dm: MutableMap<String, Any>) {
        dm["o"] = "unknown"
        dm["t"] = "unknown"
    }
}