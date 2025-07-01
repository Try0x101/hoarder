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

class DataUploader(
    private val ctx: Context,
    private val h: Handler,
    private val sp: SharedPreferences
) {
    private var ua = false
    private var ip = ""
    private var port = 5000
    private var ld: String? = null
    private var lu: String? = null
    private val g = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private var tb = sp.getLong("totalUploadedBytes", 0L)
    private var ls: String? = null
    private var lm2: Pair<String, String>? = null
    private var lt = 0L
    private val cd = 5000L

    private val bufferManager = BufferManager(ctx, sp)
    private val networkUploader = NetworkUploader(ctx)

    private val ur = object : Runnable {
        override fun run() {
            if (ua && ip.isNotBlank() && port > 0) {
                Thread {
                    ld?.let { dataToProcess ->
                        val (d, delta) = generateJson(dataToProcess)
                        if (d != null) {
                            lu = dataToProcess
                            processUpload(d, delta)
                        } else {
                            notifyStatus("No Change", "Data unchanged, skipping upload.", tb, bufferManager.getBufferedDataSize())
                        }
                        ld = null
                    }
                }.start()
                if (ua) h.postDelayed(this, 1000L)
            } else if (ua && (ip.isBlank() || port <= 0)) {
                notifyStatus("Error", "Server IP or Port became invalid.", tb, bufferManager.getBufferedDataSize())
                ua = false
            }
        }
    }

    fun setServer(ip: String, port: Int) {
        this.ip = ip
        this.port = port
    }

    fun hasValidServer() = ip.isNotBlank() && port > 0

    fun queueData(data: String) {
        ld = data
    }

    fun start() {
        h.removeCallbacks(ur)
        lu = null
        ua = true
        notifyStatus("Connecting", "Attempting to connect...", tb, bufferManager.getBufferedDataSize())
        h.post(ur)
    }

    fun stop() {
        ua = false
        h.removeCallbacks(ur)
    }

    fun resetCounter() {
        tb = 0L
        lu = null
        sp.edit().putLong("totalUploadedBytes", 0L).apply()
        notifyStatus("Paused", "Upload paused.", tb, bufferManager.getBufferedDataSize())
    }

    fun getUploadedBytes(): Long {
        return tb
    }

    fun forceSendBuffer() {
        if (ua && ip.isNotBlank() && port > 0) {
            Thread {
                val bufferedData = bufferManager.getBufferedData()
                if (bufferedData.isNotEmpty()) {
                    processBatchUpload(bufferedData)
                }
            }.start()
        }
    }

    private fun processUpload(jsonString: String, isDelta: Boolean) {
        if (!NetUtils.isNetworkAvailable(ctx)) {
            bufferManager.saveToBuffer(jsonString)
            bufferManager.addErrorLog("Internet not accessible")
            notifyStatus("Saving Locally", "Internet not accessible. Delta saved locally.", tb, bufferManager.getBufferedDataSize())
            return
        }

        val result = networkUploader.uploadSingle(jsonString, isDelta, ip, port)

        if (result.success) {
            tb += result.uploadedBytes
            sp.edit().putLong("totalUploadedBytes", tb).apply()
            bufferManager.addUploadRecord(result.uploadedBytes)
            bufferManager.addSuccessLog(jsonString, result.uploadedBytes)
            notifyStatus(
                if (isDelta) "OK (Delta)" else "OK (Full)",
                "Uploaded successfully.",
                tb,
                bufferManager.getBufferedDataSize(),
                result.uploadedBytes
            )
        } else {
            bufferManager.saveToBuffer(jsonString)
            result.errorMessage?.let { bufferManager.addErrorLog(it) }
            notifyStatus(result.statusMessage, result.errorMessage ?: "Upload failed", tb, bufferManager.getBufferedDataSize())
        }
    }

    private fun processBatchUpload(batch: List<String>) {
        val result = networkUploader.uploadBatch(batch, ip, port)

        if (result.success) {
            tb += result.uploadedBytes
            sp.edit().putLong("totalUploadedBytes", tb).apply()
            bufferManager.addUploadRecord(result.uploadedBytes)
            bufferManager.addSuccessLog("Batch upload of ${batch.size} records", result.uploadedBytes)
            bufferManager.saveLastUploadDetails(batch)
            bufferManager.clearBuffer(batch)
            notifyStatus("OK (Batch)", "Buffered data uploaded successfully.", tb, bufferManager.getBufferedDataSize(), result.uploadedBytes)
        } else {
            result.errorMessage?.let { bufferManager.addErrorLog(it) }
            notifyStatus(result.statusMessage, result.errorMessage ?: "Batch upload failed", tb, bufferManager.getBufferedDataSize())
        }
    }

    private fun generateJson(currentFrame: String): Pair<String?, Boolean> {
        if (lu == null) return Pair(currentFrame, false)
        try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val previous = g.fromJson(lu, type) as Map<String, Any?>
            val current = g.fromJson(currentFrame, type) as Map<String, Any?>
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
            return Pair(currentFrame, false)
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