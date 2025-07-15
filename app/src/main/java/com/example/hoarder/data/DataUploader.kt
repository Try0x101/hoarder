package com.example.hoarder.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.models.BufferedPayload
import com.example.hoarder.data.processing.DeltaComputer
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.data.storage.db.TelemetryDatabase
import com.example.hoarder.data.uploader.NetworkUploader
import com.example.hoarder.power.PowerManager
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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DataUploader(
    private val ctx: Context,
    private val h: Handler,
    private val sp: SharedPreferences,
    private val powerManager: PowerManager
) {
    private val ua = AtomicBoolean(false)
    private var ip = ""
    private var port = 5000
    private val uploadQueue = UploadQueue()
    private val lastUploadedMap = AtomicReference<Map<String, Any>?>(null)
    private val lastProcessedMap = AtomicReference<Map<String, Any>?>(null)
    private val isOffline = AtomicBoolean(false)
    private val offlineSessionBaseTimestamp = AtomicReference<Long?>(null)

    private val g: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val mapType = object : TypeToken<MutableMap<String, Any>>() {}.type
    private val tb = AtomicLong(sp.getLong("totalUploadedBytes", 0L))
    private val bufferedSize = AtomicLong(0L)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = TelemetryDatabase.getDatabase(ctx)
    private val logDao = db.logDao()

    private val dataBuffer = DataBuffer(logDao, g)
    private val uploadLogger = UploadLogger(logDao)
    private val networkUploader = NetworkUploader(ctx)
    private val scheduler = UploadScheduler(h, ::processQueue)

    private val batchQueue = mutableListOf<String>()
    private var lastBatchTime = System.currentTimeMillis()

    init {
        scope.launch {
            bufferedSize.set(dataBuffer.getBufferedDataSize())
            val lastJson = sp.getString("lastUploadedJson", null)
            if (lastJson != null) {
                try {
                    val map: Map<String, Any> = g.fromJson(lastJson, mapType)
                    lastUploadedMap.set(map)
                    lastProcessedMap.set(map)
                } catch (e: Exception) {
                    lastUploadedMap.set(null)
                    lastProcessedMap.set(null)
                }
            }
        }
    }

    private fun processQueue() {
        if (!ua.get() || !hasValidServer()) {
            if (ua.get()) {
                h.post { notifyStatus("Error", "Server IP or Port became invalid.", tb.get(), bufferedSize.get()) }
                ua.set(false)
            }
            return
        }

        val (dataToProcessString, isForcedUpload) = uploadQueue.getNextItem()
        if (dataToProcessString == null) return

        if (isForcedUpload) {
            lastUploadedMap.set(null)
            lastProcessedMap.set(null)
            isOffline.set(false)
            offlineSessionBaseTimestamp.set(null)
            processUpload(dataToProcessString, dataToProcessString, false)
            return
        }

        val currentDataMap: Map<String, Any> = g.fromJson(dataToProcessString, mapType)
        val previousDataMap = lastProcessedMap.get()

        if (previousDataMap == null) {
            processUpload(dataToProcessString, dataToProcessString, false)
            return
        }

        val deltaMap = DeltaComputer.calculateDelta(previousDataMap, currentDataMap)
        if (deltaMap.isEmpty()) {
            h.post { notifyStatus("No Change", "Data unchanged, skipping upload.", tb.get(), bufferedSize.get()) }
        } else {
            val deltaJson = g.toJson(deltaMap)
            processUpload(deltaJson, dataToProcessString, true)
        }
    }

    private fun processUpload(uploadJson: String, fullJsonForBuffer: String, isDelta: Boolean) {
        val currentMapState = g.fromJson<Map<String, Any>>(fullJsonForBuffer, mapType)

        if (!NetUtils.isNetworkAvailable(ctx)) {
            handleUploadFailure(uploadJson, fullJsonForBuffer, currentMapState)
            uploadLogger.addErrorLog("Internet not accessible")
            h.post { notifyStatus("Saving Locally", "Internet not accessible. Data saved locally.", tb.get(), bufferedSize.get()) }
            return
        }

        val result = networkUploader.uploadSingle(uploadJson, isDelta, ip, port)

        if (result.success) {
            handleUploadSuccess(uploadJson, fullJsonForBuffer, currentMapState, result.uploadedBytes, isDelta)
        } else {
            handleUploadFailure(uploadJson, fullJsonForBuffer, currentMapState)
            result.errorMessage?.let { uploadLogger.addErrorLog(it) }
            h.post { notifyStatus(result.statusMessage, result.errorMessage ?: "Upload failed", tb.get(), bufferedSize.get()) }
        }
    }

    private fun handleUploadSuccess(uploadJson: String, fullJson: String, currentMap: Map<String, Any>, uploadedBytes: Long, isDelta: Boolean) {
        lastUploadedMap.set(currentMap)
        lastProcessedMap.set(currentMap)
        sp.edit().putString("lastUploadedJson", fullJson).apply()
        isOffline.set(false)
        offlineSessionBaseTimestamp.set(null)

        tb.addAndGet(uploadedBytes)
        sp.edit().putLong("totalUploadedBytes", tb.get()).apply()
        uploadLogger.addSuccessLog(uploadJson, uploadedBytes)

        val statusText = when {
            isDelta -> "OK (Delta)"
            else -> "OK (Full)"
        }
        h.post { notifyStatus(statusText, "Uploaded successfully.", tb.get(), bufferedSize.get(), uploadedBytes) }
    }

    private fun handleUploadFailure(uploadJson: String, fullJson: String, currentMap: Map<String, Any>) {
        val payloadToBuffer = if (!isOffline.getAndSet(true)) {
            val baseTimestamp = System.currentTimeMillis() / 1000
            offlineSessionBaseTimestamp.set(baseTimestamp)
            val map: MutableMap<String, Any> = g.fromJson(fullJson, mapType)
            map["bts"] = baseTimestamp
            g.toJson(map)
        } else {
            val baseTs = offlineSessionBaseTimestamp.get() ?: (System.currentTimeMillis() / 1000).also { offlineSessionBaseTimestamp.set(it) }
            val offset = (System.currentTimeMillis() / 1000) - baseTs
            val map: MutableMap<String, Any> = g.fromJson(uploadJson, mapType)
            map["tso"] = offset
            g.toJson(map)
        }
        dataBuffer.saveToBuffer(payloadToBuffer)
        bufferedSize.addAndGet(payloadToBuffer.toByteArray().size.toLong())
        lastProcessedMap.set(currentMap)
    }

    private fun getAdaptiveBatchThreshold(): Int {
        val state = powerManager.powerState.value
        return when {
            state.mode == Prefs.POWER_MODE_CONTINUOUS -> 1
            state.isMoving -> 2
            else -> 5
        }
    }

    private fun getAdaptiveBatchTimeout(): Long {
        val state = powerManager.powerState.value
        return when {
            state.mode == Prefs.POWER_MODE_CONTINUOUS -> 1000L
            state.isMoving -> 120000L
            else -> 300000L
        }
    }

    fun queueData(data: String) {
        val threshold = getAdaptiveBatchThreshold()
        val timeout = getAdaptiveBatchTimeout()
        val currentTime = System.currentTimeMillis()

        if (powerManager.powerState.value.mode == Prefs.POWER_MODE_CONTINUOUS) {
            uploadQueue.queueRegular(data)
        } else {
            synchronized(batchQueue) {
                batchQueue.add(data)
                val timeoutReached = (currentTime - lastBatchTime) > timeout

                if (batchQueue.size >= threshold || timeoutReached) {
                    flushBatchQueue()
                    lastBatchTime = currentTime
                }
            }
        }
    }

    fun flushBatchQueue() {
        val batchToSend: List<String>
        synchronized(batchQueue) {
            if (batchQueue.isEmpty()) return
            batchToSend = batchQueue.toList()
            batchQueue.clear()
        }
        scheduler.submitOneTimeTask { processBatch(batchToSend) }
    }

    private fun processBatch(batch: List<String>) {
        if (batch.isEmpty() || !NetUtils.isNetworkAvailable(ctx)) return
        val result = networkUploader.uploadBatch(batch, ip, port)

        if (result.success) {
            tb.addAndGet(result.uploadedBytes)
            sp.edit().putLong("totalUploadedBytes", tb.get()).apply()
            uploadLogger.addBatchSuccessLog(batch, result.uploadedBytes)
            h.post {
                notifyStatus("OK (Batch)", "Buffered data uploaded successfully.", tb.get(), bufferedSize.get(), result.uploadedBytes)
            }
        } else {
            result.errorMessage?.let { uploadLogger.addErrorLog(it) }
            batch.forEach { jsonRecord ->
                val mapState: Map<String, Any> = g.fromJson(jsonRecord, mapType)
                handleUploadFailure(jsonRecord, jsonRecord, mapState)
            }
            h.post {
                notifyStatus(result.statusMessage, result.errorMessage ?: "Batch upload failed, buffering records.", tb.get(), bufferedSize.get())
            }
        }
    }

    private fun processBatchUpload(batch: List<BufferedPayload>) {
        val batchJsonStrings = batch.map { it.payload }
        val result = networkUploader.uploadBatch(batchJsonStrings, ip, port)

        if (result.success) {
            tb.addAndGet(result.uploadedBytes)
            sp.edit().putLong("totalUploadedBytes", tb.get()).apply()
            uploadLogger.addBatchSuccessLog(batchJsonStrings, result.uploadedBytes)
            dataBuffer.clearBuffer(batch)

            scope.launch {
                var replayedState: Map<String, Any>? = lastUploadedMap.get()
                batch.forEach { bufferedPayload ->
                    try {
                        val map: Map<String, Any> = g.fromJson(bufferedPayload.payload, mapType)
                        replayedState = replayedState?.let { base ->
                            val mutableBase = base.toMutableMap()
                            mutableBase.putAll(map)
                            mutableBase
                        } ?: map
                    } catch (e: Exception) { }
                }

                replayedState?.let { finalState ->
                    val finalStateJson = g.toJson(finalState)
                    lastUploadedMap.set(finalState)
                    lastProcessedMap.set(finalState)
                    sp.edit().putString("lastUploadedJson", finalStateJson).apply()
                }

                bufferedSize.set(dataBuffer.getBufferedDataSize())
                h.post {
                    notifyStatus("OK (Batch)", "Buffered data uploaded successfully.", tb.get(), bufferedSize.get(), result.uploadedBytes)
                }
            }
        } else {
            result.errorMessage?.let { uploadLogger.addErrorLog(it) }
            h.post {
                notifyStatus(result.statusMessage, result.errorMessage ?: "Batch upload failed", tb.get(), bufferedSize.get())
            }
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

    fun queueForcedData(data: String) {
        uploadQueue.queueForced(data)
    }

    fun start() {
        ua.set(true)
        h.post {
            notifyStatus("Connecting", "Attempting to connect...", tb.get(), bufferedSize.get())
        }
        scheduler.start()
    }

    fun stop() {
        ua.set(false)
        flushBatchQueue()
        uploadQueue.clear()
        scheduler.stop()
    }

    fun resetCounter() {
        tb.set(0L)
        lastUploadedMap.set(null)
        lastProcessedMap.set(null)
        isOffline.set(false)
        offlineSessionBaseTimestamp.set(null)
        uploadQueue.clear()
        synchronized(batchQueue) { batchQueue.clear() }
        sp.edit().putLong("totalUploadedBytes", 0L).remove("lastUploadedJson").apply()
        h.post {
            notifyStatus("Paused", "Upload paused.", tb.get(), bufferedSize.get())
        }
    }

    fun getUploadedBytes(): Long = tb.get()

    fun forceSendBuffer() {
        if (ua.get() && hasValidServer()) {
            scheduler.submitOneTimeTask {
                val bufferedData = dataBuffer.getBufferedData()
                if (bufferedData.isNotEmpty()) {
                    processBatchUpload(bufferedData)
                }
            }
        }
    }

    fun cleanup() {
        flushBatchQueue()
        scheduler.cleanup()
        scope.launch { dataBuffer.cleanupOldData() }
    }

    fun notifyStatus(s: String, m: String, ub: Long, bufferSize: Long, lastUploadSize: Long? = null) {
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply {
            putExtra("status", s)
            putExtra("message", m)
            putExtra("totalUploadedBytes", ub)
            putExtra("bufferedDataSize", bufferSize)
            lastUploadSize?.let { putExtra("lastUploadSizeBytes", it) }
        })
    }

    fun getBufferedDataSize(): Long = bufferedSize.get()
}