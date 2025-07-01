package com.example.hoarder.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RealTimeDeltaManager(
    private val context: Context,
    private val dataUploader: DataUploader
) {
    private val database = TelemetryDatabase.getDatabase(context)
    private val dao = database.telemetryDao()
    private val gson = Gson()

    private val lastKnownState = ConcurrentHashMap<String, Map<String, Any>>()
    private val isActive = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val deviceId = AtomicReference<String?>(null)

    companion object {
        const val SYNC_STATUS_PENDING = "PENDING"
        const val SYNC_STATUS_SYNCED = "SYNCED"
        const val SYNC_STATUS_FAILED = "FAILED"
    }

    fun start(deviceId: String) {
        this.deviceId.set(deviceId)
        isActive.set(true)

        scope.launch {
            try {
                val latestRecord = dao.getLatestRecord()
                latestRecord?.let { record ->
                    lastKnownState[deviceId] = convertRecordToMap(record)
                }
            } catch (e: Exception) {
                Log.e("DeltaManager", "Error loading latest record", e)
            }
        }
    }

    fun stop() {
        isActive.set(false)
        scope.cancel()
        lastKnownState.clear()
    }

    suspend fun processTelemetryData(jsonData: String): String? {
        if (!isActive.get()) return null

        return try {
            val currentData = gson.fromJson(jsonData, Map::class.java) as Map<String, Any>
            val deviceId = currentData["id"]?.toString() ?: return null

            val telemetryRecord = convertJsonToRecord(currentData)

            scope.launch {
                try {
                    dao.insertRecord(telemetryRecord)
                } catch (e: Exception) {
                    Log.e("DeltaManager", "Error inserting record", e)
                }
            }

            val previousData = lastKnownState[deviceId]
            val deltaMap = calculateDelta(previousData, currentData)

            lastKnownState[deviceId] = currentData

            if (deltaMap.isNotEmpty()) {
                val deltaJson = gson.toJson(deltaMap)
                dataUploader.queueData(deltaJson)
                deltaJson
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("DeltaManager", "Error processing telemetry data", e)
            null
        }
    }

    private fun calculateDelta(previous: Map<String, Any>?, current: Map<String, Any>): Map<String, Any> {
        if (previous == null) {
            return current.toMutableMap()
        }

        val delta = mutableMapOf<String, Any>()

        for ((key, value) in current) {
            val previousValue = previous[key]
            if (previousValue == null || !valuesEqual(previousValue, value)) {
                delta[key] = value
            }
        }

        if (current.containsKey("id")) {
            delta["id"] = current["id"]!!
        }

        if (delta.size == 1 && delta.containsKey("id")) {
            return emptyMap()
        }

        return delta
    }

    private fun valuesEqual(v1: Any, v2: Any): Boolean {
        return when {
            v1 is Number && v2 is Number -> {
                v1.toDouble() == v2.toDouble()
            }
            v1 is String && v2 is String -> v1 == v2
            else -> v1.toString() == v2.toString()
        }
    }

    private fun convertJsonToRecord(data: Map<String, Any>): TelemetryRecord {
        return TelemetryRecord(
            id = data["id"]?.toString() ?: "",
            deviceModel = data["n"]?.toString() ?: "",
            timestamp = System.currentTimeMillis(),
            batteryPercentage = safeInt(data["perc"]),
            batteryCapacity = safeIntOrNull(data["cap"]),
            latitude = safeDouble(data["lat"]),
            longitude = safeDouble(data["lon"]),
            altitude = safeInt(data["alt"]),
            accuracy = safeInt(data["acc"]),
            speed = safeInt(data["spd"]),
            networkOperator = data["op"]?.toString() ?: "",
            networkType = data["nt"]?.toString() ?: "",
            cellId = data["ci"]?.toString() ?: "",
            trackingAreaCode = data["tac"]?.toString() ?: "",
            mobileCountryCode = data["mcc"]?.toString() ?: "",
            mobileNetworkCode = data["mnc"]?.toString() ?: "",
            signalStrength = data["rssi"]?.toString() ?: "",
            wifiBssid = data["bssid"]?.toString() ?: "",
            downloadSpeed = data["dn"]?.toString() ?: "",
            uploadSpeed = data["up"]?.toString() ?: "",
            syncStatus = SYNC_STATUS_PENDING
        )
    }

    private fun convertRecordToMap(record: TelemetryRecord): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["id"] = record.id
        map["n"] = record.deviceModel
        map["perc"] = record.batteryPercentage
        record.batteryCapacity?.let { map["cap"] = it }
        map["lat"] = record.latitude
        map["lon"] = record.longitude
        map["alt"] = record.altitude
        map["acc"] = record.accuracy
        map["spd"] = record.speed
        map["op"] = record.networkOperator
        map["nt"] = record.networkType
        map["ci"] = record.cellId
        map["tac"] = record.trackingAreaCode
        map["mcc"] = record.mobileCountryCode
        map["mnc"] = record.mobileNetworkCode
        map["rssi"] = record.signalStrength
        map["bssid"] = record.wifiBssid
        map["dn"] = record.downloadSpeed
        map["up"] = record.uploadSpeed
        return map
    }

    private fun safeInt(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun safeIntOrNull(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun safeDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    suspend fun getPendingRecordsCount(): Int {
        return try {
            withContext(Dispatchers.IO) {
                dao.getCountByStatus(SYNC_STATUS_PENDING)
            }
        } catch (e: Exception) {
            0
        }
    }

    suspend fun markRecordsAsSynced(deviceId: String, count: Int) {
        try {
            withContext(Dispatchers.IO) {
                val pendingRecords = dao.getRecordsByStatus(SYNC_STATUS_PENDING)
                    .take(count)
                    .map { it.id }
                if (pendingRecords.isNotEmpty()) {
                    dao.updateSyncStatus(pendingRecords, SYNC_STATUS_SYNCED)
                }
            }
        } catch (e: Exception) {
            Log.e("DeltaManager", "Error marking records as synced", e)
        }
    }

    suspend fun markRecordsAsFailed(deviceId: String, count: Int) {
        try {
            withContext(Dispatchers.IO) {
                val pendingRecords = dao.getRecordsByStatus(SYNC_STATUS_PENDING)
                    .take(count)
                    .map { it.id }
                if (pendingRecords.isNotEmpty()) {
                    dao.updateSyncStatus(pendingRecords, SYNC_STATUS_FAILED)
                }
            }
        } catch (e: Exception) {
            Log.e("DeltaManager", "Error marking records as failed", e)
        }
    }

    suspend fun cleanupOldRecords(olderThanDays: Int = 7) {
        try {
            withContext(Dispatchers.IO) {
                val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
                dao.deleteOldRecords(cutoffTime)
            }
        } catch (e: Exception) {
            Log.e("DeltaManager", "Error cleaning up old records", e)
        }
    }
}