package com.ispindle.plotter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExclusionRangeDao {
    @Query("SELECT * FROM exclusion_ranges WHERE deviceId = :deviceId ORDER BY startMs ASC")
    fun observeForDevice(deviceId: Long): Flow<List<ExclusionRange>>

    /** Synchronous snapshot — used by settings backup. */
    @Query("SELECT * FROM exclusion_ranges WHERE deviceId = :deviceId ORDER BY startMs ASC")
    suspend fun listForDevice(deviceId: Long): List<ExclusionRange>

    @Insert
    suspend fun insert(range: ExclusionRange): Long

    @Delete
    suspend fun delete(range: ExclusionRange)

    @Query("DELETE FROM exclusion_ranges WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: Long)
}