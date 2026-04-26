package com.ispindle.plotter.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the analyser over the exported capture from a real fermentation
 * (770 points, 13.7 hours, lag → active onset). The dataset shows SG flat
 * at ~1.0524 for ~9 h then dropping to ~1.0475 by hour 13. The analyser
 * should call this Active (not "near asymptote — fermentation looks
 * complete", which is what the old single-exponential fit was reporting).
 */
class RealCaptureTest {

    @Test fun `early-active capture classifies as Active or Lag, never Complete`() {
        val (hours, sgs) = loadCapture()
        assertTrue("loaded ${hours.size} points", hours.size > 100)
        val state = Fermentation.analyse(hours, sgs)
        // Either Active (after the knee at ~h9) or Lag (if we treat the
        // small drop as still within tolerance) is acceptable. We must NOT
        // claim Complete or Stuck on data this fresh and still moving.
        assertTrue(
            "expected Active/Slowing/Lag for fresh ferment; got $state",
            state is Fermentation.State.Active ||
                    state is Fermentation.State.Slowing ||
                    state is Fermentation.State.Lag
        )
        when (state) {
            is Fermentation.State.Active -> {
                assertTrue(
                    "OG ${state.og} should be near observed max ~1.0527",
                    state.og in 1.050..1.055
                )
                assertTrue(
                    "predicted FG ${state.predictedFg} should be < OG and > 1.005",
                    state.predictedFg in 1.005..1.020
                )
            }
            is Fermentation.State.Lag -> {
                // Acceptable too — the active phase only covers the last
                // 4 hours of this capture, and the threshold for Lag may
                // still be triggered.
            }
            is Fermentation.State.Slowing -> {
                // Also reasonable on a borderline rate.
            }
            else -> error("unreachable: $state")
        }
    }

    private fun loadCapture(): Pair<DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!
            .getResourceAsStream("ferment_capture_2026-04-26.csv")!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            val parts = line.split(',')
            xs += parts[tIdx].toDouble()
            ys += parts[sgIdx].toDouble()
        }
        val t0 = xs.first()
        val hours = DoubleArray(xs.size) { (xs[it] - t0) / 3_600_000.0 }
        val sgs = DoubleArray(ys.size) { ys[it] }
        return hours to sgs
    }
}
