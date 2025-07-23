package com.example.hoarder.transport.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URI

object NetUtils {
    fun isValidServerAddress(address: String): Boolean {
        if (address.isBlank() || address.contains("://")) return false
        try {
            val uri = URI("my://$address")
            if (uri.host == null) return false
            if (uri.port != -1 && (uri.port <= 0 || uri.port > 65535)) return false
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}