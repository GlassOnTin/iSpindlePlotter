package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests on the first ~13 h of a fresh ferment
 * (`ferment_capture_start2.csv`, the 2nd brew on the device): a float
 * drop-in settling transient (an up-spike to ~1.107 then a single-sample
 * down-spike to ~1.0716, both at ~27 °C), settling to OG ≈ 1.0772; a ~9 h
 * lag while the wort cools 25 → 19 °C; and active descent only just
 * beginning (~4 mSG of an expected ~58).
 *
 * Two earlier defects this guards against, both at the most-uncertain
 * point of a ferment:
 *  1. `activeOnsetH` was tripped by the float drop-in down-spike
 *     (OG − 1.0716 = 5.6 mSG > LAG_DROP) at h ≈ 0, mislabelling the phase
 *     "Active" from pitch and erasing the visible lag, even though the
 *     Gompertz λ ≈ 13 h says the descent hadn't started.
 *  2. `predictedFgSigma` collapsed to < 0.1 mSG — the Laplace covariance
 *     reports a likelihood-only spread that the rigid Gompertz form pins
 *     by extrapolation, so the UI showed "FG 1.019 ± <0.0001" 12 h in,
 *     when FG was in fact entirely prior-driven.
 */
class EarlyFermentStartTest {

    private fun timeline() = loadCaptureWithTemp("ferment_capture_start2.csv").let { (h, s, t) ->
        Triple(h, s, Fermentation.buildTimeline(h, s, temps = t)!!)
    }

    @Test fun `OG rejects the float drop-in settling spike`() {
        val (_, sgs, tl) = timeline()
        // The raw series peaks at the ~1.107 settling up-spike; robust OG
        // must sit at the settled ~1.0772, not be dragged up by it.
        assertTrue("raw max ${sgs.max()} should be the >1.10 settling spike", sgs.max() > 1.10)
        assertTrue("OG ${tl.og} should be the settled ~1.0772, not the spike", tl.og in 1.075..1.079)
    }

    @Test fun `active onset is not tripped by the settling transient`() {
        val (_, _, tl) = timeline()
        // The down-spike is at h ≈ 0.002. A correct onset is far past it —
        // the real ~3 mSG sustained drop isn't reached until ~h 12 on this
        // capture. Anything < 5 h means the transient leaked through.
        val active = tl.activeOnsetH
        assertNotNull("active onset should eventually be detected", active)
        assertTrue(
            "activeOnsetH $active must be well past the settling transient at h≈0.002 (≥ 5 h)",
            active!! >= 5.0
        )
    }

    @Test fun `early window is classified as Lag, not Active`() {
        val (_, _, tl) = timeline()
        // At h = 2 the wort is still cooling and flat at ~OG: that's lag.
        assertEquals(
            "phase at h=2 should be Lag while SG holds flat near OG",
            Fermentation.Phase.Lag, tl.phaseAt(2.0)
        )
    }

    @Test fun `predicted FG uncertainty is floored to the prior while the descent is mostly unobserved`() {
        val (_, _, tl) = timeline()
        assertEquals(
            "FG comes from the Gompertz fit (prior-anchored this early)",
            Fermentation.PredictionSource.Gompertz, tl.source
        )
        val sigma = tl.predictedFgSigma
        assertNotNull("predictedFgSigma must be present", sigma)
        // ~6 % of the descent observed → floor ≈ 0.10 · (og−1) · 0.94 ≈ 7 mSG.
        // Pre-fix this read < 0.1 mSG. Require it to reflect the prior's
        // spread (≥ 4 mSG) rather than the collapsed likelihood-only value.
        assertTrue(
            "predictedFgSigma $sigma should be floored near the prior spread (≥ 0.004 SG), not the collapsed <0.0001",
            sigma!! >= 0.004
        )
        // The raw Laplace fgSigma is what got floored — confirm the floor
        // actually lifted it (i.e. the raw value really was too tight).
        val raw = tl.gompertz!!.fgSigma!!
        assertTrue("raw Laplace fgSigma $raw was the overconfident value (< 1 mSG)", raw < 0.001)
        assertTrue("reported sigma $sigma must be ≥ the raw value it floors", sigma >= raw)
    }

    private fun loadCaptureWithTemp(name: String): Triple<DoubleArray, DoubleArray, DoubleArray> {
        // Headerless: timestamp_ms,computed_gravity,temperature_c
        val lines = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readLines()
        val xs = mutableListOf<Double>(); val ys = mutableListOf<Double>(); val ts = mutableListOf<Double>()
        for (line in lines) {
            val p = line.split(','); if (p.size < 3) continue
            xs += p[0].toDouble(); ys += p[1].toDouble(); ts += p[2].toDouble()
        }
        val t0 = xs.first()
        val hours = DoubleArray(xs.size) { (xs[it] - t0) / 3_600_000.0 }
        return Triple(hours, ys.toDoubleArray(), ts.toDoubleArray())
    }
}
