package com.ispindle.plotter.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exclusion-range contract of [Repository] without standing up a
 * real Room database (androidTest / Robolectric — out of scope). Same
 * hand-written-DAO-fakes pattern as RepositoryImportReadingsTest.
 */
class RepositoryExclusionsTest {

    @Test fun `add stores the committed range with the owning device`() = runBlocking {
        val dao = FakeExclusionRangeDao()
        val repo = repo(dao)
        val ok = repo.addExclusionRange(deviceId = 7, startMs = 1_000, endMs = 2_000)
        assertTrue(ok)
        assertEquals(1, dao.rows.size)
        val stored = dao.rows.single()
        assertEquals(7L, stored.deviceId)
        assertEquals(1_000L, stored.startMs)
        assertEquals(2_000L, stored.endMs)
    }

    @Test fun `degenerate or reversed adds are no-ops`() = runBlocking {
        val dao = FakeExclusionRangeDao()
        val repo = repo(dao)
        assertFalse(repo.addExclusionRange(7, 500, 500))
        assertFalse(repo.addExclusionRange(7, 600, 100))
        assertEquals("no rows stored for degenerate adds", 0, dao.rows.size)
    }

    @Test fun `delete removes the range`() = runBlocking {
        val dao = FakeExclusionRangeDao()
        val repo = repo(dao)
        repo.addExclusionRange(7, 1_000, 2_000)
        val stored = dao.rows.single()
        repo.deleteExclusionRange(stored)
        assertEquals(0, dao.rows.size)
    }

    @Test fun `restore replaces ranges and rebases deviceId`() = runBlocking {
        val dao = FakeExclusionRangeDao()
        val devices = InMemoryDeviceDao()
        val repo = Repository(
            deviceDao = devices,
            readingDao = StubReadingDao(),
            calibrationDao = StubCalibrationDao(),
            exclusionRangeDao = dao,
            database = null
        )
        // Marks already on this install (with a real auto id) should be
        // dropped and replaced by the backup's rows, rebased to the device
        // the restore created.
        dao.rows += ExclusionRange(id = 11, deviceId = 1, startMs = 1, endMs = 2, createdMs = 3)
        val hwId = 1234
        repo.restoreDeviceSettings(
            hwId = hwId,
            reportedName = "Spindle",
            userLabel = "Backup label",
            calA = 0.0, calB = 0.0, calC = 0.0, calD = 0.0,
            calDegree = 0, calRSquared = null,
            calibrationPoints = emptyList(),
            exclusionRanges = listOf(
                ExclusionRange(id = 99, deviceId = 0, startMs = 10_000, endMs = 20_000, createdMs = 5)
            )
        )
        val deviceId = devices.findByHwId(hwId)!!.id
        assertEquals(1, dao.rows.size)
        val restored = dao.rows.single()
        assertEquals(deviceId, restored.deviceId)
        assertEquals("backup's id must not be carried over (fresh auto id)", 1L, restored.id)
        assertEquals(10_000L, restored.startMs)
        assertEquals(20_000L, restored.endMs)
    }

    @Test fun `restore with no ranges clears existing marks`() = runBlocking {
        val dao = FakeExclusionRangeDao()
        val devices = InMemoryDeviceDao()
        val repo = Repository(
            deviceDao = devices,
            readingDao = StubReadingDao(),
            calibrationDao = StubCalibrationDao(),
            exclusionRangeDao = dao,
            database = null
        )
        dao.rows += ExclusionRange(id = 11, deviceId = 1, startMs = 1, endMs = 2, createdMs = 3)
        repo.restoreDeviceSettings(
            hwId = 1234,
            reportedName = "Spindle",
            userLabel = "Backup label",
            calA = 0.0, calB = 0.0, calC = 0.0, calD = 0.0,
            calDegree = 0, calRSquared = null,
            calibrationPoints = emptyList()
        )
        assertEquals(0, dao.rows.size)
    }

