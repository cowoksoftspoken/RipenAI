package com.ripenai.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Connects to an open ESP32 AP and binds local HTTP traffic to that network. */
class FarmerWifiConnector(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    fun suggestOpenNetwork(ssid: String): Boolean {
        if (ssid.isBlank()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val suggestion = WifiNetworkSuggestion.Builder().setSsid(ssid.trim()).build()
                wifiManager.addNetworkSuggestions(listOf(suggestion)) == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                val configuration = WifiConfiguration().apply { SSID = "\"${ssid.trim()}\"" }
                @Suppress("DEPRECATION")
                wifiManager.addNetwork(configuration) != -1
            }
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun <T> withLocalNetwork(ssid: String, block: suspend () -> T): T {
        if (ssid.isBlank() || isAlreadyConnected(ssid)) return block()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            suggestOpenNetwork(ssid)
            return block()
        }

        val network = requestNetwork(ssid) ?: return block()
        return try {
            connectivityManager.bindProcessToNetwork(network)
            block()
        } finally {
            connectivityManager.bindProcessToNetwork(null)
        }
    }

    private fun isAlreadyConnected(ssid: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info: WifiInfo? = wifiManager.connectionInfo
            info?.ssid?.trim('"') == ssid.trim()
        } catch (_: SecurityException) {
            false
        }
    }

    private suspend fun requestNetwork(ssid: String): Network? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid.trim()).build())
            .build()
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) continuation.resume(network)
                    }

                    override fun onUnavailable() {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                try {
                    connectivityManager.requestNetwork(request, callback)
                    continuation.invokeOnCancellation { connectivityManager.unregisterNetworkCallback(callback) }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
}
