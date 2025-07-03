package com.example.hoarder.data

import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.models.BufferedPayload
import com.example.hoarder.data.storage.db.TelemetryDatabase
import com.example.hoarder.data.uploader.NetworkUploader
import com.example.hoarder.transport.buffer.DataBuffer
import com.example.hoarder.transport.buffer.UploadLogger
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.transport.queue.UploadQueue
import com.example.hoarder.transport.queue.UploadScheduler
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DataUploader(
    private val ctx: Context,
    private val h: Handler
) {
    private val ua = AtomicBoolean(false)
    private var ip = ""
    private var port = 5000
    private val uploadQueue = UploadQueue()
    private val lastUploadedMap = AtomicReference<Map<String, Any>?>(null)
    private val g: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type
    private val tb = AtomicLong(0L)
    private var ls: String? = null
    private var lm2: Pair<String, String>? = null
    private var lt = 0L
    private val cd = 5000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = TelemetryDatabase.getDatabase(ctx)
    private val logDao = db.logDao()

    private val dataBuffer = DataBuffer(logDao, g)
    private val uploadLogger = UploadLogger(logDao)
    private val networkUploader = NetworkUploader(ctx)
    private val scheduler = UploadScheduler(h, ::processQueue)

    private fun processQueue() {
        if (ua.get() && ip.isNotBlank() && port > 0) {
            val (dataToProcessString, isForcedUpload) = uploadQueue.getNextItem()
            if (dataToProcessString != null) {
                val currentDataMap: Map<String, Any> = g.fromJson(dataToProcessString, mapType)

                if (isForcedUpload) {
                    lastUploadedMap.set(null)
                    processUpload(dataToProcessString, false, dataToProcessString)
                    return
                }

                val previousDataMap = lastUploadedMap.get()

                if (previousDataMap == null) {
                    if (processUpload(dataToProcessString, false, dataToProcessString)) {
                        lastUploadedMap.set(currentDataMap)
                    }
                    return
                }

                val deltaMap = mutableMapOf<String, Any>()
                currentDataMap["id"]?.let { deltaMap["id"] = it }

                for ((key, value) in currentDataMap) {
                    if (previousDataMap[key] != value) {
                        deltaMap[key] = value
                    }
                }

                if (deltaMap.size <= 1 && deltaMap.containsKey("id")) {
                    val bufferSize = dataBuffer.getBufferedDataSize()
                    h.post {
                        notifyStatus("No Change", "Data unchanged, skipping upload.", tb.get(), bufferSize)
                    }
                } else {
                    val deltaJson = g.toJson(deltaMap)
                    if (processUpload(deltaJson, true, dataToProcessString)) {
                        lastUploadedMap.set(currentDataMap)
                    }
                }
            }
        } else if (ua.get()) {
            h.post {
                notifyStatus("Error", "Server IP or Port became invalid.", tb.get(), 0L)
            }
            ua.set(false)
        }
    }

    fun setServer(ip: String, port: Int) {
        synchronized(this) {
            this.ip = ip
            this.port = port
        }
    }

    fun hasValidServer(): Boolean {
        synchronized(this) {
            return ip.isNotBlank() && port > 0
        }
    }

    fun queueData(data: String) {
        uploadQueue.queueRegular(data)
    }

    fun queueForcedData(data: String) {
        uploadQueue.queueForced(data)
    }

    fun start() {
        ua.set(true)
        lastUploadedMap.set(null)
        uploadQueue.clear()
        scope.launch {
            val bufferSize = dataBuffer.getBufferedDataSize()
            h.post {
                notifyStatus("Connecting", "Attempting to connect...", tb.get(), bufferSize)
            }
        }
        scheduler.start()
    }

    fun stop() {
        ua.set(false)
        uploadQueue.clear()
        scheduler.stop()
    }

    fun resetCounter() {
        tb.set(0L)
        lastUploadedMap.set(null)
        uploadQueue.clear()
        scope.launch {
            val bufferSize = dataBuffer.getBufferedDataSize()
            h.post {
                notifyStatus("Paused", "Upload paused.", tb.get(), bufferSize)
            }
        }
    }

    fun getUploadedBytes(): Long = tb.get()

    fun forceSendBuffer() {
        if (ua.get() && ip.isNotBlank() && port > 0) {
            scope.launch {
                val bufferedPayloads = dataBuffer.getBufferedData()
                if (bufferedPayloads.isNotEmpty()) {
                    processBatchUpload(bufferedPayloads)
                }
            }
        }
    }

    fun cleanup() {
        scheduler.cleanup()
        scope.cancel()
    }

    private fun processUpload(jsonToUpload: String, isDelta: Boolean, fullFrameJson: String): Boolean {
        if (!NetUtils.isNetworkAvailable(ctx)) {
            dataBuffer.saveToBuffer(fullFrameJson)
            uploadLogger.addErrorLog("Internet not accessible")
            val bufferSize = dataBuffer.getBufferedDataSize()
            h.post {
                notifyStatus("Saving Locally", "Internet not accessible. Data saved locally.", tb.get(), bufferSize)
            }
            return false
        }

        val result = networkUploader.uploadSingle(jsonToUpload, isDelta, ip, port)

        if (result.success) {
            tb.addAndGet(result.uploadedBytes)
            uploadLogger.addSuccessLog(jsonToUpload, result.uploadedBytes)
        } else {
            dataBuffer.saveToBuffer(fullFrameJson)
            result.errorMessage?.let { uploadLogger.addErrorLog(it) }
        }

        val bufferSize = dataBuffer.getBufferedDataSize()
        h.post {
            if (result.success) {
                val statusText = when {
                    isDelta -> "OK (Delta)"
                    !isDelta && lastUploadedMap.get() == null -> "OK (Full Init)"
                    else -> "OK (Full)"
                }
                notifyStatus(statusText, "Uploaded successfully.", tb.get(), bufferSize, result.uploadedBytes)
            } else {
                notifyStatus(result.statusMessage, result.errorMessage ?: "Upload failed", tb.get(), bufferSize)
            }
        }
        return result.success
    }

    private fun processBatchUpload(batchPayloads: List<BufferedPayload>) {
        val batchStrings = batchPayloads.map { it.payload }
        val batchJson = batchStrings.joinToString(separator = ",", prefix = "[", postfix = "]")
        val result = networkUploader.uploadBatch(batchStrings, ip, port)

        if (result.success) {
            tb.addAndGet(result.uploadedBytes)
            uploadLogger.addBatchSuccessLog(batchJson, result.uploadedBytes)
            dataBuffer.clearBuffer(batchPayloads)
        } else {
            result.errorMessage?.let { uploadLogger.addErrorLog(it) }
        }

        val bufferSize = dataBuffer.getBufferedDataSize()
        h.post {
            if (result.success) {
                notifyStatus("OK (Batch)", "Buffered data uploaded successfully.", tb.get(), bufferSize, result.uploadedBytes)
            } else {
                notifyStatus(result.statusMessage, result.errorMessage ?: "Batch upload failed", tb.get(), bufferSize)
            }
        }
    }

    fun notifyStatus(s: String, m: String, ub: Long, bufferSize: Long, lastUploadSize: Long? = null) {
        val cm2 = Pair(s, m)
        val ct = System.currentTimeMillis()
        var sf = true

        if (s == "Network Error" && ip.isNotBlank() && m.contains(ip)) {
            if (ls == "Network Error" && lm2?.first == "Network Error" && lm2?.second?.contains(ip) == true && ct - lt < cd) sf = false
        }

        val cc = (ls != s || lm2 != cm2)
        if (sf && cc) {
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("status", s)
                putExtra("message", m)
                putExtra("totalUploadedBytes", ub)
                putExtra("bufferedDataSize", bufferSize)
                lastUploadSize?.let { putExtra("lastUploadSizeBytes", it) }
            })
            ls = s; lm2 = cm2
            if (s == "Network Error" && ip.isNotBlank() && m.contains(ip)) lt = ct
        } else if (!sf && s == "Network Error" || ls == s && lm2 == cm2) {
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply {
                putExtra("totalUploadedBytes", ub)
                putExtra("bufferedDataSize", bufferSize)
                lastUploadSize?.let { putExtra("lastUploadSizeBytes", it) }
            })
        }
    }
}