    private fun repo(exclusionRangeDao: ExclusionRangeDao) = Repository(
        deviceDao = InMemoryDeviceDao(),
        readingDao = StubReadingDao(),
        calibrationDao = StubCalibrationDao(),
        exclusionRangeDao = exclusionRangeDao,
        database = null
    )

    /** Captures every row mutably so tests can seed + inspect. */
    private class FakeExclusionRangeDao : ExclusionRangeDao {
        val rows = mutableListOf<ExclusionRange>()
        val deleted = mutableListOf<ExclusionRange>()

        override suspend fun insert(range: ExclusionRange): Long {
            val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
            rows += range.copy(id = id)
            return id
        }

        override suspend fun delete(range: ExclusionRange) {
            deleted += range
            rows.removeAll { it.id == range.id }
        }

        override suspend fun deleteForDevice(deviceId: Long) {
            rows.removeAll { it.deviceId == deviceId }
        }

        override fun observeForDevice(deviceId: Long): Flow<List<ExclusionRange>> =
            flowOf(rows.filter { it.deviceId == deviceId })

        override suspend fun listForDevice(deviceId: Long): List<ExclusionRange> =
            rows.filter { it.deviceId == deviceId }.sortedBy { it.startMs }
    }

    /** A stub with only the no-op methods the tests never exercise. */
    private class StubReadingDao : ReadingDao {
        override fun observeForDevice(deviceId: Long): Flow<List<Reading>> = flowOf(emptyList())
        override fun observeLatestForDevice(deviceId: Long): Flow<Reading?> = flowOf(null)
        override fun observeLatestAny(): Flow<Reading?> = flowOf(null)
        override suspend fun countForDevice(deviceId: Long): Int = 0
        override suspend fun deleteForDevice(deviceId: Long) { /* no-op */ }
        override suspend fun deleteForDeviceInRange(deviceId: Long, startMs: Long, endMs: Long) { /* no-op */ }
        override suspend fun listForDevice(deviceId: Long): List<Reading> = emptyList()
        override suspend fun timestampsFor(deviceId: Long): List<Long> = emptyList()
        override suspend fun insert(reading: Reading): Long = 1L
        override suspend fun insertAll(readings: List<Reading>): List<Long> =
            readings.indices.map { it.toLong() + 1 }
    }

    private class StubCalibrationDao : CalibrationDao {
        override suspend fun insert(point: CalibrationPoint): Long = 1L
        override suspend fun update(point: CalibrationPoint) { /* no-op */ }
        override suspend fun delete(point: CalibrationPoint) { /* no-op */ }
        override fun observeForDevice(deviceId: Long): Flow<List<CalibrationPoint>> = flowOf(emptyList())
        override suspend fun enabledForDevice(deviceId: Long): List<CalibrationPoint> = emptyList()
        override suspend fun listForDevice(deviceId: Long): List<CalibrationPoint> = emptyList()
    }

    /** Stores devices so restoreDeviceSettings' find/insert/update paths run. */
    private class InMemoryDeviceDao : DeviceDao {
        private val byId = LinkedHashMap<Long, Device>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<Device>> = flowOf(emptyList())
        override fun observeById(id: Long): Flow<Device?> = flowOf(byId[id])
        override suspend fun findById(id: Long): Device? = byId[id]
        override suspend fun findByHwId(hwId: Int): Device? =
            byId.values.firstOrNull { it.hwId == hwId }
        override suspend fun listAll(): List<Device> = byId.values.toList()

        override suspend fun insert(device: Device): Long {
            val id = nextId++
            byId[id] = device.copy(id = id)
            return id
        }

        override suspend fun update(device: Device) {
            byId[device.id] = device
        }
        override suspend fun touch(id: Long, t: Long) { /* no-op */ }
        override suspend fun touchWithIp(id: Long, t: Long, ip: String?) { /* no-op */ }
        override suspend fun deleteById(id: Long) { byId.remove(id) }
        override suspend fun updateCalibration(
            id: Long, a: Double, b: Double, c: Double, d: Double,
            degree: Int, r2: Double?
        ) { /* no-op */ }
    }
}