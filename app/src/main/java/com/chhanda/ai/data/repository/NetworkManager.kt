package com.chhanda.ai.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val _allIps = MutableStateFlow<List<String>>(emptyList())
    val allIps: StateFlow<List<String>> = _allIps.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isConnected.value = true
            updateNetworkInfo()
        }

        override fun onLost(network: Network) {
            _isConnected.value = connectivityManager.activeNetwork != null
            updateNetworkInfo()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _isVpnActive.value = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            updateNetworkInfo()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        updateNetworkInfo()
    }

    fun refreshNetwork() {
        updateNetworkInfo()
    }

    private fun updateNetworkInfo() {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val ip = addr.hostAddress ?: ""
                        if (!ip.contains(":")) { // IPv4 only for now
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        _allIps.value = if (ips.isEmpty()) listOf("127.0.0.1") else ips
    }

    fun getBestIp(): String {
        val ips = _allIps.value
        if (ips.isEmpty() || (ips.size == 1 && ips.first() == "127.0.0.1")) return "127.0.0.1"
        return ips.find { it.startsWith("192.") || it.startsWith("10.") || it.startsWith("172.") } ?: ips.first()
    }
}
