package com.example.hoarder.data.uploader

import android.content.Context
import com.example.hoarder.data.uploader.http.HttpUtils
import com.example.hoarder.data.uploader.models.UploadResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class NetworkUploader(private val context: Context) {

    companion object {
        private const val SINGLE_TIMEOUT_MS = 5000
        private const val BATCH_TIMEOUT_MS = 15000
        private const val BULK_TIMEOUT_MS = 60000
        private const val CONNECT_TIMEOUT_MS = 5000
    }
    private val gson = Gson()

    private fun performUpload(url: URL, payload: ByteArray, headers: Map<String, String>, timeout: Int, successStatus: String, compressionResult: CompressionResult): UploadResult {
        return HttpUtils.safeApiCall(url) {
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeout
            }
            connection.outputStream.use { it.write(payload) }
            val httpHeadersSize = HttpUtils.calculateHttpHeadersSize(url, headers, "POST", payload.size.toLong())
            val actualNetworkBytes = payload.size.toLong() + httpHeadersSize
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                UploadResult(true, payload.size.toLong(), actualNetworkBytes, successStatus, null, CompressionUtils.formatCompressionStats(compressionResult))
            } else {
                val errorResponse = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: connection.responseMessage
                UploadResult(false, 0L, actualNetworkBytes, "HTTP Error", "HTTP $responseCode: $errorResponse", CompressionUtils.formatCompressionStats(compressionResult))
            }
        }
    }

    fun uploadSingle(jsonData: String, isDelta: Boolean, serverIp: String, serverPort: Int, compressionLevel: Int): UploadResult {
        val compressionResult = CompressionUtils.compressData(jsonData, compressionLevel)
        val url = URL("http://$serverIp:$serverPort/api/telemetry")
        val headers = mapOf("Content-Type" to "application/json", "X-Data-Type" to if (isDelta) "delta" else "full", "Content-Encoding" to "deflate", "X-Original-Size" to compressionResult.originalSize.toString(), "X-Compression-Ratio" to String.format("%.2f", compressionResult.compressionRatio))
        return performUpload(url, compressionResult.compressed, headers, SINGLE_TIMEOUT_MS, if (isDelta) "OK (Delta)" else "OK (Full)", compressionResult)
    }

    fun uploadBatch(batchData: List<String>, serverIp: String, serverPort: Int, compressionLevel: Int): UploadResult {
        val batchJson = batchData.joinToString(separator = ",", prefix = "[", postfix = "]")
        val compressionResult = CompressionUtils.compressData(batchJson, compressionLevel)
        val url = URL("http://$serverIp:$serverPort/api/batch")
        val headers = mapOf("Content-Type" to "application/json", "X-Data-Type" to "batch", "X-Record-Count" to batchData.size.toString(), "Content-Encoding" to "deflate", "X-Original-Size" to compressionResult.originalSize.toString(), "X-Compression-Ratio" to String.format("%.2f", compressionResult.compressionRatio))
        return performUpload(url, compressionResult.compressed, headers, BATCH_TIMEOUT_MS, "OK (Batch)", compressionResult)
    }

    fun uploadBulkFile(file: File, serverIp: String, serverPort: Int): UploadResult {
        val url = URL("http://$serverIp:$serverPort/api/bulk")
        return HttpUtils.safeApiCall(url) {
            val headers = mapOf("Content-Type" to "application/octet-stream", "Content-Encoding" to "deflate")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply { requestMethod = "POST"; headers.forEach { (k, v) -> setRequestProperty(k, v) }; doOutput = true; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = BULK_TIMEOUT_MS; setChunkedStreamingMode(0) }
            file.inputStream().use { fileStream -> connection.outputStream.use { connStream -> fileStream.copyTo(connStream) } }
            if (connection.responseCode == 202) {
                val responseBody = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                val responseMap: Map<String, String> = gson.fromJson(responseBody, object : TypeToken<Map<String, String>>() {}.type)
                UploadResult(true, file.length(), 0L, "Accepted", jobId = responseMap["job_id"])
            } else {
                UploadResult(false, 0L, 0L, "HTTP Error", "HTTP ${connection.responseCode}: ${connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: connection.responseMessage}")
            }
        }
    }

    fun getJobStatus(jobId: String, serverIp: String, serverPort: Int): Map<String, Any>? {
        val url = URL("http://$serverIp:$serverPort/api/bulk/status/$jobId")
        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.apply { requestMethod = "GET"; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = SINGLE_TIMEOUT_MS }
            if (connection.responseCode == 200) {
                gson.fromJson(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).readText(), object : TypeToken<Map<String, Any>>() {}.type)
            } else null
        } catch (e: Exception) { null }
    }
}