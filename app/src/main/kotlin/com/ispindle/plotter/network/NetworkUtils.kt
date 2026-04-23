package com.ispindle.plotter.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Best-effort non-loopback IPv4 for the WiFi / primary network. */
    fun preferredIpv4(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            val rank = when {
                iface.name.startsWith("wlan") -> 0
                iface.name.startsWith("ap") -> 1
                iface.name.startsWith("rndis") -> 2
                iface.name.startsWith("eth") -> 3
                else -> 4
            }
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    candidates += rank to addr.hostAddress.orEmpty()
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second?.takeIf { it.isNotBlank() }
    }
}
