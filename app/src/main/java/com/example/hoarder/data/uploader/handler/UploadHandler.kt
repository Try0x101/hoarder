package com.example.hoarder.data.uploader.handler

import android.content.SharedPreferences
import com.example.hoarder.data.models.BufferedPayload
import com.example.hoarder.data.processing.DeltaComputer
import com.example.hoarder.data.uploader.NetworkUploader
import com.example.hoarder.transport.buffer.DataBuffer
import com.example.hoarder.transport.buffer.UploadLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class UploadHandler(
    private val g: Gson,
    private val sp: SharedPreferences,
    private val networkUploader: NetworkUploader,
    private val dataBuffer: DataBuffer,
    private val uploadLogger: UploadLogger,
    private val totalUploadedBytes: AtomicLong,
    private val totalActualNetworkBytes: AtomicLong,
    private val lastProcessedMap: AtomicReference<Map<String, Any>?>
) {
    private val mapType = object : TypeToken<MutableMap<String, Any>>() {}.type

    suspend fun processSingleUpload(fullJson: String, ip: String, port: Int, compLevel: Int): Boolean {
        val currentDataMap: Map<String, Any> = g.fromJson(fullJson, mapType)
        val previousDataMap = lastProcessedMap.get()
        val deltaMap = if (previousDataMap == null) currentDataMap else DeltaComputer.calculateDelta(previousDataMap, currentDataMap)
        lastProcessedMap.set(currentDataMap)
        if (deltaMap.isEmpty()) return true

        val result = networkUploader.uploadSingle(g.toJson(deltaMap), previousDataMap != null, ip, port, compLevel)

        if (result.success) {
            handleSuccessfulUpload(result.uploadedBytes, result.actualNetworkBytes)
            uploadLogger.addSuccessLog(g.toJson(deltaMap), result.uploadedBytes, result.actualNetworkBytes)
        }
        return result.success
    }

    suspend fun processHotPathUpload(batch: List<BufferedPayload>, ip: String, port: Int, compLevel: Int): Boolean {
        val result = networkUploader.uploadBatch(batch.map { it.payload }, ip, port, compLevel)

        if (result.success) {
            withContext(Dispatchers.IO) {
                dataBuffer.clearBuffer(batch)
                handleSuccessfulUpload(result.uploadedBytes, result.actualNetworkBytes)
                uploadLogger.addBatchSuccessLog(batch.map { it.payload }, result.uploadedBytes, result.actualNetworkBytes)
            }
        }
        return result.success
    }

    private fun handleSuccessfulUpload(uploaded: Long, actual: Long) {
        totalUploadedBytes.addAndGet(uploaded)
        totalActualNetworkBytes.addAndGet(actual)
        sp.edit()
            .putLong("totalUploadedBytes", totalUploadedBytes.get())
            .putLong("totalActualNetworkBytes", totalActualNetworkBytes.get())
            .apply()
    }
}