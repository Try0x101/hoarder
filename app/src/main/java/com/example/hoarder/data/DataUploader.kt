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
import com.example.hoarder.data.processing.DeltaComputer
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.data.storage.db.TelemetryDatabase
import com.example.hoarder.data.uploader.NetworkUploader
import com.example.hoarder.data.uploader.handler.BulkUploadHandler
import com.example.hoarder.data.uploader.handler.UploadHandler
import com.example.hoarder.power.PowerManager
import com.example.hoarder.transport.buffer.DataBuffer
import com.example.hoarder.transport.buffer.UploadLogger
import com.example.hoarder.transport.network.NetUtils
import com.example.hoarder.transport.queue.UploadScheduler
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
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

    private val bulkUploadHandler by lazy {
        BulkUploadHandler(ctx, appPrefs, networkUploader, dataBuffer, logDao, bulkUploadInProgress,
            { status, message -> notifyStatusDebounced(status, message, tb.get(), actualNetworkBytes.get(), bufferedSize.get()) },
            { success -> onUploadAttemptFinished(success) }
        )
    }

    private val uploadHandler by lazy {
        UploadHandler(g, sp, networkUploader, dataBuffer, uploadLogger, tb, actualNetworkBytes, lastProcessedMap)
    }

    private fun handleReconnect() {
        if (reconnectInProgress.getAndSet(true)) return
        scope.launch {
            notifyStatus("Syncing", "Connection restored...", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
            var success = false
            for (delayMs in listOf(1000L, 3000L, 5000L)) {
                if (flushBuffer()) { success = true; break }
                delay(delayMs)
            }
            onUploadAttemptFinished(success)
            reconnectInProgress.set(false)
        }
    }

    private suspend fun onUploadAttemptFinished(success: Boolean) {
        val finalSize = dataBuffer.getBufferedDataSize()
        bufferedSize.set(finalSize)
        if (success) {
            notifyStatus("Synced", "Buffered data sent.", tb.get(), actualNetworkBytes.get(), finalSize)
        } else {
            notifyStatus("Sync Failed", "Could not send buffered data.", tb.get(), actualNetworkBytes.get(), finalSize)
        }
    }

    private suspend fun flushBuffer(): Boolean {
        if (!ua.get() || !hasValidServer() || bulkUploadInProgress.get()) return true
        val currentBufferSize = dataBuffer.getBufferedDataSize()
        if (currentBufferSize == 0L) return true

        val recordCount = dataBuffer.getBufferedPayloadsCount()
        val bulkThresholdBytes = appPrefs.getBulkUploadThresholdKb() * 1024L
        return if (currentBufferSize >= bulkThresholdBytes || recordCount > HOT_PATH_RECORD_LIMIT) {
            bulkUploadHandler.initiateBulkUploadProcess()
        } else {
            val success = uploadHandler.processHotPathUpload(dataBuffer.getBufferedData(), ip, port, compressionLevel)
            if (success) {
                if (wasOffline.getAndSet(false)) handleReconnect()
                bufferedSize.set(dataBuffer.getBufferedDataSize())
                notifyStatusDebounced("OK (Batch)", "Batch uploaded", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
            } else {
                wasOffline.set(true)
            }
            success
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (wasOffline.getAndSet(false)) handleReconnect() }
        override fun onLost(network: Network) { wasOffline.set(true) }
    }

    private val batchTimeoutRunnable = Runnable { scope.launch { if (isTriggerByTimeoutEnabled && dataBuffer.getBufferedPayloadsCount() > 0) forceSendBuffer() } }

    init {
        scope.launch {
            bufferedSize.set(dataBuffer.getBufferedDataSize())
            if (appPrefs.getBulkJobId() != null) bulkUploadHandler.resumeBulkUploadProcess()
        }
        updateBatchingConfiguration()
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

    fun queueData(data: String) {
        scope.launch {
            if (isBatchingEnabled || !NetUtils.isNetworkAvailable(ctx)) {
                if (saveToBuffer(data) && isBatchingEnabled) {
                    val bufferedCount = dataBuffer.getBufferedPayloadsCount()
                    val currentBufferedSizeKb = bufferedSize.get() / 1024
                    if (bufferedCount == 1 && isTriggerByTimeoutEnabled) withContext(Dispatchers.Main) { h.postDelayed(batchTimeoutRunnable, batchTimeoutMillis) }
                    if ((isTriggerByCountEnabled && bufferedCount >= batchRecordCountThreshold) || (isTriggerByMaxSizeEnabled && currentBufferedSizeKb >= batchMaxSizeKiloBytes)) {
                        forceSendBuffer()
                    }
                }
            } else {
                val success = uploadHandler.processSingleUpload(data, ip, port, compressionLevel)
                if (success) {
                    if (wasOffline.getAndSet(false)) handleReconnect()
                    notifyStatusDebounced("OK (Delta)", "Uploaded", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
                } else {
                    wasOffline.set(true)
                    saveToBuffer(data)
                }
            }
        }
    }

    private suspend fun saveToBuffer(fullJson: String): Boolean {
        val dbFile = ctx.getDatabasePath("telemetry_database")
        if (dbFile.exists() && dbFile.length() >= DB_SIZE_LIMIT_BYTES) {
            notifyStatusDebounced("Storage Full", "DB limit reached", tb.get(), actualNetworkBytes.get(), bufferedSize.get())
            return false
        }
        try {
            val currentMap: Map<String, Any> = g.fromJson(fullJson, mapType)
            val previousMap = lastProcessedMap.get()
            val deltaMap = if (previousMap != null) DeltaComputer.calculateDelta(previousMap, currentMap) else currentMap
            lastProcessedMap.set(currentMap)
            if (deltaMap.isEmpty()) return false
            val deltaWithId = deltaMap.toMutableMap().apply { put("i", currentMap["i"] ?: "unknown") }
            val earliestBase = dataBuffer.getEarliestTimestamp()
            if (earliestBase == null) deltaWithId["bts"] = System.currentTimeMillis() / 1000
            else deltaWithId["tso"] = (System.currentTimeMillis() / 1000) - earliestBase
            dataBuffer.saveToBuffer(g.toJson(deltaWithId))
            val newSize = dataBuffer.getBufferedDataSize()
            bufferedSize.set(newSize)
            notifyStatusDebounced("Saving Locally", "Data buffered", tb.get(), actualNetworkBytes.get(), newSize)
            return true
        } catch (e: Exception) { return false }
    }

    private fun notifyStatusDebounced(s: String, m: String, ub: Long, anb: Long, bufferSize: Long) {
        val currentTime = System.currentTimeMillis()
        if ("$s|$m" != lastStatusMessage || (currentTime - lastStatusTime) > STATUS_UPDATE_DEBOUNCE_MS) {
            lastStatusTime = currentTime; lastStatusMessage = "$s|$m"
            notifyStatus(s, m, ub, anb, bufferSize)
        }
    }

    fun notifyStatus(s: String, m: String, ub: Long, anb: Long, bufferSize: Long) {
        val intent = Intent("com.example.hoarder.UPLOAD_STATUS").apply {
            putExtra("status", s); putExtra("message", m)
            putExtra("totalUploadedBytes", ub); putExtra("totalActualNetworkBytes", anb)
            putExtra("bufferedDataSize", bufferSize); putExtra("bulkInProgress", bulkUploadInProgress.get())
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    fun setServer(ip: String, port: Int) { this.ip = ip; this.port = port }
    fun hasValidServer(): Boolean = ip.isNotBlank() && port > 0
    fun start() { ua.set(true); updateBatchingConfiguration(); if (networkCallbackRegistered.compareAndSet(false, true)) connectivityManager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback); scheduler.start(); h.post { notifyStatus("Connecting", "...", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) } }
    fun stop() { ua.set(false); scheduler.stop(); h.removeCallbacks(batchTimeoutRunnable); if (networkCallbackRegistered.compareAndSet(true, false)) try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {} }
    fun resetCounter() { tb.set(0L); actualNetworkBytes.set(0L); lastProcessedMap.set(null); sp.edit().putLong("totalUploadedBytes", 0L).putLong("totalActualNetworkBytes", 0L).apply(); h.post { notifyStatus("Paused", "Upload paused", tb.get(), actualNetworkBytes.get(), bufferedSize.get()) } }
    fun forceSendBuffer() { h.removeCallbacks(batchTimeoutRunnable); scope.launch { flushBuffer() } }
    fun cleanup() { scheduler.cleanup(); stop() }

    fun updateBatchSettings(enabled: Boolean, recordCount: Int, byCount: Boolean, timeoutSec: Int, byTimeout: Boolean, maxSizeKb: Int, byMaxSize: Boolean, compLevel: Int) {
        h.removeCallbacks(batchTimeoutRunnable)
        isBatchingEnabled = enabled; batchRecordCountThreshold = recordCount; isTriggerByCountEnabled = byCount
        batchTimeoutMillis = timeoutSec * 1000L; isTriggerByTimeoutEnabled = byTimeout
        batchMaxSizeKiloBytes = maxSizeKb; isTriggerByMaxSizeEnabled = byMaxSize; compressionLevel = compLevel
    }
}