package com.ispindle.plotter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert
    suspend fun insert(reading: Reading): Long

    @Query("SELECT * FROM readings WHERE deviceId = :deviceId ORDER BY timestampMs ASC")
    fun observeForDevice(deviceId: Long): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE deviceId = :deviceId ORDER BY timestampMs DESC LIMIT 1")
    fun observeLatestForDevice(deviceId: Long): Flow<Reading?>

    @Query("SELECT * FROM readings ORDER BY timestampMs DESC LIMIT 1")
    fun observeLatestAny(): Flow<Reading?>

    @Query("SELECT COUNT(*) FROM readings WHERE deviceId = :deviceId")
    suspend fun countForDevice(deviceId: Long): Int

    @Query("DELETE FROM readings WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: Long)

    @Query("SELECT * FROM readings WHERE deviceId = :deviceId ORDER BY timestampMs ASC")
    suspend fun listForDevice(deviceId: Long): List<Reading>

    /** All timestamps for a device — used by CSV import to dedupe rows. */
    @Query("SELECT timestampMs FROM readings WHERE deviceId = :deviceId")
    suspend fun timestampsFor(deviceId: Long): List<Long>
}
