package com.example.hoarder.data.uploader

import android.content.Context
import com.example.hoarder.utils.NetUtils
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class NetworkUploader(private val ctx: Context) {

    data class UploadResult(
        val success: Boolean,
        val statusMessage: String,
        val errorMessage: String?,
        val uploadedBytes: Long = 0L
    )

    fun uploadSingle(jsonString: String, isDelta: Boolean, serverIp: String, serverPort: Int): UploadResult {
        if (!NetUtils.isNetworkAvailable(ctx)) {
            return UploadResult(false, "Saving Locally", "Internet not accessible")
        }

        if (serverIp.isBlank() || serverPort <= 0) {
            return UploadResult(false, "Error", "Server IP or Port not set")
        }

        val uploadUrl = "http://$serverIp:$serverPort/api/telemetry"
        var connection: HttpURLConnection? = null

        try {
            val url = URL(uploadUrl)
            connection = url.openConnection() as HttpURLConnection
            setupConnection(connection, isDelta)

            val compressedData = compressData(jsonString)
            connection.outputStream.write(compressedData)
            connection.outputStream.flush()

            return handleResponse(connection, compressedData.size.toLong())

        } catch (e: Exception) {
            val errorMessage = "Failed to connect: ${e.message}"
            return UploadResult(false, "Network Error", errorMessage)
        } finally {
            connection?.disconnect()
        }
    }

    fun uploadBatch(batch: List<String>, serverIp: String, serverPort: Int): UploadResult {
        if (serverIp.isBlank() || serverPort <= 0) {
            return UploadResult(false, "Error", "Server IP or Port not set")
        }

        val uploadUrl = "http://$serverIp:$serverPort/api/batch-delta"
        var connection: HttpURLConnection? = null

        try {
            val url = URL(uploadUrl)
            connection = url.openConnection() as HttpURLConnection
            setupBatchConnection(connection)

            val jsonBatch = "[" + batch.joinToString(",") + "]"
            val requestBody = jsonBatch.toByteArray(StandardCharsets.UTF_8)

            connection.outputStream.write(requestBody)
            connection.outputStream.flush()

            return handleBatchResponse(connection, requestBody.size.toLong(), batch.size)

        } catch (e: Exception) {
            val errorMessage = "Failed to connect: ${e.message}"
            return UploadResult(false, "Network Error", errorMessage)
        } finally {
            connection?.disconnect()
        }
    }

    private fun setupConnection(connection: HttpURLConnection, isDelta: Boolean) {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Data-Type", if (isDelta) "delta" else "full")
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
    }

    private fun setupBatchConnection(connection: HttpURLConnection) {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
    }

    private fun compressData(jsonString: String): ByteArray {
        val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        val deflater = Deflater(7, true)
        val compressedOutput = ByteArrayOutputStream()
        DeflaterOutputStream(compressedOutput, deflater).use { it.write(jsonBytes) }
        return compressedOutput.toByteArray()
    }

    private fun handleResponse(connection: HttpURLConnection, uploadedBytes: Long): UploadResult {
        val responseCode = connection.responseCode
        return if (responseCode == HttpURLConnection.HTTP_OK) {
            UploadResult(
                success = true,
                statusMessage = "OK (Full)",
                errorMessage = null,
                uploadedBytes = uploadedBytes
            )
        } else {
            val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error response"
            val errorMessage = "$responseCode: ${connection.responseMessage}. Server response: $errorResponse"
            UploadResult(false, "HTTP Error", errorMessage)
        }
    }

    private fun handleBatchResponse(connection: HttpURLConnection, uploadedBytes: Long, batchSize: Int): UploadResult {
        val responseCode = connection.responseCode
        return if (responseCode == HttpURLConnection.HTTP_OK) {
            UploadResult(
                success = true,
                statusMessage = "OK (Batch)",
                errorMessage = null,
                uploadedBytes = uploadedBytes
            )
        } else {
            val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error response"
            val errorMessage = "$responseCode: ${connection.responseMessage}. Server response: $errorResponse"
            UploadResult(false, "HTTP Error", errorMessage)
        }
    }
}