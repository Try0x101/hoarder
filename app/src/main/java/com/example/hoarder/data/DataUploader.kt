package com.example.hoarder.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private val powerManager: PowerManager,
    private val appPrefs: Prefs
) {
    private val ua = AtomicBoolean(false)
    private var ip = ""
    private var port = 5000
    private val uploadQueue = UploadQueue()
    private val lastUploadedMap = AtomicReference<Map<String, Any>?>(null)
    private val lastProcessedMap = AtomicReference<Map<String, Any>?>(null)
    private val isOffline = AtomicBoolean(false)
    private val offlineSessionBaseTimestamp = AtomicReference<Long?>(null)
    private val wasOffline = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)

    private val g: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val mapType = object : TypeToken<MutableMap<String, Any>>() {}.type
    private val tb = AtomicLong(sp.getLong("totalUploadedBytes", 0L))
    private val actualNetworkBytes = AtomicLong(sp.getLong("totalActualNetworkBytes", 0L))
    private val bufferedSize = AtomicLong(0L)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = TelemetryDatabase.getDatabase(ctx)
    private val logDao = db.logDao()

    private val dataBuffer = DataBuffer(logDao, g)
    private val uploadLogger = UploadLogger(logDao)
    private val networkUploader = NetworkUploader(ctx)
    private val scheduler = UploadScheduler(ctx, h, ::processQueue)

    private val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val isBatchingEnabled = AtomicBoolean(false)
    private var batchRecordCountThreshold = 20
    private var isTriggerByCountEnabled = true
    private var batchTimeoutMillis = 60000L
    private var isTriggerByTimeoutEnabled = true
    private var batchMaxSizeKiloBytes = 100
    private var isTriggerByMaxSizeEnabled = true
    private var compressionLevel = 6

    private val liveBatchBaseTimestamp = AtomicReference<Long?>(null)

    private var lastStatusTime = 0L
    private var lastStatusMessage = ""
    private val STATUS_UPDATE_DEBOUNCE_MS = 2000L

    companion object {
        private const val KEY_LIVE_BATCH_TIMESTAMP = "liveBatchBaseTimestamp"
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (wasOffline.getAndSet(false)) {
                h.post {
                    checkAutoSendBuffer()
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            wasOffline.set(true)
        }
    }

    private val batchTimeoutRunnable = Runnable {
        if (isTriggerByTimeoutEnabled && dataBuffer.getBufferedPayloadsCount() > 0) {
            forceSendBuffer()
        }
    }

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
            liveBatchBaseTimestamp.set(sp.getLong(KEY_LIVE_BATCH_TIMESTAMP, 0L).takeIf { it != 0L })
        }
        updateBatchingConfiguration()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered.compareAndSet(false, true)) {
            try {
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            } catch (e: Exception) {
                networkCallbackRegistered.set(false)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        if (networkCallbackRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) { }
        }
    }

    private fun updateBatchingConfiguration() {
        isBatchingEnabled.set(appPrefs.isBatchUploadEnabled())
        batchRecordCountThreshold = appPrefs.getBatchRecordCount()
        isTriggerByCountEnabled = appPrefs.isBatchTriggerByCountEnabled()
        batchTimeoutMillis = appPrefs.getBatchTimeout() * 1000L
        isTriggerByTimeoutEnabled = appPrefs.isBatchTriggerByTimeoutEnabled()
        batchMaxSizeKiloBytes = appPrefs.getBatchMaxSizeKb()
        isTriggerByMaxSizeEnabled = appPrefs.isBatchTriggerByMaxSizeEnabled()
        compressionLevel = appPrefs.getCompressionLevel()
    }

    private fun scheduleBatchTimeout() {
        cancelBatchTimeout()
        if(isTriggerByTimeoutEnabled) {
            h.postDelayed(batchTimeoutRunnable, batchTimeoutMillis)
        }
    }

    private fun cancelBatchTimeout() {
        h.removeCallbacks(batchTimeoutRunnable)
    }

    private fun processQueue() {
        if (isBatchingEnabled.get()) return

        if (!ua.get() || !hasValidServer()) {
            if (ua.get()) {
                h.post { notifyStatusDebounced("Error", "Server IP or Port became invalid.", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
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
            lastProcessedMap.set(currentDataMap)
            h.post { notifyStatusDebounced("No Change", "Data unchanged, skipping upload.", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
        } else {
            val deltaJson = g.toJson(deltaMap)
            processUpload(deltaJson, dataToProcessString, true)
        }
    }

    private fun processUpload(uploadJson: String, fullJsonForBuffer: String, isDelta: Boolean) {
        val currentMapState = g.fromJson<Map<String, Any>>(fullJsonForBuffer, mapType)

        if (!NetUtils.isNetworkAvailable(ctx)) {
            wasOffline.set(true)
            handleUploadFailure(uploadJson, fullJsonForBuffer, currentMapState)
            uploadLogger.addErrorLog("Internet not accessible")
            h.post { notifyStatusDebounced("Saving Locally", "Internet not accessible. Data saved locally.", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
            return
        }

        val result = networkUploader.uploadSingle(uploadJson, isDelta, ip, port, compressionLevel)

        if (result.success) {
            handleUploadSuccess(uploadJson, fullJsonForBuffer, currentMapState, result.uploadedBytes, result.actualNetworkBytes, isDelta)
        } else {
            handleUploadFailure(uploadJson, fullJsonForBuffer, currentMapState)
            result.errorMessage?.let { uploadLogger.addErrorLog(it) }
            h.post { notifyStatusDebounced(result.statusMessage, result.errorMessage ?: "Upload failed", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
        }
    }

    private fun checkAutoSendBuffer() {
        if (!ua.get() || !hasValidServer()) return

        scope.launch {
            val currentBufferSize = dataBuffer.getBufferedDataSize()
            val warningThresholdBytes = appPrefs.getBufferWarningThresholdKb() * 1024L

            if (currentBufferSize > 0 && currentBufferSize < warningThresholdBytes) {
                h.post {
                    notifyStatusDebounced("Auto-sending", "Automatically sending buffered data.", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
                    forceSendBuffer()
                }
            }
        }
    }

    private fun handleUploadSuccess(uploadJson: String, fullJson: String, currentMap: Map<String, Any>, uploadedBytes: Long, networkBytes: Long, isDelta: Boolean) {
        lastUploadedMap.set(currentMap)
        lastProcessedMap.set(currentMap)
        sp.edit().putString("lastUploadedJson", fullJson).apply()
        isOffline.set(false)
        offlineSessionBaseTimestamp.set(null)

        tb.addAndGet(uploadedBytes)
        actualNetworkBytes.addAndGet(networkBytes)
        sp.edit()
            .putLong("totalUploadedBytes", tb.get())
            .putLong("totalActualNetworkBytes", actualNetworkBytes.get())
            .apply()

        uploadLogger.addSuccessLog(uploadJson, uploadedBytes, networkBytes)

        val statusText = when {
            isDelta -> "OK (Delta)"
            else -> "OK (Full)"
        }
        h.post { notifyStatusDebounced(statusText, "Uploaded successfully.", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), networkBytes) }
    }

    private fun handleUploadFailure(uploadJson: String, fullJsonForBuffer: String, currentMap: Map<String, Any>) {
        val payloadToBuffer: String

        if (!isOffline.getAndSet(true)) {
            val baseTimestamp = System.currentTimeMillis() / 1000
            offlineSessionBaseTimestamp.set(baseTimestamp)
            val map: MutableMap<String, Any> = g.fromJson(fullJsonForBuffer, mapType)
            map["bts"] = baseTimestamp
            payloadToBuffer = g.toJson(map)
        } else {
            val baseTs = offlineSessionBaseTimestamp.get() ?: (System.currentTimeMillis() / 1000).also { offlineSessionBaseTimestamp.set(it) }
            val offset = (System.currentTimeMillis() / 1000) - baseTs
            val map: MutableMap<String, Any> = g.fromJson(uploadJson, mapType)
            map["tso"] = offset
            payloadToBuffer = g.toJson(map)
        }

        dataBuffer.saveToBuffer(payloadToBuffer)
        scope.launch {
            bufferedSize.set(dataBuffer.getBufferedDataSize())
            h.post {
                notifyStatusDebounced("Saving Locally", "Data added to local buffer.", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
            }
        }
        lastProcessedMap.set(currentMap)
    }

    fun queueData(data: String) {
        if (isBatchingEnabled.get()) {
            handleBatchingQueue(data)
        } else {
            uploadQueue.queueRegular(data)
        }
    }

    private fun handleBatchingQueue(data: String) {
        scope.launch {
            val currentMap: Map<String, Any> = g.fromJson(data, mapType)
            val previousMap = lastProcessedMap.get()
            val deltaMap = if (previousMap != null) DeltaComputer.calculateDelta(previousMap, currentMap) else currentMap

            if (deltaMap.isNotEmpty()) {
                val deltaMapWithTimestamp: MutableMap<String, Any> = deltaMap.toMutableMap()

                val currentBatchCount = dataBuffer.getBufferedPayloadsCount()
                if (currentBatchCount == 0) {
                    val baseTimestamp = System.currentTimeMillis() / 1000
                    liveBatchBaseTimestamp.set(baseTimestamp)
                    sp.edit().putLong(KEY_LIVE_BATCH_TIMESTAMP, baseTimestamp).apply()
                    deltaMapWithTimestamp["bts"] = baseTimestamp
                } else {
                    val baseTs = liveBatchBaseTimestamp.get()
                        ?: sp.getLong(KEY_LIVE_BATCH_TIMESTAMP, 0L).takeIf { it != 0L }
                        ?: (System.currentTimeMillis() / 1000).also {
                            liveBatchBaseTimestamp.set(it)
                            sp.edit().putLong(KEY_LIVE_BATCH_TIMESTAMP, it).apply()
                        }
                    val offset = (System.currentTimeMillis() / 1000) - baseTs
                    deltaMapWithTimestamp["tso"] = offset
                }

                val deltaJson = g.toJson(deltaMapWithTimestamp)
                dataBuffer.saveToBuffer(deltaJson)
                lastProcessedMap.set(currentMap)

                scope.launch {
                    bufferedSize.set(dataBuffer.getBufferedDataSize())
                    h.post {
                        notifyStatusDebounced("Batching", "Data added to batch.", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
                    }
                }
            } else {
                lastProcessedMap.set(currentMap)
            }

            val bufferedCount = dataBuffer.getBufferedPayloadsCount()
            val currentBufferedSizeKb = dataBuffer.getBufferedDataSize() / 1024

            val countTriggerMet = isTriggerByCountEnabled && bufferedCount >= batchRecordCountThreshold
            val sizeTriggerMet = isTriggerByMaxSizeEnabled && currentBufferedSizeKb >= batchMaxSizeKiloBytes

            if (countTriggerMet || sizeTriggerMet) {
                h.post { forceSendBuffer() }
            } else if (bufferedCount == 1) {
                h.post { scheduleBatchTimeout() }
            }
        }
    }

    private fun processBatchUpload(batch: List<BufferedPayload>) {
        val batchJsonStrings = batch.map { it.payload }
        val result = networkUploader.uploadBatch(batchJsonStrings, ip, port, compressionLevel)

        if (result.success) {
            dataBuffer.clearBuffer(batch)
            liveBatchBaseTimestamp.set(null)
            sp.edit().remove(KEY_LIVE_BATCH_TIMESTAMP).apply()

            bufferedSize.set(0L)

            tb.addAndGet(result.uploadedBytes)
            actualNetworkBytes.addAndGet(result.actualNetworkBytes)
            sp.edit()
                .putLong("totalUploadedBytes", tb.get())
                .putLong("totalActualNetworkBytes", actualNetworkBytes.get())
                .apply()

            uploadLogger.addBatchSuccessLog(batchJsonStrings, result.uploadedBytes, result.actualNetworkBytes)

            h.postDelayed({
                notifyStatus("Connected", "All data uploaded", tb.get(), actualNetworkBytes.get(), 0L, result.actualNetworkBytes)
            }, 100)
        } else {
            result.errorMessage?.let { uploadLogger.addErrorLog(it) }
            scope.launch {
                bufferedSize.set(dataBuffer.getBufferedDataSize())
                h.post {
                    notifyStatusDebounced(result.statusMessage, result.errorMessage ?: "Batch upload failed", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
                }
            }
        }
    }

    private fun notifyStatusDebounced(s: String, m: String, ub: Long, anb: Long, bufferSize: Long, lastUploadSize: Long? = null) {
        val currentTime = System.currentTimeMillis()
        val statusKey = "$s|$m"

        if (s == "OK (Batch)" || s == "Auto-sending" || statusKey != lastStatusMessage || (currentTime - lastStatusTime) > STATUS_UPDATE_DEBOUNCE_MS) {
            lastStatusTime = currentTime
            lastStatusMessage = statusKey
            notifyStatus(s, m, ub, anb, bufferSize, lastUploadSize)
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
        updateBatchingConfiguration()
        registerNetworkCallback()
        h.post {
            notifyStatusDebounced("Connecting", "Attempting to connect...", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
        }
        scheduler.start()
    }

    fun stop() {
        ua.set(false)
        uploadQueue.clear()
        scheduler.stop()
        cancelBatchTimeout()
        unregisterNetworkCallback()
    }

    fun resetCounter() {
        tb.set(0L)
        actualNetworkBytes.set(0L)
        lastUploadedMap.set(null)
        lastProcessedMap.set(null)
        isOffline.set(false)
        offlineSessionBaseTimestamp.set(null)
        uploadQueue.clear()
        sp.edit()
            .putLong("totalUploadedBytes", 0L)
            .putLong("totalActualNetworkBytes", 0L)
            .remove("lastUploadedJson")
            .apply()
        h.post {
            notifyStatusDebounced("Paused", "Upload paused.", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
        }
    }

    fun getUploadedBytes(): Long = tb.get()
    fun getActualNetworkBytes(): Long = actualNetworkBytes.get()

    fun forceSendBuffer() {
        cancelBatchTimeout()
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
        scheduler.cleanup()
        unregisterNetworkCallback()
        scope.launch { dataBuffer.cleanupOldData() }
    }

    fun notifyStatus(s: String, m: String, ub: Long, anb: Long, bufferSize: Long, lastUploadSize: Long? = null) {
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.hoarder.UPLOAD_STATUS").apply {
            putExtra("status", s)
            putExtra("message", m)
            putExtra("totalUploadedBytes", ub)
            putExtra("totalActualNetworkBytes", anb)
            putExtra("bufferedDataSize", bufferSize)
            lastUploadSize?.let { putExtra("lastUploadSizeBytes", it) }
        })
    }

    fun getBufferedDataSize(): Long = bufferedSize.get()

    fun updateBatchSettings(enabled: Boolean, recordCount: Int, byCount: Boolean, timeoutSec: Int, byTimeout: Boolean, maxSizeKb: Int, byMaxSize: Boolean, compLevel: Int) {
        isBatchingEnabled.set(enabled)
        batchRecordCountThreshold = recordCount
        isTriggerByCountEnabled = byCount
        batchTimeoutMillis = timeoutSec * 1000L
        isTriggerByTimeoutEnabled = byTimeout
        batchMaxSizeKiloBytes = maxSizeKb
        isTriggerByMaxSizeEnabled = byMaxSize
        compressionLevel = compLevel
    }
}