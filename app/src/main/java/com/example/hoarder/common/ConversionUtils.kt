package com.example.hoarder.common

object ConversionUtils {
    fun safeStringToInt(str: String?, default: Int = 0): Int {
        return try {
            str?.toIntOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun safeStringToLong(str: String?, default: Long = 0L): Long {
        return try {
            str?.toLongOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun safeStringToFloat(str: String?, default: Float = 0.0f): Float {
        return try {
            str?.toFloatOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun safeStringToDouble(str: String?, default: Double = 0.0): Double {
        return try {
            str?.toDoubleOrNull() ?: default
        } catch (e: Exception) {
            default
        }
    }
}