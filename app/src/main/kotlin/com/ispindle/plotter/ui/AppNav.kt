package com.ispindle.plotter.ui

import com.ispindle.plotter.R

sealed class Dest(val route: String, val labelResId: Int) {
    data object Home : Dest("home", R.string.nav_home)
    data object Devices : Dest("devices", R.string.nav_devices)
    data object Graph : Dest("graph/{deviceId}", R.string.nav_graph) {
        fun of(deviceId: Long) = "graph/$deviceId"
    }
    data object Calibrate : Dest("calibrate/{deviceId}", R.string.nav_calibrate) {
        fun of(deviceId: Long) = "calibrate/$deviceId"
    }
    data object Setup : Dest("setup", R.string.nav_setup)
    data object Configure : Dest("configure", R.string.nav_configure)
}
