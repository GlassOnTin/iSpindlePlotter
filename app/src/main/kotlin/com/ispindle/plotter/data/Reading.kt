package com.ispindle.plotter.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "readings",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("deviceId"), Index("timestampMs")]
)
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val timestampMs: Long,
    val angle: Double,
    val temperatureC: Double,
    val batteryV: Double,
    val rssi: Int?,
    val intervalS: Int?,
    val reportedGravity: Double?,
    val computedGravity: Double?,
    val tempUnits: String = "C"
)
