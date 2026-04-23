package com.ispindle.plotter.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calibration_points",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("deviceId")]
)
data class CalibrationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val angle: Double,
    val knownSG: Double,
    val note: String = "",
    val createdMs: Long,
    val enabled: Boolean = true
)
