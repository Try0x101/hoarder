package com.example.hoarder.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

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

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}