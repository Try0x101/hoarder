package com.example.hoarder.data.uploader

import android.content.Context
import android.util.Log
import java.io.*
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

data class UploadResult(
    val success: Boolean,
    val uploadedBytes: Long,
    val statusMessage: String,
    val errorMessage: String? = null,
    val compressionStats: String? = null
)

class NetworkUploader(private val context: Context) {

    companion object {
        private const val SINGLE_TIMEOUT_MS = 5000
        private const val BATCH_TIMEOUT_MS = 10000
        private const val CONNECT_TIMEOUT_MS = 3000
    }

    fun uploadSingle(jsonData: String, isDelta: Boolean, serverIp: String, serverPort: Int): UploadResult {
        return try {
            val compressionResult = CompressionUtils.compressData(jsonData)
            val url = URL("http://$serverIp:$serverPort/api/telemetry")
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Data-Type", if (isDelta) "delta" else "full")
                setRequestProperty("Content-Encoding", "deflate")
                setRequestProperty("X-Original-Size", compressionResult.originalSize.toString())
                setRequestProperty("X-Compression-Ratio", String.format("%.2f", compressionResult.compressionRatio))
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = SINGLE_TIMEOUT_MS
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(compressionResult.compressed)
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage ?: "Unknown"

            when (responseCode) {
                200 -> {
                    val response = connection.inputStream.use { inputStream ->
                        inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                    }
                    UploadResult(
                        success = true,
                        uploadedBytes = compressionResult.compressedSize.toLong(),
                        statusMessage = if (isDelta) "OK (Delta)" else "OK (Full)",
                        errorMessage = null,
                        compressionStats = CompressionUtils.formatCompressionStats(compressionResult)
                    )
                }
                else -> {
                    val errorResponse = try {
                        connection.errorStream?.use { errorStream ->
                            errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
                        } ?: responseMessage
                    } catch (e: Exception) {
                        responseMessage
                    }

                    UploadResult(
                        success = false,
                        uploadedBytes = 0L,
                        statusMessage = "HTTP Error",
                        errorMessage = "HTTP $responseCode: $errorResponse",
                        compressionStats = CompressionUtils.formatCompressionStats(compressionResult)
                    )
                }
            }
        } catch (e: ConnectException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Connection refused to $serverIp:$serverPort"
            )
        } catch (e: SocketTimeoutException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Connection timeout to $serverIp:$serverPort"
            )
        } catch (e: IOException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Network error: ${e.message}"
            )
        } catch (e: Exception) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Error",
                errorMessage = "Upload failed: ${e.message}"
            )
        }
    }

    fun uploadBatch(batchData: List<String>, serverIp: String, serverPort: Int): UploadResult {
        return try {
            val batchJson = createBatchJson(batchData)
            val compressionResult = CompressionUtils.compressData(batchJson)

            val url = URL("http://$serverIp:$serverPort/api/batch")
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Data-Type", "batch")
                setRequestProperty("X-Record-Count", batchData.size.toString())
                setRequestProperty("Content-Encoding", "deflate")
                setRequestProperty("X-Original-Size", compressionResult.originalSize.toString())
                setRequestProperty("X-Compression-Ratio", String.format("%.2f", compressionResult.compressionRatio))
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = BATCH_TIMEOUT_MS
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(compressionResult.compressed)
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage ?: "Unknown"

            when (responseCode) {
                200 -> {
                    val response = connection.inputStream.use { inputStream ->
                        inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                    }
                    UploadResult(
                        success = true,
                        uploadedBytes = compressionResult.compressedSize.toLong(),
                        statusMessage = "OK (Batch)",
                        errorMessage = null,
                        compressionStats = CompressionUtils.formatCompressionStats(compressionResult)
                    )
                }
                else -> {
                    val errorResponse = try {
                        connection.errorStream?.use { errorStream ->
                            errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
                        } ?: responseMessage
                    } catch (e: Exception) {
                        responseMessage
                    }

                    UploadResult(
                        success = false,
                        uploadedBytes = 0L,
                        statusMessage = "HTTP Error",
                        errorMessage = "Batch HTTP $responseCode: $errorResponse",
                        compressionStats = CompressionUtils.formatCompressionStats(compressionResult)
                    )
                }
            }
        } catch (e: ConnectException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Batch connection refused to $serverIp:$serverPort"
            )
        } catch (e: SocketTimeoutException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Batch connection timeout to $serverIp:$serverPort"
            )
        } catch (e: IOException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Batch network error: ${e.message}"
            )
        } catch (e: Exception) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Error",
                errorMessage = "Batch upload failed: ${e.message}"
            )
        }
    }

    private fun createBatchJson(batchData: List<String>): String {
        val jsonBuilder = StringBuilder()
        jsonBuilder.append("[")

        batchData.forEachIndexed { index, record ->
            jsonBuilder.append(record)
            if (index < batchData.size - 1) {
                jsonBuilder.append(",")
            }
        }

        jsonBuilder.append("]")
        return jsonBuilder.toString()
    }

    fun testConnection(serverIp: String, serverPort: Int): UploadResult {
        return try {
            val url = URL("http://$serverIp:$serverPort/api/telemetry")
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 2000
            }

            val responseCode = connection.responseCode

            if (responseCode in 200..299 || responseCode == 404 || responseCode == 405) {
                UploadResult(
                    success = true,
                    uploadedBytes = 0L,
                    statusMessage = "Connected",
                    errorMessage = null
                )
            } else {
                UploadResult(
                    success = false,
                    uploadedBytes = 0L,
                    statusMessage = "HTTP Error",
                    errorMessage = "Server responded with HTTP $responseCode"
                )
            }
        } catch (e: ConnectException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Cannot connect to $serverIp:$serverPort"
            )
        } catch (e: SocketTimeoutException) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Network Error",
                errorMessage = "Connection timeout to $serverIp:$serverPort"
            )
        } catch (e: Exception) {
            UploadResult(
                success = false,
                uploadedBytes = 0L,
                statusMessage = "Error",
                errorMessage = "Connection test failed: ${e.message}"
            )
        }
    }
}