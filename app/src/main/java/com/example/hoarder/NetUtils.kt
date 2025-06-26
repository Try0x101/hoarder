package com.example.hoarder

object NetUtils {
    fun isValidIpPort(ip: String): Boolean {
        if (ip.isBlank()) return false

        val parts = ip.split(":")
        if (parts.size != 2) return false

        val addr = parts[0]
        val port = parts[1].toIntOrNull()

        val ips = addr.split(".")
        if (ips.size != 4) return false

        for (part in ips) {
            val n = part.toIntOrNull()
            if (n == null || n < 0 || n > 255) return false
        }

        if (port == null || port <= 0 || port > 65535) return false

        return true
    }
}