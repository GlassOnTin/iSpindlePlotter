package com.ispindle.plotter.data

import android.content.Context
import java.net.URI

/**
 * Proxy mode. When [enabled] the app polls an always-on buffering proxy (see
 * the repo's `proxy/` dir) for readings the phone missed, instead of hosting
 * the local listener.
 *
 * The proxy address is found one of two ways:
 *  - [manualUrl] — a user-typed override (e.g. `http://192.168.0.2:9501`); or
 *  - mDNS discovery, whose result the service caches in [discoveredUrl] so
 *    other screens (the Configure flow) can target the proxy too.
 *
 * [effectiveUrl] is `manualUrl ?: discoveredUrl`. [cursor] is the id of the
 * last proxy record ingested, sent back as `?since=` so we only pull newer rows.
 */
object ProxyPrefs {
    private const val FILE = "ispindle_proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MANUAL_URL = "manual_url"
    private const val KEY_DISCOVERED_URL = "discovered_url"
    private const val KEY_CURSOR = "cursor"

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun manualUrl(ctx: Context): String? =
        prefs(ctx).getString(KEY_MANUAL_URL, null)?.takeIf { it.isNotBlank() }

    /** Stores the manual override (trailing slash trimmed); resets the cursor when it changes. */
    fun setManualUrl(ctx: Context, raw: String?) {
        val v = normalise(raw)
        val p = prefs(ctx)
        if (p.getString(KEY_MANUAL_URL, null) == v) return
        p.edit().apply {
            if (v == null) remove(KEY_MANUAL_URL) else putString(KEY_MANUAL_URL, v)
            putLong(KEY_CURSOR, 0L)
        }.apply()
    }

    fun discoveredUrl(ctx: Context): String? =
        prefs(ctx).getString(KEY_DISCOVERED_URL, null)?.takeIf { it.isNotBlank() }

    /**
     * Caches an mDNS-discovered address. Does NOT reset the cursor — a proxy
     * rediscovered after a network blip is the same buffer, so re-pulling from
     * 0 would duplicate everything. (Swapping to a genuinely different proxy is
     * the rare case; toggle proxy mode off/on or set a manual URL to reset.)
     */
    fun setDiscoveredUrl(ctx: Context, url: String?) {
        val v = normalise(url)
        val p = prefs(ctx)
        if (p.getString(KEY_DISCOVERED_URL, null) == v) return
        p.edit().apply {
            if (v == null) remove(KEY_DISCOVERED_URL) else putString(KEY_DISCOVERED_URL, v)
        }.apply()
    }

    /** Manual override if set, else the last mDNS-discovered address, else null. */
    fun effectiveUrl(ctx: Context): String? = manualUrl(ctx) ?: discoveredUrl(ctx)

    fun cursor(ctx: Context): Long = prefs(ctx).getLong(KEY_CURSOR, 0L)

    fun setCursor(ctx: Context, c: Long) {
        prefs(ctx).edit().putLong(KEY_CURSOR, c).apply()
    }

    /** Host of [effectiveUrl], for pointing the iSpindle's server target at the proxy. */
    fun host(ctx: Context): String? =
        effectiveUrl(ctx)?.let { runCatching { URI(it).host }.getOrNull() }

    /** Explicit port of [effectiveUrl], or null. */
    fun port(ctx: Context): Int? =
        effectiveUrl(ctx)?.let { runCatching { URI(it).port.takeIf { p -> p > 0 } }.getOrNull() }

    private fun normalise(raw: String?): String? =
        raw?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
