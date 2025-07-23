package com.example.hoarder.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.example.hoarder.data.batching.BatchingManager
import com.example.hoarder.data.notifiers.UploaderNotifier
import com.example.hoarder.data.processing.DeltaComputer
import com.example.hoarder.data.reconnect.NetworkConnectivityHandler
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.data.storage.db.TelemetryDatabase
import com.example.hoarder.data.uploader.NetworkUploader
import com.example.hoarder.data.uploader.handler.BulkUploadHandler
import com.example.hoarder.data.uploader.handler.UploadHandler
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
    private val appPrefs: Prefs
) {
    private val ua = AtomicBoolean(false)
    private var serverAddress = ""
    private val lastProcessedMap = AtomicReference<Map<String, Any>?>(null)
    private val bulkUploadInProgress = AtomicBoolean(false)
    private val reconnectInProgress = AtomicBoolean(false)

    private val g: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val mapType = object : TypeToken<MutableMap<String, Any>>() {}.type
    private val tb = AtomicLong(sp.getLong(Prefs.KEY_TOTAL_UPLOADED_BYTES, 0L))
    private val actualNetworkBytes = AtomicLong(sp.getLong(Prefs.KEY_TOTAL_ACTUAL_NETWORK_BYTES, 0L))
    private val bufferedSize = AtomicLong(0L)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = TelemetryDatabase.getDatabase(ctx)
    private val logDao = db.logDao()

    private val dataBuffer = DataBuffer(logDao, g)
    private val uploadLogger = UploadLogger(logDao)
    private val networkUploader = NetworkUploader(ctx)
    private val scheduler = UploadScheduler(ctx, h, ::forceSendBuffer)
    private val notifier = UploaderNotifier(ctx)
    private val connectivityHandler = NetworkConnectivityHandler(ctx, ::handleReconnect)
    private val batchingManager = BatchingManager(appPrefs, scope, ::forceSendBuffer)
    private var compressionLevel = 6

    companion object {
        private const val HOT_PATH_RECORD_LIMIT = 1000
        private const val DB_SIZE_LIMIT_BYTES = 256 * 1024 * 1024L
    }

    private val bulkUploadHandler by lazy {
        BulkUploadHandler(ctx, appPrefs, networkUploader, dataBuffer, logDao, bulkUploadInProgress,
            { status, message -> notifier.notifyStatusDebounced(status, message, tb.get(), actualNetworkBytes.get(), bufferedSize.get(), bulkUploadInProgress.get()) },
            { success -> onUploadAttemptFinished(success) }
        )
    }

    private val uploadHandler by lazy {
        UploadHandler(g, sp, networkUploader, dataBuffer, uploadLogger, tb, actualNetworkBytes, lastProcessedMap)
    }

    init {
        scope.launch {
            bufferedSize.set(dataBuffer.getBufferedDataSize())
            if (appPrefs.getBulkJobId() != null) bulkUploadHandler.resumeBulkUploadProcess()
        }
        reapplyBatchingConfiguration()
    }

    private fun reapplyBatchingConfiguration() {
        batchingManager.updateConfiguration()
        compressionLevel = appPrefs.getCompressionLevel()
        scope.launch {
            val count = dataBuffer.getBufferedPayloadsCount()
            if (count > 0 && batchingManager.isEnabled()) {
                batchingManager.checkTriggers(count, bufferedSize.get() / 1024)
            }
        }
    }

    fun queueData(data: String) {
        scope.launch {
            if (batchingManager.isEnabled() || !NetUtils.isNetworkAvailable(ctx)) {
                if (saveToBuffer(data)) {
                    batchingManager.checkTriggers(dataBuffer.getBufferedPayloadsCount(), bufferedSize.get() / 1024)
                }
            } else {
                val success = uploadHandler.processSingleUpload(data, serverAddress, compressionLevel)
                if (success) {
                    if (connectivityHandler.getWasOffline()) { handleReconnect() }
                    connectivityHandler.setWasOffline(false)
                    notifier.notifyStatusDebounced("OK (Delta)", "Uploaded", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), bulkUploadInProgress.get())
                } else {
                    connectivityHandler.setWasOffline(true)
                    saveToBuffer(data)
                }
            }
        }
    }

    private suspend fun saveToBuffer(fullJson: String): Boolean {
        val dbFile = ctx.getDatabasePath("telemetry_database")
        if (dbFile.exists() && dbFile.length() >= DB_SIZE_LIMIT_BYTES) {
            notifier.notifyStatusDebounced("Storage Full", "DB limit reached", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), bulkUploadInProgress.get())
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
            notifier.notifyStatusDebounced("Saving Locally", "Data buffered", tb.get(), actualNetworkBytes.get(), newSize, bulkUploadInProgress.get())
            return true
        } catch (e: Exception) { return false }
    }

    private fun handleReconnect() {
        if (reconnectInProgress.getAndSet(true)) return
        scope.launch {
            notifier.notifyStatus("Syncing", "Connection restored...", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), bulkUploadInProgress.get())
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
        val status = if (success) "Synced" else "Sync Failed"
        val message = if (success) "Buffered data sent." else "Could not send buffered data."
        notifier.notifyStatus(status, message, tb.get(), actualNetworkBytes.get(), finalSize, bulkUploadInProgress.get())
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
            val success = uploadHandler.processHotPathUpload(dataBuffer.getBufferedData(), serverAddress, compressionLevel)
            if (success) {
                if (connectivityHandler.getWasOffline()) { handleReconnect() }
                connectivityHandler.setWasOffline(false)
                bufferedSize.set(dataBuffer.getBufferedDataSize())
                notifier.notifyStatusDebounced("OK (Batch)", "Batch uploaded", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), bulkUploadInProgress.get())
            } else {
                connectivityHandler.setWasOffline(true)
            }
            success
        }
    }

    fun postStatusNotification(status: String, message: String) {
        notifier.notifyStatus(status, message, tb.get(), actualNetworkBytes.get(), bufferedSize.get(), bulkUploadInProgress.get())
    }

    fun setServer(address: String) { this.serverAddress = address }
    fun hasValidServer(): Boolean = serverAddress.isNotBlank()
    fun start() { ua.set(true); reapplyBatchingConfiguration(); connectivityHandler.start(); scheduler.start(); h.post { notifier.notifyStatus("Connecting", "...", tb.get(), actualNetworkBytes.get(), bufferedSize.get(), bulkUploadInProgress.get()) } }
    fun stop() { ua.set(false); scheduler.stop(); batchingManager.onForceSendBuffer(); connectivityHandler.stop() }
    fun resetCounter() { tb.set(0L); actualNetworkBytes.set(0L); lastProcessedMap.set(null); sp.edit().putLong(Prefs.KEY_TOTAL_UPLOADED_BYTES, 0L).putLong(Prefs.KEY_TOTAL_ACTUAL_NETWORK_BYTES, 0L).apply(); h.post { postStatusNotification("Paused", "Upload paused") } }
    fun forceSendBuffer() { batchingManager.onForceSendBuffer(); scope.launch { flushBuffer() } }
    fun cleanup() { scheduler.cleanup(); stop() }

    fun updateBatchSettings(enabled: Boolean, recordCount: Int, byCount: Boolean, timeoutSec: Int, byTimeout: Boolean, maxSizeKb: Int, byMaxSize: Boolean, compLevel: Int) {
        reapplyBatchingConfiguration()
    }
}