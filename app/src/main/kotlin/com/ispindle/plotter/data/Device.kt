package com.ispindle.plotter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A physical iSpindel, keyed by its firmware-reported ID. The polynomial
 * coefficients hold the app's own calibration (a + b*x + c*x^2 + d*x^3)
 * used to derive gravity from tilt angle. calDegree == 0 means "no
 * calibration — fall back to whatever the firmware sent, if anything".
 */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hwId: Int,
    val reportedName: String,
    val userLabel: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val calA: Double = 0.0,
    val calB: Double = 0.0,
    val calC: Double = 0.0,
    val calD: Double = 0.0,
    val calDegree: Int = 0,
    val calRSquared: Double? = null,
    val lastSeenIp: String? = null
)
