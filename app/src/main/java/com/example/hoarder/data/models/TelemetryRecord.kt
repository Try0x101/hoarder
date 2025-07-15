package com.example.hoarder.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telemetry_records")
data class TelemetryRecord(
    @PrimaryKey val id: String,
    val deviceModel: String,
    val timestamp: Long,
    val batteryPercentage: Int,
    val batteryCapacity: Int?,
    val latitude: Double,
    val longitude: Double,
    val altitude: Int,
    val accuracy: Int,
    val speed: Int,
    val networkOperator: String,
    val networkType: String,
    val cellId: String,
    val trackingAreaCode: String,
    val mobileCountryCode: String,
    val mobileNetworkCode: String,
    val signalStrength: String,
    val wifiBssid: String,
    val downloadSpeed: String,
    val uploadSpeed: String,
    val lastModified: Long = System.currentTimeMillis(),
    val syncStatus: String = "PENDING"
)

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val message: String,
    val sizeBytes: Long? = null,
    val actualNetworkBytes: Long? = null
)

@Entity(tableName = "buffered_payloads")
data class BufferedPayload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val payload: String
)