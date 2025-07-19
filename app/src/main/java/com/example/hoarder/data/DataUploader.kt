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
import com.example.hoarder.data.uploader.UploadResult
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
import kotlin.math.pow

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
    private val wasOffline = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val bulkUploadInProgress = AtomicBoolean(false)

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

    private val isBatchingEnabled = AtomicBoolean(false)
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
        private const val KEY_LIVE_BATCH_TIMESTAMP = "liveBatchBaseTimestamp"
        private const val HOT_PATH_RECORD_LIMIT = 1000
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (wasOffline.getAndSet(false)) {
                // Network is back, but we don't auto-send. The scheduler or user will trigger it.
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            wasOffline.set(true)
        }
    }

    private val batchTimeoutRunnable = Runnable {
        scope.launch {
            if (isTriggerByTimeoutEnabled && dataBuffer.getBufferedPayloadsCount() > 0) {
                forceSendBuffer()
            }
        }
    }

    init {
        scope.launch {
            bufferedSize.set(dataBuffer.getBufferedDataSize())
            // Removed liveBatchBaseTimestamp initialization

            if (appPrefs.getBulkJobId() != null) {
                resumeBulkUploadProcess()
            }
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

    fun queueData(data: String) {
        if (isBatchingEnabled.get()) {
            handleBatchingQueue(data)
        } else {
            processSingleUpload(data)
        }
    }

    private fun processSingleUpload(fullJson: String) {
        scope.launch {
            if (!ua.get() || !hasValidServer()) {
                handleUploadFailure(fullJson, g.fromJson(fullJson, mapType))
                return@launch
            }

            val currentDataMap: Map<String, Any> = g.fromJson(fullJson, mapType)
            val previousDataMap = lastProcessedMap.get()
            val deltaMap = if (previousDataMap == null) currentDataMap else DeltaComputer.calculateDelta(previousDataMap, currentDataMap)

            lastProcessedMap.set(currentDataMap)

            if (deltaMap.isEmpty()) {
                h.post { notifyStatusDebounced("No Change", "Data unchanged, skipping upload.", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
                return@launch
            }

            val deltaJson = g.toJson(deltaMap)
            val isDelta = previousDataMap != null

            if (!NetUtils.isNetworkAvailable(ctx)) {
                wasOffline.set(true)
                handleUploadFailure(fullJson, currentDataMap)
                uploadLogger.addErrorLog("Internet not accessible")
                h.post { notifyStatusDebounced("Saving Locally", "Internet not accessible. Data saved locally.", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
                return@launch
            }

            val result = networkUploader.uploadSingle(deltaJson, isDelta, ip, port, compressionLevel)

            if (result.success) {
                handleUploadSuccess(deltaJson, fullJson, currentDataMap, result.uploadedBytes, result.actualNetworkBytes, isDelta)
            } else {
                handleUploadFailure(fullJson, currentDataMap)
                result.errorMessage?.let { uploadLogger.addErrorLog(it) }
                h.post { notifyStatusDebounced(result.statusMessage, result.errorMessage ?: "Upload failed", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
            }
        }
    }

    private fun handleUploadSuccess(uploadJson: String, fullJson: String, currentMap: Map<String, Any>, uploadedBytes: Long, networkBytes: Long, isDelta: Boolean) {
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

    private suspend fun handleUploadFailure(fullJsonForBuffer: String, currentMap: Map<String, Any>) {
        val payloadToBuffer = fullJsonForBuffer
        dataBuffer.saveToBuffer(payloadToBuffer)
        bufferedSize.set(dataBuffer.getBufferedDataSize())
        h.post {
            notifyStatusDebounced("Saving Locally", "Data added to local buffer.", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
        }
    }

    private fun handleBatchingQueue(data: String) {
        scope.launch {
            val currentMap: Map<String, Any> = g.fromJson(data, mapType)
            val previousMap = lastProcessedMap.get()
            val deltaMap = if (previousMap != null) DeltaComputer.calculateDelta(previousMap, currentMap) else currentMap

            if (deltaMap.isNotEmpty()) {
                val deltaMapWithTimestamp: MutableMap<String, Any> = deltaMap.toMutableMap()

                // Get buffer *before* adding new record
                val bufferedData = withContext(Dispatchers.IO) { dataBuffer.getBufferedData() }
                // Find base timestamp (bts) from earliest already-buffered record
                val earliestBase = bufferedData
                    .asSequence()
                    .mapNotNull { it.payload }
                    .mapNotNull { payload ->
                        try {
                            val map = g.fromJson(payload, Map::class.java)
                            (map["bts"] as? Double)?.toLong() ?: (map["bts"] as? Long)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .minOrNull()

                if (earliestBase == null) {
                    // If no anchor exists, this is a new batch anchor
                    val baseTimestamp = System.currentTimeMillis() / 1000
                    deltaMapWithTimestamp["bts"] = baseTimestamp
                } else {
                    // Calculate tso relative to true anchor
                    val offset = (System.currentTimeMillis() / 1000) - earliestBase
                    deltaMapWithTimestamp["tso"] = offset
                }

                val deltaJson = g.toJson(deltaMapWithTimestamp)
                dataBuffer.saveToBuffer(deltaJson)
                lastProcessedMap.set(currentMap)

                bufferedSize.set(dataBuffer.getBufferedDataSize())
                h.post {
                    notifyStatusDebounced("Batching", "Data added to batch.", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
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

    private fun processHotPathUpload(batch: List<BufferedPayload>) {
        val batchJsonStrings = batch.map { it.payload }
        val result = networkUploader.uploadBatch(batchJsonStrings, ip, port, compressionLevel)

        if (result.success) {
            dataBuffer.clearBuffer(batch)
            tb.addAndGet(result.uploadedBytes)
            actualNetworkBytes.addAndGet(result.actualNetworkBytes)
            sp.edit()
                .putLong("totalUploadedBytes", tb.get())
                .putLong("totalActualNetworkBytes", actualNetworkBytes.get())
                .apply()

            uploadLogger.addBatchSuccessLog(batchJsonStrings, result.uploadedBytes, result.actualNetworkBytes)

            scope.launch {
                bufferedSize.set(dataBuffer.getBufferedDataSize())
                h.post {
                    notifyStatusDebounced("OK (Batch)", "Batch uploaded successfully", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), result.actualNetworkBytes)
                }
            }
        } else {
            wasOffline.set(!NetUtils.isNetworkAvailable(ctx))
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

        if (statusKey != lastStatusMessage || (currentTime - lastStatusTime) > STATUS_UPDATE_DEBOUNCE_MS) {
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
        scheduler.stop()
        cancelBatchTimeout()
        unregisterNetworkCallback()
    }

    fun resetCounter() {
        tb.set(0L)
        actualNetworkBytes.set(0L)
        lastProcessedMap.set(null)
        sp.edit()
            .putLong("totalUploadedBytes", 0L)
            .putLong("totalActualNetworkBytes", 0L)
            .remove("lastUploadedJson")
            .apply()
        h.post {
            notifyStatusDebounced("Paused", "Upload paused.", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
        }
    }

    fun forceSendBuffer() {
        cancelBatchTimeout()
        if (!ua.get() || !hasValidServer() || bulkUploadInProgress.get()) return

        scope.launch {
            val currentBufferSize = dataBuffer.getBufferedDataSize()
            if (currentBufferSize == 0L) return@launch

            val recordCount = dataBuffer.getBufferedPayloadsCount()
            val bulkThresholdBytes = appPrefs.getBulkUploadThresholdKb() * 1024L

            if (currentBufferSize >= bulkThresholdBytes || recordCount > HOT_PATH_RECORD_LIMIT) {
                initiateBulkUploadProcess()
            } else {
                val bufferedData = dataBuffer.getBufferedData()
                if (bufferedData.isNotEmpty()) {
                    processHotPathUpload(bufferedData)
                }
            }
        }
    }

    private suspend fun initiateBulkUploadProcess() {
        if (!bulkUploadInProgress.compareAndSet(false, true)) return

        try {
            withContext(Dispatchers.Main) {
                notifyStatus(
                    "Preparing",
                    "Preparing large upload...",
                    tb.get(),
                    actualNetworkBytes.get(),
                    bufferedSize.get()
                )
            }

            appPrefs.setBulkJobState("MARSHALLING")
            val tempFile = File(ctx.cacheDir, "bulk_upload_${System.currentTimeMillis()}.json.zlib")
            appPrefs.setBulkTempFilePath(tempFile.absolutePath)

            val success = try {
                marshallDataToFile(tempFile)
            } catch (e: Exception) {
                android.util.Log.e("DataUploader", "Bulk marshalling failed", e)
                false
            }
            if (!success) {
                cleanupBulkState(tempFile)
                return
            }

            withContext(Dispatchers.Main) {
                notifyStatus(
                    "Uploading",
                    "Uploading bulk file (${ByteFormatter.format(tempFile.length())})...",
                    tb.get(),
                    actualNetworkBytes.get(),
                    bufferedSize.get()
                )
            }
            appPrefs.setBulkJobState("UPLOADING")
            val result = withContext(Dispatchers.IO) {
                try {
                    networkUploader.uploadBulkFile(tempFile, ip, port)
                } catch (e: Exception) {
                    android.util.Log.e("DataUploader", "Bulk upload failed", e)
                    UploadResult(false, 0L, 0L, "Bulk Error", "Internal error: ${e.message}")
                }
            }

            if (result.success && result.jobId != null) {
                appPrefs.setBulkJobId(result.jobId)
                appPrefs.setBulkJobState("POLLING")
                tempFile.delete()
                appPrefs.setBulkTempFilePath(null)
                schedulePollingTask(0, 2000L)
            } else {
                cleanupBulkState(tempFile, isError = true, errorMessage = result.errorMessage)
            }
        } finally {
            // Ensure flag is always reset
            bulkUploadInProgress.set(false)
        }
    }

    private suspend fun marshallDataToFile(file: File): Boolean {
        return withContext(Dispatchers.IO) {
            var cursor: Cursor? = null
            try {
                DeflaterOutputStream(FileOutputStream(file), Deflater(compressionLevel, true)).use { deflaterStream ->
                    deflaterStream.write("[".toByteArray())
                    cursor = logDao.getAllPayloadsCursor()
                    var isFirst = true
                    if (cursor?.moveToFirst() == true) {
                        val payloadIndex = cursor!!.getColumnIndexOrThrow("payload")
                        do {
                            if (!isFirst) deflaterStream.write(",".toByteArray())
                            val payload = cursor!!.getString(payloadIndex)
                            deflaterStream.write(payload.toByteArray(Charsets.UTF_8))
                            isFirst = false
                        } while (cursor!!.moveToNext())
                    }
                    deflaterStream.write("]".toByteArray())
                }
                true
            } catch (e: Exception) {
                android.util.Log.e("DataUploader", "Exception during marshallDataToFile", e)
                false
            } finally {
                cursor?.close()
            }
        }
    }

    private fun schedulePollingTask(retries: Int, delay: Long) {
        h.postDelayed({
            scope.launch { pollJobStatus(retries, delay) }
        }, delay)
    }

    private suspend fun pollJobStatus(retries: Int, prevDelay: Long) {
        val jobId = appPrefs.getBulkJobId() ?: return cleanupBulkState()

        val status = withContext(Dispatchers.IO) { networkUploader.getJobStatus(jobId, ip, port) }

        when (status?.get("status")) {
            "COMPLETE" -> finalizeBulkSuccess()
            "PROCESSING" -> {
                val nextDelay = (prevDelay * 2).coerceAtMost(60000L)
                val details = status["details"] as? String ?: "Processing..."
                withContext(Dispatchers.Main) {
                    notifyStatus("Processing", details, tb.get(), actualNetworkBytes.get(), bufferedSize.get())
                }
                schedulePollingTask(retries + 1, nextDelay)
            }
            "FAILED" -> {
                val error = status["error"] as? String ?: "Unknown server error"
                cleanupBulkState(isError = true, errorMessage = "Bulk upload failed on server: $error")
            }
            else -> {
                if (retries < 5) {
                    schedulePollingTask(retries + 1, prevDelay)
                } else {
                    cleanupBulkState(isError = true, errorMessage = "Polling failed after multiple retries.")
                }
            }
        }
    }

    private suspend fun finalizeBulkSuccess() {
        val records = withContext(Dispatchers.IO) { logDao.getAllPayloads() }
        withContext(Dispatchers.IO) { dataBuffer.clearBuffer(records) }
        cleanupBulkState()
        withContext(Dispatchers.Main) {
            notifyStatus("OK (Bulk)", "Bulk upload complete.", tb.get(), actualNetworkBytes.get(), 0L)
        }
    }

    private fun cleanupBulkState(file: File? = null, isError: Boolean = false, errorMessage: String? = null) {
        appPrefs.setBulkJobId(null)
        appPrefs.setBulkJobState("IDLE")
        val tempFilePath = appPrefs.getBulkTempFilePath()
        if (tempFilePath != null) {
            File(tempFilePath).delete()
        }
        file?.delete()
        appPrefs.setBulkTempFilePath(null)
        bulkUploadInProgress.set(false)
        if (isError) {
            h.post { notifyStatusDebounced("Error", errorMessage ?: "Bulk upload failed.", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) }
        }
        scope.launch { bufferedSize.set(dataBuffer.getBufferedDataSize()) }
    }

    private suspend fun resumeBulkUploadProcess() {
        val state = appPrefs.getBulkJobState()
        val jobId = appPrefs.getBulkJobId()
        val filePath = appPrefs.getBulkTempFilePath()

        when (state) {
            "POLLING" -> if (jobId != null) {
                bulkUploadInProgress.set(true)
                schedulePollingTask(0, 2000L)
            } else cleanupBulkState()
            "UPLOADING", "MARSHALLING" -> {
                bulkUploadInProgress.set(false) // Reset to allow restart
                if (filePath != null) File(filePath).delete()
                initiateBulkUploadProcess()
            }
            else -> cleanupBulkState()
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
            putExtra("bulkInProgress", bulkUploadInProgress.get())
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

        scope.launch {
            val needsReschedule = dataBuffer.getBufferedPayloadsCount() > 0
            withContext(Dispatchers.Main) {
                cancelBatchTimeout()
                if (needsReschedule && isTriggerByTimeoutEnabled) {
                    h.postDelayed(batchTimeoutRunnable, batchTimeoutMillis)
                }
            }
        }
    }
}