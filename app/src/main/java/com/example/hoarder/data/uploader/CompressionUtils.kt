package com.example.hoarder.data.uploader

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

data class CompressionResult(
    val compressed: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val compressionRatio: Float,
    val method: String,
    val wasCompressed: Boolean
)

object CompressionUtils {

    private const val NO_COMPRESSION_THRESHOLD = 50
    private const val MIN_COMPRESSION_BENEFIT = 0.85f

    fun compressData(jsonData: String, compressionLevel: Int): CompressionResult {
        val originalBytes = jsonData.toByteArray(StandardCharsets.UTF_8)
        val originalSize = originalBytes.size

        if (compressionLevel == 0) {
            return CompressionResult(
                compressed = originalBytes,
                originalSize = originalSize,
                compressedSize = originalSize,
                compressionRatio = 1.0f,
                method = "none-disabled",
                wasCompressed = false
            )
        }

        return when {
            originalSize < NO_COMPRESSION_THRESHOLD -> {
                CompressionResult(
                    compressed = originalBytes,
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    compressionRatio = 1.0f,
                    method = "none-too-small",
                    wasCompressed = false
                )
            }
            else -> {
                compressWithDeflate(originalBytes, originalSize, compressionLevel)
            }
        }
    }

    private fun compressWithDeflate(
        originalBytes: ByteArray,
        originalSize: Int,
        compressionLevel: Int
    ): CompressionResult {
        return try {
            val compressedBytes = compressWithZlib(originalBytes, compressionLevel)
            val compressedSize = compressedBytes.size
            val compressionRatio = compressedSize.toFloat() / originalSize.toFloat()

            if (compressedSize >= originalSize || compressionRatio > MIN_COMPRESSION_BENEFIT) {
                CompressionResult(
                    compressed = originalBytes,
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    compressionRatio = 1.0f,
                    method = "none-insufficient-benefit",
                    wasCompressed = false
                )
            } else {
                CompressionResult(
                    compressed = compressedBytes,
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    compressionRatio = compressionRatio,
                    method = "deflate-level-$compressionLevel",
                    wasCompressed = true
                )
            }
        } catch (e: Exception) {
            Log.e("CompressionUtils", "Compression failed, using uncompressed", e)
            CompressionResult(
                compressed = originalBytes,
                originalSize = originalSize,
                compressedSize = originalSize,
                compressionRatio = 1.0f,
                method = "none-error",
                wasCompressed = false
            )
        }
    }

    private fun compressWithZlib(data: ByteArray, compressionLevel: Int): ByteArray {
        val deflater = Deflater(compressionLevel, true)
        return try {
            deflater.setInput(data)
            deflater.finish()

            val buffer = ByteArray(1024)
            val outputStream = ByteArrayOutputStream()

            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                outputStream.write(buffer, 0, count)
            }

            outputStream.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun formatCompressionStats(result: CompressionResult): String {
        return if (result.wasCompressed) {
            "${result.method}: ${result.originalSize}B → ${result.compressedSize}B (${(result.compressionRatio * 100).toInt()}%)"
        } else {
            "${result.method}: ${result.originalSize}B (uncompressed)"
        }
    }
}