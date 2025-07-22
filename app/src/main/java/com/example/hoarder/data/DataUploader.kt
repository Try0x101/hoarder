package com.example.hoarder.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
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
import com.example.hoarder.transport.queue.UploadScheduler
import com.example.hoarder.ui.formatters.ByteFormatter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

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
    private val lastProcessedMap = AtomicReference<Map<String, Any>?>(null)
    private val wasOffline = AtomicBoolean(true)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val bulkUploadInProgress = AtomicBoolean(false)
    private val reconnectInProgress = AtomicBoolean(false)

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
    private val scheduler = UploadScheduler(ctx, h, ::forceSendBuffer)

    private val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var isBatchingEnabled = true
    private var batchRecordCountThreshold = 20
    private var isTriggerByCountEnabled = true
    private var batchTimeoutMillis = 60000L
    private var isTriggerByTimeoutEnabled = true
    private var batchMaxSizeKiloBytes = 100
    private var isTriggerByMaxSizeEnabled = true
    private var compressionLevel = 6

    private var lastStatusTime = 0L
    private var lastStatusMessage = ""
    private val STATUS_UPDATE_DEBOUNCE_MS = 2000L

    companion object {
        private const val HOT_PATH_RECORD_LIMIT = 1000
        private const val DB_SIZE_LIMIT_BYTES = 256 * 1024 * 1024L
    }

    private fun handleReconnect() {
        if (reconnectInProgress.getAndSet(true)) return

        scope.launch {
            withContext(Dispatchers.Main) { notifyStatus("Syncing", "Connection restored, sending buffer...", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
            val delays = listOf(1000L, 3000L, 5000L)
            var success = false
            for (delayMs in delays) {
                if (flushBuffer()) {
                    success = true; break
                }
                delay(delayMs)
            }
            val finalSize = withContext(Dispatchers.IO) { dataBuffer.getBufferedDataSize() }
            bufferedSize.set(finalSize)
            withContext(Dispatchers.Main) {
                if (success) notifyStatus("Synced", "Buffered data sent.", tb.get(), actualNetworkBytes.get(), finalSize)
                else notifyStatus("Sync Failed", "Could not send buffered data.", tb.get(), actualNetworkBytes.get(), finalSize)
            }
            reconnectInProgress.set(false)
        }
    }

    private suspend fun flushBuffer(): Boolean {
        if (!ua.get() || !hasValidServer() || bulkUploadInProgress.get()) return true
        val currentBufferSize = withContext(Dispatchers.IO) { dataBuffer.getBufferedDataSize() }
        if (currentBufferSize == 0L) return true
        val recordCount = withContext(Dispatchers.IO) { dataBuffer.getBufferedPayloadsCount() }
        val bulkThresholdBytes = appPrefs.getBulkUploadThresholdKb() * 1024L
        return if (currentBufferSize >= bulkThresholdBytes || recordCount > HOT_PATH_RECORD_LIMIT) {
            initiateBulkUploadProcess()
        } else {
            val bufferedData = withContext(Dispatchers.IO) { dataBuffer.getBufferedData() }
            if (bufferedData.isNotEmpty()) processHotPathUpload(bufferedData) else true
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (wasOffline.getAndSet(false)) handleReconnect()
        }
        override fun onLost(network: Network) {
            super.onLost(network)
            wasOffline.set(true)
        }
    }

    private val batchTimeoutRunnable = Runnable {
        scope.launch {
            if (isTriggerByTimeoutEnabled && withContext(Dispatchers.IO) { dataBuffer.getBufferedPayloadsCount() } > 0) forceSendBuffer()
        }
    }

    init {
        scope.launch {
            bufferedSize.set(withContext(Dispatchers.IO) { dataBuffer.getBufferedDataSize() })
            if (appPrefs.getBulkJobId() != null) resumeBulkUploadProcess()
        }
        updateBatchingConfiguration()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered.compareAndSet(false, true)) {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun unregisterNetworkCallback() {
        if (networkCallbackRegistered.compareAndSet(true, false)) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        }
    }

    private fun updateBatchingConfiguration() {
        isBatchingEnabled = appPrefs.isBatchUploadEnabled()
        batchRecordCountThreshold = appPrefs.getBatchRecordCount()
        isTriggerByCountEnabled = appPrefs.isBatchTriggerByCountEnabled()
        batchTimeoutMillis = appPrefs.getBatchTimeout() * 1000L
        isTriggerByTimeoutEnabled = appPrefs.isBatchTriggerByTimeoutEnabled()
        batchMaxSizeKiloBytes = appPrefs.getBatchMaxSizeKb()
        isTriggerByMaxSizeEnabled = appPrefs.isBatchTriggerByMaxSizeEnabled()
        compressionLevel = appPrefs.getCompressionLevel()
    }

    private fun scheduleBatchTimeout() { h.postDelayed(batchTimeoutRunnable, batchTimeoutMillis) }
    private fun cancelBatchTimeout() { h.removeCallbacks(batchTimeoutRunnable) }

    fun queueData(data: String) {
        scope.launch {
            if (isBatchingEnabled || !NetUtils.isNetworkAvailable(ctx)) {
                if (saveToBuffer(data) && isBatchingEnabled) {
                    val bufferedCount = withContext(Dispatchers.IO) { dataBuffer.getBufferedPayloadsCount() }
                    val currentBufferedSizeKb = bufferedSize.get() / 1024
                    if (bufferedCount == 1 && isTriggerByTimeoutEnabled) withContext(Dispatchers.Main) { scheduleBatchTimeout() }
                    if ((isTriggerByCountEnabled && bufferedCount >= batchRecordCountThreshold) || (isTriggerByMaxSizeEnabled && currentBufferedSizeKb >= batchMaxSizeKiloBytes)) {
                        forceSendBuffer()
                    }
                }
            } else {
                processSingleUpload(data)
            }
        }
    }

    private suspend fun saveToBuffer(fullJson: String): Boolean {
        val dbFile = ctx.getDatabasePath("telemetry_database")
        if (dbFile.exists() && dbFile.length() >= DB_SIZE_LIMIT_BYTES) {
            withContext(Dispatchers.Main) { notifyStatusDebounced("Storage Full", "DB limit reached", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
            return false
        }
        try {
            val currentMap: Map<String, Any> = g.fromJson(fullJson, mapType)
            val previousMap = lastProcessedMap.get()
            val deltaMap = if (previousMap != null) DeltaComputer.calculateDelta(previousMap, currentMap) else currentMap
            lastProcessedMap.set(currentMap)
            if (deltaMap.isEmpty()) return false
            val deltaWithId = deltaMap.toMutableMap().apply { put("i", currentMap["i"] ?: "unknown") }
            val earliestBase = withContext(Dispatchers.IO) { dataBuffer.getEarliestTimestamp() }
            if (earliestBase == null) deltaWithId["bts"] = System.currentTimeMillis() / 1000
            else deltaWithId["tso"] = (System.currentTimeMillis() / 1000) - earliestBase
            withContext(Dispatchers.IO) { dataBuffer.saveToBuffer(g.toJson(deltaWithId)) }
            val newSize = withContext(Dispatchers.IO) { dataBuffer.getBufferedDataSize() }
            bufferedSize.set(newSize)
            withContext(Dispatchers.Main) { notifyStatusDebounced("Saving Locally", "Data buffered", tb.get(), actualNetworkBytes.get(), newSize) }
            return true
        } catch (e: Exception) { return false }
    }

    private suspend fun processSingleUpload(fullJson: String) {
        if (!ua.get() || !hasValidServer()) { saveToBuffer(fullJson); return }
        val currentDataMap: Map<String, Any> = g.fromJson(fullJson, mapType)
        val previousDataMap = lastProcessedMap.get()
        val deltaMap = if (previousDataMap == null) currentDataMap else DeltaComputer.calculateDelta(previousDataMap, currentDataMap)
        lastProcessedMap.set(currentDataMap)
        if (deltaMap.isEmpty()) return
        val result = networkUploader.uploadSingle(g.toJson(deltaMap), previousDataMap != null, ip, port, compressionLevel)
        if (result.success) {
            if (wasOffline.getAndSet(false)) handleReconnect()
            tb.addAndGet(result.uploadedBytes); actualNetworkBytes.addAndGet(result.actualNetworkBytes)
            sp.edit().putLong("totalUploadedBytes", tb.get()).putLong("totalActualNetworkBytes", actualNetworkBytes.get()).apply()
            uploadLogger.addSuccessLog(g.toJson(deltaMap), result.uploadedBytes, result.actualNetworkBytes)
            withContext(Dispatchers.Main) { notifyStatusDebounced("OK (Delta)", "Uploaded", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), result.actualNetworkBytes) }
        } else {
            wasOffline.set(true)
            saveToBuffer(fullJson)
        }
    }

    private suspend fun processHotPathUpload(batch: List<BufferedPayload>): Boolean {
        val result = networkUploader.uploadBatch(batch.map { it.payload }, ip, port, compressionLevel)
        if (result.success) {
            if (wasOffline.getAndSet(false)) handleReconnect()
            withContext(Dispatchers.IO) {
                dataBuffer.clearBuffer(batch)
                tb.addAndGet(result.uploadedBytes); actualNetworkBytes.addAndGet(result.actualNetworkBytes)
                sp.edit().putLong("totalUploadedBytes", tb.get()).putLong("totalActualNetworkBytes", actualNetworkBytes.get()).apply()
                uploadLogger.addBatchSuccessLog(batch.map { it.payload }, result.uploadedBytes, result.actualNetworkBytes)
                val newSize = dataBuffer.getBufferedDataSize()
                bufferedSize.set(newSize)
                withContext(Dispatchers.Main) { notifyStatusDebounced("OK (Batch)", "Batch uploaded", tb.get(), actualNetworkBytes.get(), newSize, result.actualNetworkBytes) }
            }
        } else {
            wasOffline.set(true)
        }
        return result.success
    }

    private fun notifyStatusDebounced(s: String, m: String, ub: Long, anb: Long, bufferSize: Long, lastUploadSize: Long? = null) {
        val currentTime = System.currentTimeMillis()
        if ("$s|$m" != lastStatusMessage || (currentTime - lastStatusTime) > STATUS_UPDATE_DEBOUNCE_MS) {
            lastStatusTime = currentTime; lastStatusMessage = "$s|$m"
            notifyStatus(s, m, ub, anb, bufferSize, lastUploadSize)
        }
    }

    fun setServer(ip: String, port: Int) { this.ip = ip; this.port = port }
    fun hasValidServer(): Boolean = ip.isNotBlank() && port > 0

    fun start() { ua.set(true); updateBatchingConfiguration(); registerNetworkCallback(); scheduler.start(); h.post { notifyStatus("Connecting", "...", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) } }
    fun stop() { ua.set(false); scheduler.stop(); cancelBatchTimeout(); unregisterNetworkCallback() }
    fun resetCounter() {
        tb.set(0L); actualNetworkBytes.set(0L); lastProcessedMap.set(null)
        sp.edit().putLong("totalUploadedBytes", 0L).putLong("totalActualNetworkBytes", 0L).apply()
        h.post { notifyStatus("Paused", "Upload paused", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
    }

    fun forceSendBuffer() { cancelBatchTimeout(); scope.launch { flushBuffer() } }

    private suspend fun initiateBulkUploadProcess(): Boolean {
        if (!bulkUploadInProgress.compareAndSet(false, true)) return false
        var success = false
        try {
            withContext(Dispatchers.Main) { notifyStatus("Preparing", "Bulk upload...", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
            appPrefs.setBulkJobState("MARSHALLING")
            val tempFile = File(ctx.cacheDir, "bulk_upload_${System.currentTimeMillis()}.json.zlib")
            if (!marshallDataToFile(tempFile)) { cleanupBulkState(tempFile); return false }
            withContext(Dispatchers.Main) { notifyStatus("Uploading", "Bulk file...", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
            appPrefs.setBulkJobState("UPLOADING")
            val result = networkUploader.uploadBulkFile(tempFile, ip, port)
            if (result.success && result.jobId != null) {
                appPrefs.setBulkJobId(result.jobId); appPrefs.setBulkJobState("POLLING")
                tempFile.delete()
                success = pollJobStatusLoop(result.jobId)
            } else {
                cleanupBulkState(tempFile, isError = true, errorMessage = result.errorMessage)
            }
        } finally { bulkUploadInProgress.set(false) }
        return success
    }

    private suspend fun pollJobStatusLoop(jobId: String): Boolean {
        for (delayMs in listOf(2000L, 4000L, 8000L, 15000L, 30000L, 60000L)) {
            val status = networkUploader.getJobStatus(jobId, ip, port)
            when (status?.get("status")) {
                "COMPLETE" -> { finalizeBulkSuccess(); return true }
                "FAILED" -> { cleanupBulkState(isError = true, errorMessage = status["error"] as? String); return false }
                else -> { withContext(Dispatchers.Main) { notifyStatus("Processing", status?.get("details") as? String ?: "...", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }; delay(delayMs) }
            }
        }
        cleanupBulkState(isError = true, errorMessage = "Polling timed out.")
        return false
    }

    private suspend fun marshallDataToFile(file: File): Boolean = withContext(Dispatchers.IO) {
        var cursor: Cursor? = null
        try {
            DeflaterOutputStream(FileOutputStream(file), Deflater(compressionLevel, true)).use { deflaterStream ->
                cursor = logDao.getAllPayloadsCursor()
                deflaterStream.write("[".toByteArray())
                var isFirst = true
                if (cursor?.moveToFirst() == true) {
                    val payloadIndex = cursor!!.getColumnIndexOrThrow("payload")
                    do {
                        if (!isFirst) deflaterStream.write(",".toByteArray())
                        deflaterStream.write(cursor!!.getString(payloadIndex).toByteArray(Charsets.UTF_8))
                        isFirst = false
                    } while (cursor!!.moveToNext())
                }
                deflaterStream.write("]".toByteArray())
            }
            true
        } catch (e: Exception) { false } finally { cursor?.close() }
    }

    private suspend fun finalizeBulkSuccess() {
        val records = withContext(Dispatchers.IO) { logDao.getAllPayloads() }
        withContext(Dispatchers.IO) { dataBuffer.clearBuffer(records) }
        cleanupBulkState()
        withContext(Dispatchers.Main) { notifyStatus("OK (Bulk)", "Bulk upload complete", tb.get(), actualNetworkBytes.get(), 0L) }
    }

    private fun cleanupBulkState(file: File? = null, isError: Boolean = false, errorMessage: String? = null) {
        appPrefs.setBulkJobId(null); appPrefs.setBulkJobState("IDLE"); file?.delete()
        if (isError) h.post { notifyStatus("Error", errorMessage ?: "Bulk upload failed", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
        scope.launch { bufferedSize.set(withContext(Dispatchers.IO) { dataBuffer.getBufferedDataSize() }) }
    }

    private suspend fun resumeBulkUploadProcess() {
        val jobId = appPrefs.getBulkJobId()
        if (appPrefs.getBulkJobState() == "POLLING" && jobId != null) {
            bulkUploadInProgress.set(true); pollJobStatusLoop(jobId); bulkUploadInProgress.set(false)
        } else {
            initiateBulkUploadProcess()
        }
    }

    fun cleanup() { scheduler.cleanup(); unregisterNetworkCallback(); scope.launch { dataBuffer.cleanupOldData() } }

    fun notifyStatus(s: String, m: String, ub: Long, anb: Long, bufferSize: Long, lastUploadSize: Long? = null) {
        val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
            putExtra("status", s); putExtra("message", m)
            putExtra("totalUploadedBytes", ub); putExtra("totalActualNetworkBytes", anb)
            putExtra("bufferedDataSize", bufferSize); putExtra("bulkInProgress", bulkUploadInProgress.get())
            lastUploadSize?.let { putExtra("lastUploadSizeBytes", it) }
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    fun updateBatchSettings(enabled: Boolean, recordCount: Int, byCount: Boolean, timeoutSec: Int, byTimeout: Boolean, maxSizeKb: Int, byMaxSize: Boolean, compLevel: Int) {
        isBatchingEnabled = enabled; batchRecordCountThreshold = recordCount; isTriggerByCountEnabled = byCount
        batchTimeoutMillis = timeoutSec * 1000L; isTriggerByTimeoutEnabled = byTimeout
        batchMaxSizeKiloBytes = maxSizeKb; isTriggerByMaxSizeEnabled = byMaxSize; compressionLevel = compLevel
    }
}