package com.example.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    data class NetworkIpCandidate(
        val interfaceName: String,
        val ipAddress: String,
        val isWifi: Boolean,
        val isEthernet: Boolean
    )

    /**
     * Gets all valid IPv4 addresses on the device (excluding loopback).
     */
    fun getAllIpCandidates(): List<NetworkIpCandidate> {
        val list = mutableListOf<NetworkIpCandidate>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nInterface = interfaces.nextElement()
                if (nInterface.isLoopback || !nInterface.isUp) continue

                val name = nInterface.name.lowercase()
                val isWifi = name.contains("wlan") || name.contains("wifi") || name.contains("ap")
                val isEth = name.contains("eth")

                val addresses = nInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val host = address.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            list.add(
                                NetworkIpCandidate(
                                    interfaceName = nInterface.name,
                                    ipAddress = host,
                                    isWifi = isWifi,
                                    isEthernet = isEth
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating interfaces: ${e.message}")
        }
        return list
    }

    /**
     * Finds the active IPv4 address on the Wi-Fi or active network interface,
     * prioritizing the subnet that matches target TV IP if provided.
     */
    fun getLocalIpAddress(context: Context, targetTvIp: String? = null): String {
        val candidates = getAllIpCandidates()
        if (candidates.isEmpty()) {
            return getFallbackWifiManagerIp(context)
        }

        // 1. If target TV IP is known, match same subnet (e.g. 192.168.1.X)
        if (!targetTvIp.isNullOrBlank() && targetTvIp.contains(".")) {
            val tvSubnet = targetTvIp.substringBeforeLast(".") + "."
            val match = candidates.firstOrNull { it.ipAddress.startsWith(tvSubnet) }
            if (match != null) {
                Log.d(TAG, "Matched subnet with TV ($targetTvIp): ${match.ipAddress} on ${match.interfaceName}")
                return match.ipAddress
            }
        }

        // 2. Prioritize Wi-Fi interfaces (wlan0, wlan1)
        val wifiMatch = candidates.firstOrNull { it.isWifi && isPrivateNetwork(it.ipAddress) }
            ?: candidates.firstOrNull { it.isWifi }
        if (wifiMatch != null) {
            return wifiMatch.ipAddress
        }

        // 3. Prioritize Ethernet (eth0)
        val ethMatch = candidates.firstOrNull { it.isEthernet }
        if (ethMatch != null) {
            return ethMatch.ipAddress
        }

        // 4. Any private network IP (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
        val privateMatch = candidates.firstOrNull { isPrivateNetwork(it.ipAddress) }
        if (privateMatch != null) {
            return privateMatch.ipAddress
        }

        return candidates.first().ipAddress
    }

    private fun isPrivateNetwork(ip: String): Boolean {
        return ip.startsWith("192.168.") || ip.startsWith("10.") ||
                (ip.startsWith("172.") && try {
                    val second = ip.split(".")[1].toInt()
                    second in 16..31
                } catch (_: Exception) { false })
    }

    private fun getFallbackWifiManagerIp(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
