package com.ispindle.plotter.data

import android.content.Context

/**
 * Single boolean: did the user want the HTTP server running?
 *
 * Touched from three places — the Home tab's Start/Stop buttons, the
 * BootReceiver, and MainActivity's onCreate auto-start. Defaults to true
 * so a fresh install just works; the user has to explicitly tap Stop to
 * suppress the auto-start paths.
 */
object ServerPrefs {
    private const val FILE = "ispindle_server"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
