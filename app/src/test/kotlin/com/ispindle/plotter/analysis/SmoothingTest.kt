package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SmoothingTest {

    @Test fun `rolling median rejects an isolated spike`() {
        // Steady 4.15 V trace with one 4.10 V reading at index 5.
        val pts = (0..10).map { it.toDouble() to 4.15 }.toMutableList()
        pts[5] = pts[5].first to 4.10
        val smoothed = Smoothing.rollingMedian(pts, window = 5)
        for ((t, v) in smoothed) {
            assertEquals("isolated spike at t=$t should be rejected", 4.15, v, 1e-9)
        }
    }

    @Test fun `rolling median preserves a real step change`() {
        // First 10 readings at 4.15, next 10 at 4.10. A real step.
        val pts = (0..19).map {
            it.toDouble() to (if (it < 10) 4.15 else 4.10)
        }
        val smoothed = Smoothing.rollingMedian(pts, window = 5)
        // Far from the boundary the median follows the level cleanly.
        assertEquals(4.15, smoothed[2].second, 1e-9)
        assertEquals(4.10, smoothed[17].second, 1e-9)
    }

    @Test fun `rolling mean smooths quantised stair-step input`() {
        // Underlying linear ramp 4.15 → 4.10, but the input is the ADC-
        // quantised version that holds at each 0.01 V step for ~6 samples.
        val pts = (0..29).map {
            val q = 4.15 - (it / 6) * 0.01
            it.toDouble() to q
        }
        val smoothed = Smoothing.rollingMean(pts, window = 7)
        // Successive smoothed values should never jump by more than the
        // raw quantisation step (0.01 V) — by averaging across the step
        // boundary the mean produces sub-quantum transitions.
        for (i in 1 until smoothed.size) {
            val jump = abs(smoothed[i].second - smoothed[i - 1].second)
            assertTrue(
                "step of $jump V at i=$i should be < 0.01 V after mean smoothing",
                jump < 0.01
            )
        }
    }

    @Test fun `robustSmooth output has same length as input`() {
        val pts = (0..49).map { it.toDouble() to (4.15 - it * 0.001) }
        val smoothed = Smoothing.robustSmooth(pts, medianWindow = 9, meanWindow = 7)
        assertEquals(pts.size, smoothed.size)
    }

    @Test fun `window of 1 returns input unchanged`() {
        val pts = listOf(0.0 to 1.0, 1.0 to 2.0, 2.0 to 3.0)
        assertEquals(pts, Smoothing.rollingMedian(pts, window = 1))
        assertEquals(pts, Smoothing.rollingMean(pts, window = 1))
    }

    @Test fun `requires positive window`() {
        val pts = listOf(0.0 to 1.0, 1.0 to 2.0)
        try {
            Smoothing.rollingMedian(pts, window = 0)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
