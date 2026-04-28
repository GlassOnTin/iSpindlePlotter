package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the analyser over three snapshots of the same real fermentation:
 *  - `ferment_capture_2026-04-26.csv` — 13.7 h in, lag → active onset.
 *  - `ferment_capture_2026-04-27_31h.csv` — 31.0 h in, mid active phase.
 *  - `ferment_capture_2026-04-27_35h.csv` — 35.4 h in, post-diauxic-shift.
 *
 * All must produce sensible classifications without claiming "near
 * asymptote — fermentation looks complete", which is what the old single-
 * exponential fit was reporting. The 35 h capture additionally contains
 * a clear mid-ferment plateau (h 27–30 at SG ≈ 1.0292) which the
 * detector should call out without disturbing the trusted-Logistic verdict.
 */
class RealCaptureTest {

    @Test fun `early-active capture classifies as Active or Lag, never Complete`() {
        val (hours, sgs) = loadCapture("ferment_capture_2026-04-26.csv")
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

    /**
     * Mid-active extension of the same ferment, captured 31 h in
     * (1753 points, OG ≈ 1.0527, current ≈ 1.0271). The lag plateau
     * (h0–h9) and ~21 h of active descent are both visible; the
     * asymptote is not.
     *
     * Pre-fix, the LM converged to FG ≈ 1.0255 (51 % attenuation —
     * snug-fitting the recent tail and ignoring the lag plateau). The
     * analyser's `notHuggingData` gate rejected that fit and fell back to
     * the 75 %-prior with a `Linear`/rate-based source. Post-fix, the FG
     * ceiling at 70 % attenuation forces the LM to a curve that respects
     * both the lag and the descent, and the resulting fit is trusted.
     */
    @Test fun `mid-active 31h capture trusts logistic source and respects lag plateau`() {
        val (hours, sgs) = loadCapture("ferment_capture_2026-04-27_31h.csv")
        assertTrue("loaded ${hours.size} points", hours.size > 1500)
        val state = Fermentation.analyse(hours, sgs)
        assertTrue("expected Active; got $state", state is Fermentation.State.Active)
        state as Fermentation.State.Active
        assertEquals(
            "source should be Gompertz (regression — pre-fix this fell to Linear)",
            Fermentation.PredictionSource.Gompertz, state.source
        )
        assertTrue(
            "OG ${state.og} should be near observed max ~1.0527",
            state.og in 1.050..1.055
        )
        assertTrue(
            "predicted FG ${state.predictedFg} should sit in 70-90 % atten band",
            state.predictedFg in 1.011..1.020
        )
        val eta = state.etaToFinishHours
        assertTrue("ETA $eta should be set and in (5, 40) h", eta != null && eta in 5.0..40.0)
    }

    /**
     * 35 h snapshot of the same brew. Between h 26 and h 30 the SG sits
     * at ≈ 1.0292 with a near-zero slope while the active descent pauses
     * — almost certainly a diauxic shift between sugar populations.
     * Active descent then resumes from h 30 onward.
     *
     * Three things must hold simultaneously:
     *  1. The state still classifies as Active (we are NOT stuck — descent
     *     resumed after the pause).
     *  2. The Logistic source is trusted (the prior + bounds keep FG sane
     *     even though the fit smooths through the plateau).
     *  3. A Mid plateau is detected and reported on the state — this is
     *     the regression-prevention assertion for the new detector.
     */
    @Test fun `post-diauxic-shift 35h capture is Active and reports a Mid plateau`() {
        val (hours, sgs) = loadCapture("ferment_capture_2026-04-27_35h.csv")
        assertTrue("loaded ${hours.size} points", hours.size > 1800)
        val state = Fermentation.analyse(hours, sgs)
        assertTrue("expected Active; got $state", state is Fermentation.State.Active)
        state as Fermentation.State.Active

        assertEquals(
            "source should still be Gompertz post-restart",
            Fermentation.PredictionSource.Gompertz, state.source
        )
        val mid = state.plateaus.firstOrNull { it.kind == Plateau.Kind.Mid }
        assertTrue(
            "expected a Mid plateau in the detected list; got ${state.plateaus}",
            mid != null
        )
        mid!!
        assertTrue(
            "mid plateau SG ${mid.sg} should sit near 1.029",
            mid.sg in 1.027..1.031
        )
        assertTrue(
            "mid plateau duration ${mid.durationH} should be at least 2 h",
            mid.durationH >= 2.0
        )
        assertTrue(
            "mid plateau startH ${mid.startH} should sit between 24 h and 30 h",
            mid.startH in 24.0..30.0
        )
    }

    private fun loadCapture(name: String): Pair<DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
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
