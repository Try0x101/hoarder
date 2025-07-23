package com.example.hoarder.data.storage.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("HoarderPrefs", Context.MODE_PRIVATE)

    companion object {
        const val POWER_MODE_CONTINUOUS = 0
        const val POWER_MODE_OPTIMIZED = 1
        const val POWER_MODE_PASSIVE = 2

        const val KEY_IS_FIRST_RUN = "isFirstRun"
        const val KEY_DATA_COLLECTION_ENABLED = "dataCollectionToggleState"
        const val KEY_DATA_UPLOAD_ENABLED = "dataUploadToggleState"
        const val KEY_BATCH_UPLOAD_ENABLED = "batchUploadEnabled"
        const val KEY_SERVER_ADDRESS = "serverIpPortAddress"
        const val KEY_GPS_PRECISION = "gpsPrecision"
        const val KEY_GPS_ALTITUDE_PRECISION = "gpsAltitudePrecision"
        const val KEY_RSSI_PRECISION = "rssiPrecision"
        const val KEY_BATTERY_PRECISION = "batteryPrecision"
        const val KEY_NETWORK_PRECISION = "networkPrecision"
        const val KEY_SPEED_PRECISION = "speedPrecision"
        const val KEY_BATCH_RECORD_COUNT = "batchRecordCount"
        const val KEY_BATCH_TRIGGER_BY_COUNT_ENABLED = "batchTriggerByCountEnabled"
        const val KEY_BATCH_TIMEOUT_SEC = "batchTimeoutSec"
        const val KEY_BATCH_TRIGGER_BY_TIMEOUT_ENABLED = "batchTriggerByTimeoutEnabled"
        const val KEY_BATCH_MAX_SIZE_KB = "batchMaxSizeKb"
        const val KEY_BATCH_TRIGGER_BY_MAX_SIZE_ENABLED = "batchTriggerByMaxSizeEnabled"
        const val KEY_COMPRESSION_LEVEL = "compressionLevel"
        const val KEY_POWER_SAVING_MODE = "powerSavingMode"
        const val KEY_BUFFER_WARNING_THRESHOLD_KB = "bufferWarningThresholdKb"
        const val KEY_BULK_UPLOAD_THRESHOLD_KB = "bulkUploadThresholdKb"
        const val KEY_BULK_JOB_ID = "bulkJobId"
        const val KEY_BULK_JOB_STATE = "bulkJobState"
        const val KEY_BULK_TEMP_FILE_PATH = "bulkTempFilePath"
        const val KEY_TOTAL_UPLOADED_BYTES = "totalUploadedBytes"
        const val KEY_TOTAL_ACTUAL_NETWORK_BYTES = "totalActualNetworkBytes"
    }

    fun isFirstRun() = p.getBoolean(KEY_IS_FIRST_RUN, true)
    fun markFirstRunComplete() = p.edit().putBoolean(KEY_IS_FIRST_RUN, false).apply()

    fun isDataCollectionEnabled() = p.getBoolean(KEY_DATA_COLLECTION_ENABLED, true)
    fun setDataCollectionEnabled(e: Boolean) = p.edit().putBoolean(KEY_DATA_COLLECTION_ENABLED, e).apply()

    fun isDataUploadEnabled() = p.getBoolean(KEY_DATA_UPLOAD_ENABLED, true)
    fun setDataUploadEnabled(e: Boolean) = p.edit().putBoolean(KEY_DATA_UPLOAD_ENABLED, e).apply()

    fun isBatchUploadEnabled() = p.getBoolean(KEY_BATCH_UPLOAD_ENABLED, true)
    fun setBatchUploadEnabled(e: Boolean) = p.edit().putBoolean(KEY_BATCH_UPLOAD_ENABLED, e).apply()

    fun getServerAddress() = p.getString(KEY_SERVER_ADDRESS, "ingest.try0x101.uk") ?: "ingest.try0x101.uk"
    fun setServerAddress(a: String) = p.edit().putString(KEY_SERVER_ADDRESS, a).apply()

    fun getGPSPrecision() = p.getInt(KEY_GPS_PRECISION, 100)
    fun setGPSPrecision(pr: Int) = this.p.edit().putInt(KEY_GPS_PRECISION, pr).apply()

    fun getGPSAltitudePrecision() = p.getInt(KEY_GPS_ALTITUDE_PRECISION, 100)
    fun setGPSAltitudePrecision(pr: Int) = this.p.edit().putInt(KEY_GPS_ALTITUDE_PRECISION, pr).apply()

    fun getRSSIPrecision() = p.getInt(KEY_RSSI_PRECISION, 100)
    fun setRSSIPrecision(pr: Int) = this.p.edit().putInt(KEY_RSSI_PRECISION, pr).apply()

    fun getBatteryPrecision() = p.getInt(KEY_BATTERY_PRECISION, 100)
    fun setBatteryPrecision(pr: Int) = this.p.edit().putInt(KEY_BATTERY_PRECISION, pr).apply()

    fun getNetworkPrecision() = p.getInt(KEY_NETWORK_PRECISION, 100)
    fun setNetworkPrecision(pr: Int) = this.p.edit().putInt(KEY_NETWORK_PRECISION, pr).apply()

    fun getSpeedPrecision() = p.getInt(KEY_SPEED_PRECISION, 100)
    fun setSpeedPrecision(pr: Int) = this.p.edit().putInt(KEY_SPEED_PRECISION, pr).apply()

    fun getBatchRecordCount() = p.getInt(KEY_BATCH_RECORD_COUNT, 20)
    fun setBatchRecordCount(s: Int) = p.edit().putInt(KEY_BATCH_RECORD_COUNT, s).apply()

    fun isBatchTriggerByCountEnabled() = p.getBoolean(KEY_BATCH_TRIGGER_BY_COUNT_ENABLED, false)
    fun setBatchTriggerByCountEnabled(e: Boolean) = p.edit().putBoolean(KEY_BATCH_TRIGGER_BY_COUNT_ENABLED, e).apply()

    fun getBatchTimeout() = p.getInt(KEY_BATCH_TIMEOUT_SEC, 60)
    fun setBatchTimeout(t: Int) = p.edit().putInt(KEY_BATCH_TIMEOUT_SEC, t).apply()

    fun isBatchTriggerByTimeoutEnabled() = p.getBoolean(KEY_BATCH_TRIGGER_BY_TIMEOUT_ENABLED, false)
    fun setBatchTriggerByTimeoutEnabled(e: Boolean) = p.edit().putBoolean(KEY_BATCH_TRIGGER_BY_TIMEOUT_ENABLED, e).apply()

    fun getBatchMaxSizeKb() = p.getInt(KEY_BATCH_MAX_SIZE_KB, 5)
    fun setBatchMaxSizeKb(s: Int) = p.edit().putInt(KEY_BATCH_MAX_SIZE_KB, s).apply()

    fun isBatchTriggerByMaxSizeEnabled() = p.getBoolean(KEY_BATCH_TRIGGER_BY_MAX_SIZE_ENABLED, true)
    fun setBatchTriggerByMaxSizeEnabled(e: Boolean) = p.edit().putBoolean(KEY_BATCH_TRIGGER_BY_MAX_SIZE_ENABLED, e).apply()

    fun getCompressionLevel() = p.getInt(KEY_COMPRESSION_LEVEL, 9)
    fun setCompressionLevel(l: Int) = p.edit().putInt(KEY_COMPRESSION_LEVEL, l).apply()

    fun getPowerMode() = p.getInt(KEY_POWER_SAVING_MODE, POWER_MODE_PASSIVE)
    fun setPowerMode(m: Int) = p.edit().putInt(KEY_POWER_SAVING_MODE, m).apply()

    fun getBufferWarningThresholdKb() = p.getInt(KEY_BUFFER_WARNING_THRESHOLD_KB, 20480)
    fun setBufferWarningThresholdKb(kb: Int) = p.edit().putInt(KEY_BUFFER_WARNING_THRESHOLD_KB, kb).apply()

    fun getBulkUploadThresholdKb() = p.getInt(KEY_BULK_UPLOAD_THRESHOLD_KB, 10240)
    fun setBulkUploadThresholdKb(kb: Int) = p.edit().putInt(KEY_BULK_UPLOAD_THRESHOLD_KB, kb).apply()

    fun getBulkJobId(): String? = p.getString(KEY_BULK_JOB_ID, null)
    fun setBulkJobId(jobId: String?) = p.edit().putString(KEY_BULK_JOB_ID, jobId).apply()

    fun getBulkJobState(): String = p.getString(KEY_BULK_JOB_STATE, "IDLE") ?: "IDLE"
    fun setBulkJobState(state: String) = p.edit().putString(KEY_BULK_JOB_STATE, state).apply()

    fun getBulkTempFilePath(): String? = p.getString(KEY_BULK_TEMP_FILE_PATH, null)
    fun setBulkTempFilePath(path: String?) = p.edit().putString(KEY_BULK_TEMP_FILE_PATH, path).apply()

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
            setServerAddress("ingest.try0x101.uk")
            markFirstRunComplete()
        }
    }
}