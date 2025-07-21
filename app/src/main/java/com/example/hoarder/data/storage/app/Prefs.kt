package com.example.hoarder.data.storage.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)

    companion object {
        const val POWER_MODE_CONTINUOUS = 0
        const val POWER_MODE_OPTIMIZED = 1
        const val POWER_MODE_PASSIVE = 2
    }

    fun isFirstRun() = p.getBoolean("isFirstRun", true)
    fun markFirstRunComplete() = p.edit().putBoolean("isFirstRun", false).apply()

    fun isDataCollectionEnabled() = p.getBoolean("dataCollectionToggleState", true)
    fun setDataCollectionEnabled(e: Boolean) = p.edit().putBoolean("dataCollectionToggleState", e).apply()

    fun isDataUploadEnabled() = p.getBoolean("dataUploadToggleState", true)
    fun setDataUploadEnabled(e: Boolean) = p.edit().putBoolean("dataUploadToggleState", e).apply()

    fun isBatchUploadEnabled() = p.getBoolean("batchUploadEnabled", true)
    fun setBatchUploadEnabled(e: Boolean) = p.edit().putBoolean("batchUploadEnabled", e).apply()

    fun getServerAddress() =
        p.getString("serverIpPortAddress", "188.132.234.72:5000") ?: "188.132.234.72:5000"
    fun setServerAddress(a: String) = p.edit().putString("serverIpPortAddress", a).apply()

    fun getGPSPrecision() = p.getInt("gpsPrecision", 100)
    fun setGPSPrecision(p: Int) = this.p.edit().putInt("gpsPrecision", p).apply()

    fun getGPSAltitudePrecision() = p.getInt("gpsAltitudePrecision", 100)
    fun setGPSAltitudePrecision(p: Int) = this.p.edit().putInt("gpsAltitudePrecision", p).apply()

    fun getRSSIPrecision() = p.getInt("rssiPrecision", 100)
    fun setRSSIPrecision(p: Int) = this.p.edit().putInt("rssiPrecision", p).apply()

    fun getBatteryPrecision() = p.getInt("batteryPrecision", 100)
    fun setBatteryPrecision(p: Int) = this.p.edit().putInt("batteryPrecision", p).apply()

    fun getNetworkPrecision() = p.getInt("networkPrecision", 100)
    fun setNetworkPrecision(p: Int) = this.p.edit().putInt("networkPrecision", p).apply()

    fun getSpeedPrecision() = p.getInt("speedPrecision", 100)
    fun setSpeedPrecision(p: Int) = this.p.edit().putInt("speedPrecision", p).apply()

    fun getBatchRecordCount() = p.getInt("batchRecordCount", 20)
    fun setBatchRecordCount(s: Int) = p.edit().putInt("batchRecordCount", s).apply()

    fun isBatchTriggerByCountEnabled() = p.getBoolean("batchTriggerByCountEnabled", false)
    fun setBatchTriggerByCountEnabled(e: Boolean) = p.edit().putBoolean("batchTriggerByCountEnabled", e).apply()

    fun getBatchTimeout() = p.getInt("batchTimeoutSec", 60)
    fun setBatchTimeout(t: Int) = p.edit().putInt("batchTimeoutSec", t).apply()

    fun isBatchTriggerByTimeoutEnabled() = p.getBoolean("batchTriggerByTimeoutEnabled", false)
    fun setBatchTriggerByTimeoutEnabled(e: Boolean) = p.edit().putBoolean("batchTriggerByTimeoutEnabled", e).apply()

    fun getBatchMaxSizeKb() = p.getInt("batchMaxSizeKb", 5)
    fun setBatchMaxSizeKb(s: Int) = p.edit().putInt("batchMaxSizeKb", s).apply()

    fun isBatchTriggerByMaxSizeEnabled() = p.getBoolean("batchTriggerByMaxSizeEnabled", true)
    fun setBatchTriggerByMaxSizeEnabled(e: Boolean) = p.edit().putBoolean("batchTriggerByMaxSizeEnabled", e).apply()

    fun getCompressionLevel() = p.getInt("compressionLevel", 9)
    fun setCompressionLevel(l: Int) = p.edit().putInt("compressionLevel", l).apply()

    fun getPowerMode() = p.getInt("powerSavingMode", POWER_MODE_PASSIVE)
    fun setPowerMode(m: Int) = p.edit().putInt("powerSavingMode", m).apply()

    fun getBufferWarningThresholdKb() = p.getInt("bufferWarningThresholdKb", 20480)
    fun setBufferWarningThresholdKb(kb: Int) = p.edit().putInt("bufferWarningThresholdKb", kb).apply()

    fun getBulkUploadThresholdKb() = p.getInt("bulkUploadThresholdKb", 10240)
    fun setBulkUploadThresholdKb(kb: Int) = p.edit().putInt("bulkUploadThresholdKb", kb).apply()

    fun getBulkJobId(): String? = p.getString("bulkJobId", null)
    fun setBulkJobId(jobId: String?) = p.edit().putString("bulkJobId", jobId).apply()

    fun getBulkJobState(): String = p.getString("bulkJobState", "IDLE") ?: "IDLE"
    fun setBulkJobState(state: String) = p.edit().putString("bulkJobState", state).apply()

    fun getBulkTempFilePath(): String? = p.getString("bulkTempFilePath", null)
    fun setBulkTempFilePath(path: String?) = p.edit().putString("bulkTempFilePath", path).apply()

    init {
        if (isFirstRun()) {
            setPowerMode(POWER_MODE_PASSIVE)
            setDataUploadEnabled(true)
            setGPSPrecision(0)
            setGPSAltitudePrecision(0)
            setRSSIPrecision(0)
            setBatteryPrecision(0)
            setNetworkPrecision(-2)
            setSpeedPrecision(0)
            setServerAddress("188.132.234.72:5000")
            markFirstRunComplete()
        }
    }
}