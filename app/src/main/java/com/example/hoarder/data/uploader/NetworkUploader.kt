package com.example.hoarder.data.uploader

import android.content.Context
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

data class UploadResult(
    val success: Boolean,
    val uploadedBytes: Long,
    val actualNetworkBytes: Long,
    val statusMessage: String,
    val errorMessage: String? = null,
    val compressionStats: String? = null
)

class NetworkUploader(private val context: Context) {

    companion object {
        private const val SINGLE_TIMEOUT_MS = 5000
        private const val BATCH_TIMEOUT_MS = 15000
        private const val CONNECT_TIMEOUT_MS = 3000
    }

    private fun safeApiCall(url: URL, apiCall: () -> UploadResult): UploadResult {
        return try {
            apiCall()
        } catch (e: ConnectException) {
            UploadResult(false, 0L, 0L, "Network Error", "Connection refused to ${url.host}:${url.port}")
        } catch (e: SocketTimeoutException) {
            UploadResult(false, 0L, 0L, "Network Error", "Connection timeout to ${url.host}:${url.port}")
        } catch (e: IOException) {
            UploadResult(false, 0L, 0L, "Network Error", "Network error: ${e.message}")
        } catch (e: Exception) {
            UploadResult(false, 0L, 0L, "Error", "Upload failed: ${e.message}")
        }
    }

    private fun calculateHttpHeadersSize(url: URL, headers: Map<String, String>, payloadSize: Int): Long {
        var size = 0L

        size += "POST ${url.path} HTTP/1.1\r\n".toByteArray().size
        size += "Host: ${url.host}:${url.port}\r\n".toByteArray().size
        size += "User-Agent: Dalvik/2.1.0 (Linux; U; Android)\r\n".toByteArray().size
        size += "Connection: keep-alive\r\n".toByteArray().size
        size += "Content-Length: $payloadSize\r\n".toByteArray().size

        headers.forEach { (key, value) ->
            size += "$key: $value\r\n".toByteArray().size
        }

        size += "\r\n".toByteArray().size

        return size
    }

    private fun performUpload(url: URL, payload: ByteArray, headers: Map<String, String>, timeout: Int, successStatus: String, compressionResult: CompressionResult): UploadResult {
        return safeApiCall(url) {
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeout
            }

            connection.outputStream.use { it.write(payload) }

            val httpHeadersSize = calculateHttpHeadersSize(url, headers, payload.size)
            val actualNetworkBytes = payload.size.toLong() + httpHeadersSize

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                UploadResult(
                    success = true,
                    uploadedBytes = payload.size.toLong(),
                    actualNetworkBytes = actualNetworkBytes,
                    statusMessage = successStatus,
                    null,
                    CompressionUtils.formatCompressionStats(compressionResult)
                )
            } else {
                val errorResponse = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: connection.responseMessage
                UploadResult(
                    success = false,
                    uploadedBytes = 0L,
                    actualNetworkBytes = actualNetworkBytes,
                    statusMessage = "HTTP Error",
                    "HTTP $responseCode: $errorResponse",
                    CompressionUtils.formatCompressionStats(compressionResult)
                )
            }
        }
    }

    fun uploadSingle(jsonData: String, isDelta: Boolean, serverIp: String, serverPort: Int, compressionLevel: Int): UploadResult {
        val compressionResult = CompressionUtils.compressData(jsonData, compressionLevel)
        val url = URL("http://$serverIp:$serverPort/api/telemetry")
        val headers = mapOf(
            "Content-Type" to "application/json",
            "X-Data-Type" to if (isDelta) "delta" else "full",
            "Content-Encoding" to "deflate",
            "X-Original-Size" to compressionResult.originalSize.toString(),
            "X-Compression-Ratio" to String.format("%.2f", compressionResult.compressionRatio)
        )
        val statusMessage = if (isDelta) "OK (Delta)" else "OK (Full)"
        return performUpload(url, compressionResult.compressed, headers, SINGLE_TIMEOUT_MS, statusMessage, compressionResult)
    }

    fun uploadBatch(batchData: List<String>, serverIp: String, serverPort: Int, compressionLevel: Int): UploadResult {
        val batchJson = batchData.joinToString(separator = ",", prefix = "[", postfix = "]")
        val compressionResult = CompressionUtils.compressData(batchJson, compressionLevel)
        val url = URL("http://$serverIp:$serverPort/api/batch")
        val headers = mapOf(
            "Content-Type" to "application/json",
            "X-Data-Type" to "batch",
            "X-Record-Count" to batchData.size.toString(),
            "Content-Encoding" to "deflate",
            "X-Original-Size" to compressionResult.originalSize.toString(),
            "X-Compression-Ratio" to String.format("%.2f", compressionResult.compressionRatio)
        )
        return performUpload(url, compressionResult.compressed, headers, BATCH_TIMEOUT_MS, "OK (Batch)", compressionResult)
    }

    fun testConnection(serverIp: String, serverPort: Int): UploadResult {
        val url = URL("http://$serverIp:$serverPort/api/telemetry")
        return safeApiCall(url) {
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 2000
            }

            val httpHeadersSize = calculateHttpHeadersSize(url, emptyMap(), 0)

            val responseCode = connection.responseCode
            if (responseCode in 200..299 || responseCode == 404 || responseCode == 405) {
                UploadResult(true, 0L, httpHeadersSize, "Connected")
            } else {
                UploadResult(false, 0L, httpHeadersSize, "HTTP Error", "Server responded with HTTP $responseCode")
            }
        }
    }
}