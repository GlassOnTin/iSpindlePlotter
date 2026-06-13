package com.ispindle.plotter.network

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Finds the iSpindle proxy on the LAN via mDNS / DNS-SD, so the user doesn't
 * have to type its address. The proxy advertises `_ispindle-proxy._tcp` (see
 * the repo's `proxy/` dir); we resolve the first instance to `http://host:port`.
 *
 * Returns null on timeout / no proxy found — the caller falls back to the
 * manual URL.
 */
object ProxyDiscovery {
    private const val TAG = "ProxyDiscovery"
    const val SERVICE_TYPE = "_ispindle-proxy._tcp."

    suspend fun discover(nsd: NsdManager, timeoutMs: Long): String? {
        var listener: NsdManager.DiscoveryListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    // Guard so we resolve only the first hit — NsdManager rejects
                    // overlapping resolveService calls (FAILURE_ALREADY_ACTIVE).
                    val resolving = AtomicBoolean(false)
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolve failed: $errorCode")
                            resolving.set(false)
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress
                            if (host != null && cont.isActive) {
                                cont.resume("http://$host:${info.port}")
                            } else {
                                resolving.set(false)
                            }
                        }
                    }
                    val dl = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onServiceFound(service: NsdServiceInfo) {
                            if (resolving.compareAndSet(false, true)) {
                                runCatching { nsd.resolveService(service, resolveListener) }
                                    .onFailure { resolving.set(false) }
                            }
                        }

                        override fun onServiceLost(service: NsdServiceInfo) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            Log.w(TAG, "discovery start failed: $errorCode")
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    }
                    listener = dl
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
                }
            }
        } finally {
            // Always stop discovery — on success, timeout, or cancellation.
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }
}
