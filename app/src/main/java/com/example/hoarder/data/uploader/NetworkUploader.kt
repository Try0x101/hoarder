package com.example.hoarder.data.uploader

import android.content.Context
import com.example.hoarder.utils.NetUtils
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class NetworkUploader(private val ctx: Context) {

    data class UploadResult(
        val success: Boolean,
        val uploadedBytes: Long = 0,
        val statusMessage: String = "",
        val errorMessage: String? = null
    )

    fun uploadSingle(jsonString: String, isDelta: Boolean, ip: String, port: Int): UploadResult {
        if (!NetUtils.isNetworkAvailable(ctx)) {
            return UploadResult(false, 0, "Saving Locally", "Internet not accessible")
        }

        return try {
            val url = URL("http://$ip:$port/api/telemetry")
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Data-Type", if (isDelta) "delta" else "full")
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
            }

            val bytes = jsonString.toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                    writer.write(jsonString)
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                UploadResult(true, bytes.size.toLong(), if (isDelta) "OK (Delta)" else "OK (Full)")
            } else {
                UploadResult(false, 0, "HTTP Error", "HTTP $responseCode")
            }
        } catch (e: java.net.ConnectException) {
            UploadResult(false, 0, "Network Error", "Server unreachable")
        } catch (e: java.net.SocketTimeoutException) {
            UploadResult(false, 0, "Network Error", "Connection timeout")
        } catch (e: Exception) {
            UploadResult(false, 0, "Network Error", e.message ?: "Unknown error")
        }
    }

    fun uploadBatch(batch: List<String>, ip: String, port: Int): UploadResult {
        if (!NetUtils.isNetworkAvailable(ctx)) {
            return UploadResult(false, 0, "Saving Locally", "Internet not accessible")
        }

        return try {
            val url = URL("http://$ip:$port/api/batch")
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }

            val batchJson = "[${batch.joinToString(",")}]"
            val bytes = batchJson.toByteArray(StandardCharsets.UTF_8)

            connection.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                    writer.write(batchJson)
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                UploadResult(true, bytes.size.toLong(), "OK (Batch)")
            } else {
                UploadResult(false, 0, "HTTP Error", "HTTP $responseCode")
            }
        } catch (e: java.net.ConnectException) {
            UploadResult(false, 0, "Network Error", "Server unreachable")
        } catch (e: java.net.SocketTimeoutException) {
            UploadResult(false, 0, "Network Error", "Timeout")
        } catch (e: Exception) {
            UploadResult(false, 0, "Network Error", e.message ?: "Unknown error")
        }
    }
}