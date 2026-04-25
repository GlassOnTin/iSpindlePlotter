package com.ispindle.plotter.ui

sealed class Dest(val route: String, val label: String) {
    data object Home : Dest("home", "Home")
    data object Devices : Dest("devices", "Devices")
    data object Graph : Dest("graph/{deviceId}", "Graph") {
        fun of(deviceId: Long) = "graph/$deviceId"
    }
    data object Calibrate : Dest("calibrate/{deviceId}", "Calibrate") {
        fun of(deviceId: Long) = "calibrate/$deviceId"
    }
    data object Setup : Dest("setup", "Setup Guide")
    data object Configure : Dest("configure", "Configure")
}
