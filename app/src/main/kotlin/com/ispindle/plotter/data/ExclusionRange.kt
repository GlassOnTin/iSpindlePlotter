package com.ispindle.plotter.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-marked time span of a device's readings that should be ignored by
 * plots and model fitting (transport bumps, cleaning tilts, garbage from
 * fringe WiFi) — shaded on the charts, excluded from fits, but never
 * deleted.
 *
 * Rows are stored exactly as committed: overlapping drags stay separate so
 * deleting one never destroys a neighbour's span. Display/filter layers
 * coalesce (analysis/Exclusions.kt).
 */
@Entity(
    tableName = "exclusion_ranges",
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
data class ExclusionRange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    /** Inclusive start of the ignored span (epoch-ms). */
    val startMs: Long,
    /** Inclusive end of the ignored span (epoch-ms). */
    val endMs: Long,
    val createdMs: Long
)