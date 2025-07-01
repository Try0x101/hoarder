package com.example.hoarder.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.hoarder.data.uploader.BufferManager
import com.example.hoarder.data.uploader.NetworkUploader
import com.example.hoarder.utils.NetUtils
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DataUploader(
    private val ctx: Context,
    private val h: Handler,
    private val sp: SharedPreferences
) {
    private val ua = AtomicBoolean(false)
    private var ip = ""
    private var port = 5000
    private val ld = AtomicReference<String?>(null)
    private val lu = AtomicReference<String?>(null)
    private val g = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val tb = AtomicLong(sp.getLong("totalUploadedBytes", 0L))
    private var ls: String? = null
    private var lm2: Pair<String, String>? = null
    private var lt = 0L
    private val cd = 5000L

    private val bufferManager = BufferManager(ctx, sp)
    private val networkUploader = NetworkUploader(ctx)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentUploadTask: Future<*>? = null

    private val ur = object : Runnable {
        override fun run() {
            if (ua.get() && ip.isNotBlank() && port > 0) {
                val dataToProcess = ld.getAndSet(null)
                if (dataToProcess != null) {
                    currentUploadTask?.cancel(false)
                    currentUploadTask = executor.submit {
                        val (d, delta) = generateJson(dataToProcess)
                        if (d != null) {
                            lu.set(dataToProcess)
                            processUpload(d, delta)
                        } else {
                            h.post {
                                notifyStatus("No Change", "Data unchanged, skipping upload.", tb.get(), bufferManager.getBufferedDataSize())
                            }
                        }
                    }
                }
                if (ua.get()) h.postDelayed(this, 1000L)
            } else if (ua.get() && (ip.isBlank() || port <= 0)) {
                h.post {
                    notifyStatus("Error", "Server IP or Port became invalid.", tb.get(), bufferManager.getBufferedDataSize())
                }
                ua.set(false)
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

    fun queueData(data: String) {
        ld.set(data)
    }

    fun start() {
        h.removeCallbacks(ur)
        lu.set(null)
        ua.set(true)
        h.post {
            notifyStatus("Connecting", "Attempting to connect...", tb.get(), bufferManager.getBufferedDataSize())
        }
        h.post(ur)
    }

    fun stop() {
        ua.set(false)
        h.removeCallbacks(ur)
        currentUploadTask?.cancel(true)
    }

    fun resetCounter() {
        tb.set(0L)
        lu.set(null)
        sp.edit().putLong("totalUploadedBytes", 0L).apply()
        h.post {
            notifyStatus("Paused", "Upload paused.", tb.get(), bufferManager.getBufferedDataSize())
        }
    }

    fun getUploadedBytes(): Long {
        return tb.get()
    }

    fun forceSendBuffer() {
        if (ua.get() && ip.isNotBlank() && port > 0) {
            executor.submit {
                val bufferedData = bufferManager.getBufferedData()
                if (bufferedData.isNotEmpty()) {
                    processBatchUpload(bufferedData)
                }
            }
        }
    }

    fun cleanup() {
        stop()
        executor.shutdown()
    }

    private fun processUpload(jsonString: String, isDelta: Boolean) {
        if (!NetUtils.isNetworkAvailable(ctx)) {
            bufferManager.saveToBuffer(jsonString)
            bufferManager.addErrorLog("Internet not accessible")
            h.post {
                notifyStatus("Saving Locally", "Internet not accessible. Delta saved locally.", tb.get(), bufferManager.getBufferedDataSize())
            }
            return
        }

        val result = networkUploader.uploadSingle(jsonString, isDelta, ip, port)

        h.post {
            if (result.success) {
                tb.addAndGet(result.uploadedBytes)
                sp.edit().putLong("totalUploadedBytes", tb.get()).apply()
                bufferManager.addUploadRecord(result.uploadedBytes)
                bufferManager.addSuccessLog(jsonString, result.uploadedBytes)
                notifyStatus(
                    if (isDelta) "OK (Delta)" else "OK (Full)",
                    "Uploaded successfully.",
                    tb.get(),
                    bufferManager.getBufferedDataSize(),
                    result.uploadedBytes
                )
            } else {
                bufferManager.saveToBuffer(jsonString)
                result.errorMessage?.let { bufferManager.addErrorLog(it) }
                notifyStatus(result.statusMessage, result.errorMessage ?: "Upload failed", tb.get(), bufferManager.getBufferedDataSize())
            }
        }
    }

    private fun processBatchUpload(batch: List<String>) {
        val result = networkUploader.uploadBatch(batch, ip, port)

        h.post {
            if (result.success) {
                tb.addAndGet(result.uploadedBytes)
                sp.edit().putLong("totalUploadedBytes", tb.get()).apply()
                bufferManager.addUploadRecord(result.uploadedBytes)
                bufferManager.addSuccessLog("Batch upload of ${batch.size} records", result.uploadedBytes)
                bufferManager.saveLastUploadDetails(batch)
                bufferManager.clearBuffer(batch)
                notifyStatus("OK (Batch)", "Buffered data uploaded successfully.", tb.get(), bufferManager.getBufferedDataSize(), result.uploadedBytes)
            } else {
                result.errorMessage?.let { bufferManager.addErrorLog(it) }
                notifyStatus(result.statusMessage, result.errorMessage ?: "Batch upload failed", tb.get(), bufferManager.getBufferedDataSize())
            }
        }
    }

    private fun generateJson(currentFrame: String): Pair<String?, Boolean> {
        val lastUpload = lu.get() ?: return Pair(currentFrame, false)

        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val previous = g.fromJson<Map<String, Any?>>(lastUpload, type)
            val current = g.fromJson<Map<String, Any?>>(currentFrame, type)
            val delta = mutableMapOf<String, Any?>()

            for ((key, value) in current.entries) {
                if (!previous.containsKey(key) || previous[key] != value) {
                    delta[key] = value
                }
            }

            if (current.containsKey("id")) delta["id"] = current["id"]
            if (delta.keys == setOf("id") || delta.isEmpty()) return Pair(null, true)

            return Pair(g.toJson(delta), true)
        } catch (e: Exception) {
            Pair(currentFrame, false)
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

    fun getBufferedDataSize(): Long {
        return bufferManager.getBufferedDataSize()
    }
}