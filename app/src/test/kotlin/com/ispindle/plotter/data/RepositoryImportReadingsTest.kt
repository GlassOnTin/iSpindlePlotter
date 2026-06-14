package com.ispindle.plotter.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository.importReadings was doing one DAO insert per row, each in its
 * own Room transaction; the live Flow observers then re-queried after
 * every commit. A 15 k-row CSV import took hours. The fix batches the
 * keep-set into a single insertAll call wrapped in withTransaction.
 *
 * These tests pin the contract without standing up a real Room database
 * (that needs androidTest / Robolectric — out of scope here). The fakes
 * just count which DAO methods got called.
 */
class RepositoryImportReadingsTest {

    @Test fun `import path returns correct insert and skip counts`() = runBlocking {
        // Fallback path (database = null) is the only path testable in pure
        // JVM tests; the bulk withTransaction + insertAll path needs a real
        // Room database and lives behind an instrumentation test. Both
        // produce the same (inserted, skipped) tally — which is what every
        // caller above the repo actually observes.
        val fake = FakeReadingDao()
        val repo = Repository(
            deviceDao = StubDeviceDao(),
            readingDao = fake,
            calibrationDao = StubCalibrationDao(),
            database = null
        )
        val rows = (1..1_000L).map { r(deviceId = 5, timestampMs = it) }
        val (inserted, skipped) = repo.importReadings(deviceId = 5, readings = rows)
        assertEquals(1_000, inserted)
        assertEquals(0, skipped)
        assertEquals("fallback should use per-row insert when database is null",
            1_000, fake.singleInsertCount)
        assertEquals(0, fake.batchInsertCount)
    }

    @Test fun `duplicate timestamps in the input batch are skipped, not double-inserted`() = runBlocking {
        val fake = FakeReadingDao()
        val repo = Repository(
            deviceDao = StubDeviceDao(),
            readingDao = fake,
            calibrationDao = StubCalibrationDao(),
            database = null
        )
        val rows = listOf(
            r(deviceId = 1, timestampMs = 100),
            r(deviceId = 1, timestampMs = 100),  // duplicate within batch
            r(deviceId = 1, timestampMs = 200),
            r(deviceId = 1, timestampMs = 300),
            r(deviceId = 1, timestampMs = 200)   // duplicate within batch
        )
        val (inserted, skipped) = repo.importReadings(deviceId = 1, readings = rows)
        assertEquals(3, inserted)
        assertEquals(2, skipped)
        assertEquals(3, fake.singleInsertCount)
    }

    @Test fun `timestamps already in the DAO are skipped`() = runBlocking {
        val fake = FakeReadingDao(existingTimestamps = listOf(100L, 200L))
        val repo = Repository(
            deviceDao = StubDeviceDao(),
            readingDao = fake,
            calibrationDao = StubCalibrationDao(),
            database = null
        )
        val rows = (1..5L).map { r(deviceId = 1, timestampMs = it * 100) }
        val (inserted, skipped) = repo.importReadings(deviceId = 1, readings = rows)
        assertEquals(3, inserted)
        assertEquals(2, skipped)
    }

    @Test fun `forces deviceId on imported rows to the target device`() = runBlocking {
        val fake = FakeReadingDao()
        val repo = Repository(
            deviceDao = StubDeviceDao(),
            readingDao = fake,
            calibrationDao = StubCalibrationDao(),
            database = null
        )
        // Import a row that claims a different deviceId in its payload.
        repo.importReadings(deviceId = 42, readings = listOf(r(deviceId = 99, timestampMs = 100)))
        assertEquals(1, fake.singleInserted.size)
        assertEquals(
            "imported row's deviceId should be rewritten to the target",
            42L, fake.singleInserted.first().deviceId
        )
    }

    /**
     * Counts how often each DAO method gets called and what rows it sees.
     * No real Room here — just a record + return-empty implementation.
     */
    private class FakeReadingDao(
        existingTimestamps: List<Long> = emptyList()
    ) : ReadingDao {
        private val existing = existingTimestamps.toMutableList()
        val singleInserted = mutableListOf<Reading>()
        var singleInsertCount: Int = 0
            private set
        var batchInsertCount: Int = 0
            private set
        val batchInserted = mutableListOf<List<Reading>>()

        override suspend fun insert(reading: Reading): Long {
            singleInsertCount++
            singleInserted += reading
            existing += reading.timestampMs
            return singleInsertCount.toLong()
        }

        override suspend fun insertAll(readings: List<Reading>): List<Long> {
            batchInsertCount++
            batchInserted += readings
            readings.forEach { existing += it.timestampMs }
            return readings.indices.map { (singleInsertCount + it + 1).toLong() }
        }

        override fun observeForDevice(deviceId: Long): Flow<List<Reading>> = flowOf(emptyList())
        override fun observeLatestForDevice(deviceId: Long): Flow<Reading?> = flowOf(null)
        override fun observeLatestAny(): Flow<Reading?> = flowOf(null)
        override suspend fun countForDevice(deviceId: Long): Int = existing.size
        override suspend fun deleteForDevice(deviceId: Long) { existing.clear() }
        override suspend fun deleteForDeviceInRange(
            deviceId: Long,
            startMs: Long,
            endMs: Long
        ) {
            existing.removeAll { it in startMs..endMs }
        }
        override suspend fun listForDevice(deviceId: Long): List<Reading> = emptyList()
        override suspend fun timestampsFor(deviceId: Long): List<Long> = existing.toList()
    }

    private class StubDeviceDao : DeviceDao {
        override fun observeAll(): Flow<List<Device>> = flowOf(emptyList())
        override fun observeById(id: Long): Flow<Device?> = flowOf(null)
        override suspend fun findById(id: Long): Device? = null
        override suspend fun findByHwId(hwId: Int): Device? = null
        override suspend fun listAll(): List<Device> = emptyList()
        override suspend fun insert(device: Device): Long = 1L
        override suspend fun update(device: Device) { /* no-op */ }
        override suspend fun touch(id: Long, t: Long) { /* no-op */ }
        override suspend fun touchWithIp(id: Long, t: Long, ip: String?) { /* no-op */ }
        override suspend fun deleteById(id: Long) { /* no-op */ }
        override suspend fun updateCalibration(
            id: Long, a: Double, b: Double, c: Double, d: Double,
            degree: Int, r2: Double?
        ) { /* no-op */ }
    }

    private class StubCalibrationDao : CalibrationDao {
        override suspend fun insert(point: CalibrationPoint): Long = 1L
        override suspend fun update(point: CalibrationPoint) { /* no-op */ }
        override suspend fun delete(point: CalibrationPoint) { /* no-op */ }
        override fun observeForDevice(deviceId: Long): Flow<List<CalibrationPoint>> = flowOf(emptyList())
        override suspend fun enabledForDevice(deviceId: Long): List<CalibrationPoint> = emptyList()
        override suspend fun listForDevice(deviceId: Long): List<CalibrationPoint> = emptyList()
    }

    private fun r(deviceId: Long, timestampMs: Long): Reading = Reading(
        id = 0,
        deviceId = deviceId,
        timestampMs = timestampMs,
        angle = 35.0,
        temperatureC = 20.0,
        batteryV = 3.85,
        rssi = -60,
        intervalS = 60,
        reportedGravity = null,
        computedGravity = 1.020,
        tempUnits = "C"
    )
}
