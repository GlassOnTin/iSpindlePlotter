package com.ispindle.plotter.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StickyRangeTest {

    @Test fun `first call snaps to a nice range`() {
        val (lo, hi) = stickyRange(cached = null, rawMin = 1.020, rawMax = 1.060)
        // niceRange snaps to step=0.010 (span 0.040 / 5 ticks).
        assertEquals(1.020, lo, 1e-9)
        assertEquals(1.060, hi, 1e-9)
    }

    @Test fun `subsequent points within range leave the cache untouched`() {
        val cached = 1.020 to 1.060
        // Data span shrinks slightly as nothing new arrives at extremes.
        val result = stickyRange(cached, rawMin = 1.030, rawMax = 1.055)
        assertEquals(cached, result)
    }

    @Test fun `point that drops below cached min widens the axis downward`() {
        val cached = 1.020 to 1.060
        val (lo, hi) = stickyRange(cached, rawMin = 1.010, rawMax = 1.060)
        assert(lo <= 1.010) { "lo=$lo should cover new minimum" }
        assertEquals(1.060, hi, 1e-9)
    }

    @Test fun `point that exceeds cached max widens the axis upward`() {
        val cached = 1.020 to 1.060
        val (lo, hi) = stickyRange(cached, rawMin = 1.020, rawMax = 1.075)
        assertEquals(1.020, lo, 1e-9)
        assert(hi >= 1.075) { "hi=$hi should cover new maximum" }
    }

    @Test fun `cache survives small changes that would otherwise re-snap`() {
        // niceRange-only would hop around with a single drifting point.
        val cached = 1.020 to 1.060
        for (raw in listOf(
            1.030 to 1.060,
            1.025 to 1.058,
            1.035 to 1.055
        )) {
            val result = stickyRange(cached, raw.first, raw.second)
            assertEquals("with data ${raw}, expected cache stable", cached, result)
        }
    }

    @Test fun `cache re-snaps when data span has shrunk to a small fraction`() {
        // A wide cached range (0.040) and the user trimmed back to a sliver
        // of recent data near the asymptote. Re-snap so the chart isn't
        // pinned into one corner.
        val cached = 1.020 to 1.060
        val (lo, hi) = stickyRange(cached, rawMin = 1.012, rawMax = 1.013)
        // The cache spans 40x the data span; we should re-snap, which means
        // not equal to the original.
        assertNotEquals(cached, lo to hi)
        // And the new range covers the data.
        assert(lo <= 1.012 && hi >= 1.013)
    }

    @Test fun `cache holds when shrink is mild`() {
        // Cache span 0.040, data span 0.020 — only 2x ratio, below threshold.
        val cached = 1.020 to 1.060
        val result = stickyRange(cached, rawMin = 1.030, rawMax = 1.050)
        assertEquals(cached, result)
    }

    @Test fun `single-point data does not crash the shrink test`() {
        val cached = 1.020 to 1.060
        val result = stickyRange(cached, rawMin = 1.040, rawMax = 1.040)
        // dataSpan is 0; the shrink branch is gated on dataSpan > 0 so we
        // fall through to "keep cached" rather than divide-by-zero.
        assertEquals(cached, result)
    }
}
