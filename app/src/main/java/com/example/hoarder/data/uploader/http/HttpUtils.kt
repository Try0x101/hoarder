package com.example.hoarder.data.uploader.http

import com.example.hoarder.data.uploader.models.UploadResult
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL

object HttpUtils {
    fun safeApiCall(url: URL, apiCall: () -> UploadResult): UploadResult {
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

    fun calculateHttpHeadersSize(url: URL, headers: Map<String, String>, method: String, payloadSize: Long): Long {
        var size = 0L
        size += "$method ${url.path} HTTP/1.1\r\n".toByteArray().size
        size += "Host: ${url.host}:${url.port}\r\n".toByteArray().size
        size += "User-Agent: Dalvik/2.1.0 (Linux; U; Android)\r\n".toByteArray().size
        size += "Connection: keep-alive\r\n".toByteArray().size
        if (payloadSize >= 0) {
            size += "Content-Length: $payloadSize\r\n".toByteArray().size
        }
        headers.forEach { (key, value) ->
            size += "$key: $value\r\n".toByteArray().size
        }
        size += "\r\n".toByteArray().size
        return size
    }
}