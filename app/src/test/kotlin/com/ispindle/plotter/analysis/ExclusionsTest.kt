package com.ispindle.plotter.analysis

import com.ispindle.plotter.data.ExclusionRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the coalesce/filter logic behind the graph screen's "bad data"
 * marks. Pure JVM: the object must not touch Android classes.
 */
class ExclusionsTest {

    private fun range(startMs: Long, endMs: Long) =
        ExclusionRange(deviceId = 1, startMs = startMs, endMs = endMs, createdMs = 0)

    @Test fun `empty input coalesces to empty`() {
        assertEquals(emptyList<ExclusionRange>(), Exclusions.coalesce(emptyList()))
    }

    @Test fun `disjoint ranges are preserved in sorted order`() {
        val out = Exclusions.coalesce(listOf(range(100, 200), range(10, 20), range(50, 60)))
        assertEquals(listOf(range(10, 20), range(50, 60), range(100, 200)), out)
    }

    @Test fun `overlapping ranges merge`() {
        val out = Exclusions.coalesce(listOf(range(100, 200), range(150, 300)))
        assertEquals(listOf(range(100, 300)), out)
    }

    @Test fun `touching ranges merge (end equals start)`() {
        val out = Exclusions.coalesce(listOf(range(100, 200), range(200, 300)))
        assertEquals(listOf(range(100, 300)), out)
    }

    @Test fun `one millisecond gap keeps ranges separate`() {
        val out = Exclusions.coalesce(listOf(range(100, 200), range(201, 300)))
        assertEquals(listOf(range(100, 200), range(201, 300)), out)
    }

    @Test fun `reversed endpoints are normalised before sorting`() {
        val out = Exclusions.coalesce(listOf(range(300, 100), range(500, 400)))
        assertEquals(listOf(range(100, 300), range(400, 500)), out)
    }

    @Test fun `chain of overlapping ranges collapses to one`() {
        val out = Exclusions.coalesce(listOf(range(0, 10), range(5, 15), range(12, 20)))
        assertEquals(listOf(range(0, 20)), out)
    }

    @Test fun `nested range extends the enclosing span`() {
        val out = Exclusions.coalesce(listOf(range(0, 100), range(40, 60)))
        assertEquals(listOf(range(0, 100)), out)
    }

    @Test fun `filter drops readings inside ranges preserving order`() {
        val readings = listOf(50L, 150L, 160L, 250L, 260L)
        val out = Exclusions.filterReadings(
            readings.map { r(it) },
            listOf(range(100, 200), range(300, 999))
        )
        assertEquals(listOf(50L, 250L, 260L), out.map { it.timestampMs })
    }

    @Test fun `readings exactly on range boundaries are excluded (closed interval)`() {
        val readings = listOf(100L, 200L, 201L)
        val out = Exclusions.filterReadings(readings.map { r(it) }, listOf(range(100, 200)))
        assertEquals(listOf(201L), out.map { it.timestampMs })
    }

    @Test fun `filter with no ranges returns the same list`() {
        val readings = listOf(r(1), r(2), r(3))
        assertSame(readings, Exclusions.filterReadings(readings, emptyList()))
    }

    @Test fun `coalesced disjoint ranges both filter`() {
        val out = Exclusions.filterReadings(
            listOf(r(50), r(150), r(1000)),
            Exclusions.coalesce(listOf(range(100, 200), range(50, 60)))
        )
        assertEquals(listOf(1000L), out.map { it.timestampMs })
    }

    private fun r(timestampMs: Long) = com.ispindle.plotter.data.Reading(
        deviceId = 1,
        timestampMs = timestampMs,
        angle = 35.0,
        temperatureC = 20.0,
        batteryV = 3.9,
        rssi = -60,
        intervalS = 60,
        reportedGravity = null,
        computedGravity = 1.020,
        tempUnits = "C"
    )
}