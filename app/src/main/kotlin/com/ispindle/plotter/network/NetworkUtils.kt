package com.ispindle.plotter.network

import java.net.Inet4Address
import java.net.InetAddress
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

    /**
     * Asks the system resolver for the PTR (reverse DNS) of an IPv4 address.
     * Many home routers running dnsmasq (or a similar resolver) publish a
     * DNS name for every DHCP client; this is how we discover that name
     * from the IP, so the iSpindle can be configured against a stable
     * hostname rather than a raw address.
     *
     * Returns null if no PTR is registered, or if the response is just the
     * IP echoed back (which Java's resolver does when the lookup fails).
     */
    fun reverseLookup(ip: String): String? {
        return try {
            val addr = InetAddress.getByName(ip)
            val name = addr.canonicalHostName
            if (name.equals(ip, ignoreCase = true)) null
            else name.trimEnd('.').takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves [hostname] forward to all configured IPv4 addresses. Used to
     * sanity-check that a hostname suggested by reverse DNS still points at
     * the phone — a router can return a stale name that resolves to a
     * sibling device's IP if multiple clients have advertised it.
     */
    fun forwardLookupIpv4(hostname: String): List<String> {
        return try {
            InetAddress.getAllByName(hostname)
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
