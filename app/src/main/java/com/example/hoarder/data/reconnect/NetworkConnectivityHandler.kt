package com.example.hoarder.data.reconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean

class NetworkConnectivityHandler(
    context: Context,
    private val onReconnect: () -> Unit
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wasOffline = AtomicBoolean(true)
    private val networkCallbackRegistered = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (wasOffline.getAndSet(false)) {
                onReconnect()
            }
        }
        override fun onLost(network: Network) {
            wasOffline.set(true)
        }
    }

    fun start() {
        if (networkCallbackRegistered.compareAndSet(false, true)) {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        }
    }

    fun stop() {
        if (networkCallbackRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
            }
        }
    }

    fun setWasOffline(isOffline: Boolean) = wasOffline.set(isOffline)
    fun getWasOffline(): Boolean = wasOffline.get()
}