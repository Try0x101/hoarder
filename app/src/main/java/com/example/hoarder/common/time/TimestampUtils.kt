package com.example.hoarder.common.time

object TimestampUtils {
    private const val FIXED_EPOCH = 1719705600L // June 30, 2025 00:00:00 UTC

    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis() / 1000 - FIXED_EPOCH
    }

    fun timestampToUnixEpoch(timestamp: Long): Long {
        return timestamp + FIXED_EPOCH
    }
}