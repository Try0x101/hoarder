package com.example.hoarder.data.uploader

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

data class CompressionResult(
    val compressed: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val compressionRatio: Float
)

object CompressionUtils {

    private const val COMPRESSION_LEVEL = Deflater.BEST_COMPRESSION

    fun compressData(jsonData: String): CompressionResult {
        val originalBytes = jsonData.toByteArray(StandardCharsets.UTF_8)
        val originalSize = originalBytes.size

        return try {
            val compressedBytes = compressWithZlib(originalBytes)
            val compressedSize = compressedBytes.size
            val compressionRatio = compressedSize.toFloat() / originalSize.toFloat()

            CompressionResult(
                compressed = compressedBytes,
                originalSize = originalSize,
                compressedSize = compressedSize,
                compressionRatio = compressionRatio
            )
        } catch (e: Exception) {
            Log.e("CompressionUtils", "Compression failed", e)
            throw e
        }
    }

    private fun compressWithZlib(data: ByteArray): ByteArray {
        val deflater = Deflater(COMPRESSION_LEVEL, true) // nowrap=true for raw zlib (wbits=-15)
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
        return "Compressed: ${result.originalSize}B → ${result.compressedSize}B (${(result.compressionRatio * 100).toInt()}%)"
    }
}