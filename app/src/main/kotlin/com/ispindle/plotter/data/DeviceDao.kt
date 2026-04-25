package com.ispindle.plotter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM devices WHERE id = :id")
    fun observeById(id: Long): Flow<Device?>

    @Query("SELECT * FROM devices WHERE hwId = :hwId LIMIT 1")
    suspend fun findByHwId(hwId: Int): Device?

    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Device?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(device: Device): Long

    @Update
    suspend fun update(device: Device)

    @Query("UPDATE devices SET lastSeenMs = :t WHERE id = :id")
    suspend fun touch(id: Long, t: Long)

    @Query("UPDATE devices SET lastSeenMs = :t, lastSeenIp = :ip WHERE id = :id")
    suspend fun touchWithIp(id: Long, t: Long, ip: String?)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE devices SET calA = :a, calB = :b, calC = :c, calD = :d, calDegree = :degree, calRSquared = :r2 WHERE id = :id")
    suspend fun updateCalibration(
        id: Long,
        a: Double, b: Double, c: Double, d: Double,
        degree: Int,
        r2: Double?
    )
}
