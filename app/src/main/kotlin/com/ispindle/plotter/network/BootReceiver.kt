package com.ispindle.plotter.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the HTTP server back up after a phone reboot or after the app
 * is upgraded in place. ACTION_BOOT_COMPLETED is one of the documented
 * exemptions to Android 12+'s "no FGS from background" rule, so the
 * service can legally raise its notification here without the user
 * touching the screen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Boot/upgrade received; starting server if enabled")
                IspindleServerService.startIfEnabled(context)
            }
        }
    }

    private companion object {
        const val TAG = "IspindleBootReceiver"
    }
}
