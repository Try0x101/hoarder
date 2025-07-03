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

    private const val NO_COMPRESSION_THRESHOLD = 25
    private const val COMPRESSION_LEVEL = Deflater.BEST_COMPRESSION

    fun compressData(jsonData: String): CompressionResult {
        val originalBytes = jsonData.toByteArray(StandardCharsets.UTF_8)
        val originalSize = originalBytes.size

        return when {
            originalSize < NO_COMPRESSION_THRESHOLD -> {
                CompressionResult(
                    compressed = originalBytes,
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    compressionRatio = 1.0f,
                    method = "none",
                    wasCompressed = false
                )
            }
            else -> {
                compressWithBestLevel(originalBytes, originalSize)
            }
        }
    }

    private fun compressWithBestLevel(
        originalBytes: ByteArray,
        originalSize: Int
    ): CompressionResult {
        return try {
            val compressedBytes = compressWithZlib(originalBytes, COMPRESSION_LEVEL)
            val compressedSize = compressedBytes.size
            val compressionRatio = compressedSize.toFloat() / originalSize.toFloat()

            if (compressedSize >= originalSize) {
                CompressionResult(
                    compressed = originalBytes,
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    compressionRatio = 1.0f,
                    method = "none-fallback",
                    wasCompressed = false
                )
            } else {
                CompressionResult(
                    compressed = compressedBytes,
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    compressionRatio = compressionRatio,
                    method = "deflate-best",
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