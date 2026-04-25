package com.ispindle.plotter.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Drives the [WifiNetworkSpecifier] handshake to join an iSpindel access point.
 *
 * Modern Android (10+) exposes only this API for app-driven WiFi joins. The
 * system shows the user an approval dialog the first time we request a given
 * SSID; once accepted, the network is bound to the calling process for as
 * long as we hold the [ConnectivityManager.NetworkCallback]. Releasing the
 * Flow's collector cancels the request and the OS reverts to the previous
 * default network — typically your home WiFi.
 *
 * The default SSID prefix is "iSpindel" because the firmware names its AP
 * `iSpindel_<chipID>` (see iSpindel.cpp:394 in universam1/iSpindel).
 */
object IspindleApBinder {

    sealed class State {
        data object Requesting : State()
        data class Connected(val network: Network) : State()
        data class Lost(val reason: String) : State()
        data object Unsupported : State()
    }

    /**
     * Returns a hot Flow that emits state transitions until the collector is
     * cancelled. Cancellation removes the network request, releasing the AP.
     *
     * @param ssidPrefix matches networks whose SSID starts with this string;
     *                   the system dialog lets the user pick the exact one.
     * @param exactSsid  if non-null, takes precedence and matches a single SSID.
     * @param passphrase WPA2 passphrase, or null for an open network (default).
     */
    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        ssidPrefix: String = "iSpindel",
        exactSsid: String? = null,
        passphrase: String? = null
    ): Flow<State> = callbackFlow {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            trySend(State.Unsupported)
            close()
            return@callbackFlow
        }

        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val cleanedExact = exactSsid?.trim().takeUnless { it.isNullOrEmpty() }
        val cleanedPrefix = ssidPrefix.trim().ifEmpty { "iSpindel" }
        val cleanedPass = passphrase?.takeUnless { it.isEmpty() }

        val request = try {
            val specBuilder = WifiNetworkSpecifier.Builder().apply {
                if (cleanedExact != null) {
                    setSsid(cleanedExact)
                } else {
                    setSsidPattern(PatternMatcher(cleanedPrefix, PatternMatcher.PATTERN_PREFIX))
                }
                if (cleanedPass != null) setWpa2Passphrase(cleanedPass)
            }
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specBuilder.build())
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "Building NetworkRequest failed", t)
            trySend(State.Lost("Invalid AP settings: ${t.message ?: t.javaClass.simpleName}"))
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "AP onAvailable: $network")
                trySend(State.Connected(network))
            }
            override fun onUnavailable() {
                Log.w(TAG, "AP onUnavailable")
                trySend(State.Lost("Unavailable — user cancelled or no matching SSID in range"))
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "AP onLost: $network")
                trySend(State.Lost("Connection lost"))
            }
        }

        trySend(State.Requesting)
        try {
            cm.requestNetwork(request, callback, REQUEST_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "requestNetwork failed", t)
            trySend(State.Lost(t.message ?: "requestNetwork failed"))
            close(t)
            return@callbackFlow
        }

        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
                // Already torn down — fine.
            }
        }
    }

    private const val TAG = "IspindleApBinder"
    private const val REQUEST_TIMEOUT_MS = 30_000
}
