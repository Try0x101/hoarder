package com.example.hoarder.data.uploader.handler

import android.content.Context
import android.database.Cursor
import com.example.hoarder.data.storage.app.Prefs
import com.example.hoarder.data.storage.db.LogDao
import com.example.hoarder.data.uploader.NetworkUploader
import com.example.hoarder.transport.buffer.DataBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class BulkUploadHandler(
    private val ctx: Context,
    private val appPrefs: Prefs,
    private val networkUploader: NetworkUploader,
    private val dataBuffer: DataBuffer,
    private val logDao: LogDao,
    private val bulkUploadInProgress: AtomicBoolean,
    private val statusNotifier: (String, String) -> Unit,
    private val onCompletion: suspend (Boolean) -> Unit
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val compressionLevel by lazy { appPrefs.getCompressionLevel() }

    fun resumeBulkUploadProcess() {
        coroutineScope.launch {
            val jobId = appPrefs.getBulkJobId()
            if (appPrefs.getBulkJobState() == "POLLING" && jobId != null) {
                bulkUploadInProgress.set(true)
                if (pollJobStatusLoop(jobId)) {
                    onCompletion(true)
                }
                bulkUploadInProgress.set(false)
            } else {
                initiateBulkUploadProcess()
            }
        }
    }

    suspend fun initiateBulkUploadProcess(): Boolean {
        if (!bulkUploadInProgress.compareAndSet(false, true)) return false

        statusNotifier("Preparing", "Bulk upload...")
        appPrefs.setBulkJobState("MARSHALLING")

        val tempFile = File(ctx.cacheDir, "bulk_upload_${System.currentTimeMillis()}.json.zlib")
        if (!marshallDataToFile(tempFile)) {
            cleanupBulkState(tempFile, isError = true, errorMessage = "Marshalling failed")
            bulkUploadInProgress.set(false)
            return false
        }

        val (ip, port) = (appPrefs.getServerAddress().split(":") + listOf("0")).let { it[0] to it[1].toInt() }
        statusNotifier("Uploading", "Bulk file...")
        appPrefs.setBulkJobState("UPLOADING")
        val result = networkUploader.uploadBulkFile(tempFile, ip, port)

        val success = if (result.success && result.jobId != null) {
            appPrefs.setBulkJobId(result.jobId)
            appPrefs.setBulkJobState("POLLING")
            tempFile.delete()
            pollJobStatusLoop(result.jobId)
        } else {
            cleanupBulkState(tempFile, isError = true, errorMessage = result.errorMessage)
            false
        }

        bulkUploadInProgress.set(false)
        return success
    }

    private suspend fun pollJobStatusLoop(jobId: String): Boolean {
        val (ip, port) = (appPrefs.getServerAddress().split(":") + listOf("0")).let { it[0] to it[1].toInt() }
        for (delayMs in listOf(2000L, 4000L, 8000L, 15000L, 30000L, 60000L)) {
            val status = networkUploader.getJobStatus(jobId, ip, port)
            when (status?.get("status")) {
                "COMPLETE" -> { finalizeBulkSuccess(); return true }
                "FAILED" -> { cleanupBulkState(isError = true, errorMessage = status["error"] as? String); return false }
                else -> { statusNotifier("Processing", status?.get("details") as? String ?: "..."); delay(
                    delayMs
                )
                }
            }
        }
        cleanupBulkState(isError = true, errorMessage = "Polling timed out.")
        return false
    }

    private suspend fun marshallDataToFile(file: File): Boolean = withContext(Dispatchers.IO) {
        var cursor: Cursor? = null
        try {
            DeflaterOutputStream(
                FileOutputStream(file),
                Deflater(compressionLevel, true)
            ).use { deflaterStream ->
                cursor = logDao.getAllPayloadsCursor()
                deflaterStream.write("[".toByteArray())
                var isFirst = true
                if (cursor?.moveToFirst() == true) {
                    val payloadIndex = cursor!!.getColumnIndexOrThrow("payload")
                    do {
                        if (!isFirst) deflaterStream.write(",".toByteArray())
                        deflaterStream.write(
                            cursor!!.getString(payloadIndex).toByteArray(Charsets.UTF_8)
                        )
                        isFirst = false
                    } while (cursor!!.moveToNext())
                }
                deflaterStream.write("]".toByteArray())
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            cursor?.close()
        }
    }

    private suspend fun finalizeBulkSuccess() {
        dataBuffer.clearBuffer(logDao.getAllPayloads())
        cleanupBulkState()
        statusNotifier("OK (Bulk)", "Bulk upload complete")
        onCompletion(true)
    }

    private fun cleanupBulkState(file: File? = null, isError: Boolean = false, errorMessage: String? = null) {
        appPrefs.setBulkJobId(null); appPrefs.setBulkJobState("IDLE"); file?.delete()
        if (isError) {
            statusNotifier("Error", errorMessage ?: "Bulk upload failed")
            coroutineScope.launch { onCompletion(false) }
        }
    }
}