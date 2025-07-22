package com.example.hoarder.collection.source

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.example.hoarder.collection.source.cellular.CellProcessor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CellularCollector(private val ctx: Context) {
    private lateinit var tm: TelephonyManager
    private val isInitialized = AtomicBoolean(false)
    private val hasPermissions = AtomicBoolean(false)
    private val cellInfoCache = AtomicReference<List<CellInfo>?>(null)
    private val lastFreshRequest = AtomicLong(0L)
    private var lastCellInfoUpdate = 0L

    companion object {
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
        cellInfoCache.set(cellInfo)
        lastCellInfoUpdate = System.currentTimeMillis()
    }

    @Suppress("DEPRECATION")
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

    @Suppress("DEPRECATION")
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

    private fun requestFreshCellInfoAsync() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFreshRequest.get() > FRESH_REQUEST_INTERVAL) {
                lastFreshRequest.set(currentTime)
                try {
                    tm.requestCellInfoUpdate(ctx.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: List<CellInfo>) {
                            processCellInfoUpdate(cellInfo)
                        }
                    })
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun getCellInfoForCollection(): List<CellInfo>? {
        val powerMode = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)
            .getString("powerMode", "continuous") ?: "continuous"

        return if (powerMode == "continuous") {
            requestFreshCellInfoAsync()
            try { tm.allCellInfo } catch (e: SecurityException) { null }
        } else {
            cellInfoCache.get() ?: run {
                val freshInfo = try { tm.allCellInfo } catch (e: SecurityException) { null }
                freshInfo?.let { processCellInfoUpdate(it) }
                freshInfo
            }
        }
    }

    fun collect(dm: MutableMap<String, Any>, rp: Int) {
        if (!isInitialized.get() || !hasPermissions.get()) {
            setDefaultCellValues(dm)
            return
        }
        try {
            dm["o"] = getOperatorName()
            dm["t"] = getNetworkTypeName()
            val currentCellInfo = getCellInfoForCollection()
            if (currentCellInfo == null || !processCellInfo(currentCellInfo, dm, rp)) {
                setDefaultCellValues(dm)
            }
        } catch (e: Exception) {
            setDefaultCellValues(dm)
        }
    }

    private fun getOperatorName(): String = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"

    @Suppress("DEPRECATION")
    private fun getNetworkTypeName(): String = CellProcessor.getNetworkTypeName(tm.dataNetworkType)

    private fun processCellInfo(cellInfoList: List<CellInfo>, dm: MutableMap<String, Any>, rp: Int): Boolean {
        val registeredCell = cellInfoList.firstOrNull { it.isRegistered } ?: return false
        return when (registeredCell) {
            is CellInfoLte -> { CellProcessor.processLteCell(registeredCell, dm, rp); true }
            is CellInfoWcdma -> { CellProcessor.processWcdmaCell(registeredCell, dm, rp); true }
            is CellInfoGsm -> { CellProcessor.processGsmCell(registeredCell, dm, rp); true }
            is CellInfoNr -> { CellProcessor.processNrCell(registeredCell, dm, rp); true }
            else -> false
        }
    }

    private fun setDefaultCellValues(dm: MutableMap<String, Any>) {
        dm["o"] = "unknown"
        dm["t"] = "unknown"
    }
}