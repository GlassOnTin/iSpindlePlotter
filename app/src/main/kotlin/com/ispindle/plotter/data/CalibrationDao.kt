package com.ispindle.plotter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationDao {
    @Insert
    suspend fun insert(point: CalibrationPoint): Long

    @Update
    suspend fun update(point: CalibrationPoint)

    @Delete
    suspend fun delete(point: CalibrationPoint)

    @Query("SELECT * FROM calibration_points WHERE deviceId = :deviceId ORDER BY angle ASC")
    fun observeForDevice(deviceId: Long): Flow<List<CalibrationPoint>>

    @Query("SELECT * FROM calibration_points WHERE deviceId = :deviceId AND enabled = 1 ORDER BY angle ASC")
    suspend fun enabledForDevice(deviceId: Long): List<CalibrationPoint>
}
