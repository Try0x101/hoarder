package com.example.hoarder.data.processing

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object DeltaComputer {

    fun calculateDelta(previous: Map<String, Any>?, current: Map<String, Any>): Map<String, Any> {
        if (previous == null) {
            return current.toMutableMap()
        }

        val delta = mutableMapOf<String, Any>()

        for ((key, value) in current) {
            val previousValue = previous[key]
            if (previousValue == null || !valuesEqual(previousValue, value)) {
                delta[key] = value
            }
        }

        if (current.containsKey("id")) {
            delta["id"] = current["id"]!!
        }

        if (delta.size == 1 && delta.containsKey("id")) {
            return emptyMap()
        }

        return delta
    }

    fun generateJsonDelta(gson: Gson, lastUpload: String?, currentFrame: String): Pair<String?, Boolean> {
        if (lastUpload == null) return Pair(currentFrame, false)

        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val previous = gson.fromJson<Map<String, Any?>>(lastUpload, type)
            val current = gson.fromJson<Map<String, Any?>>(currentFrame, type)
            val delta = mutableMapOf<String, Any?>()

            for ((key, value) in current.entries) {
                if (!previous.containsKey(key) || previous[key] != value) {
                    delta[key] = value
                }
            }

            if (current.containsKey("id")) delta["id"] = current["id"]
            if (delta.keys == setOf("id") || delta.isEmpty()) return Pair(null, true)

            Pair(gson.toJson(delta), true)
        } catch (e: Exception) {
            Pair(currentFrame, false)
        }
    }

    private fun valuesEqual(v1: Any, v2: Any): Boolean {
        return when {
            v1 is Number && v2 is Number -> v1.toDouble() == v2.toDouble()
            v1 is String && v2 is String -> v1 == v2
            else -> v1.toString() == v2.toString()
        }
    }
}