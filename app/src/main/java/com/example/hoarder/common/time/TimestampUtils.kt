package com.example.hoarder.common.time

object TimestampUtils {
    private const val FIXED_EPOCH = 0L // Using standard Unix epoch

    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis() / 1000 - FIXED_EPOCH
    }

    fun timestampToUnixEpoch(timestamp: Long): Long {
        return timestamp + FIXED_EPOCH
    }
}