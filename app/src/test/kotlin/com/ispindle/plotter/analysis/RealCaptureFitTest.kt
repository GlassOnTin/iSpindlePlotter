package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * LM-level regression tests on real iSpindle captures. These complement
 * `RealCaptureTest` (which exercises the higher-level `Fermentation`
 * analyser) by pinning down what the underlying logistic fit actually
 * does on the data we ship as fixtures.
 *
 * Two captures, both from the same brew at different timepoints:
 *  - `ferment_capture_2026-04-26.csv` — 13.7 h in, lag → active onset.
 *  - `ferment_capture_2026-04-27_31h.csv` — ~33 h in, mid active phase.
 */
class RealCaptureFitTest {

    @Test fun `33h capture fit converges with covariance and a Logistic-trustable FG`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val r = AttenuationFit.fit(hours, sgs)!!
        assertNotNull("covariance must be populated for v1", r.covariance)
        assertTrue("OG ${r.og} should be near observed max ~1.0527", r.og in 1.050..1.055)
        val atten = (r.og - r.fg) / (r.og - 1.0)
        assertTrue(
            "attenuation $atten should be near 75% (prior pulls in this band)",
            atten in 0.70..0.80
        )
        // The trust gates in Fermentation.pickFgEstimate require fg to
        // sit at least 0.002 SG below current SG; verify directly.
        val current = sgs.last()
        assertTrue(
            "fg ${r.fg} should sit > 0.002 below current $current so the analyser trusts the fit",
            current - r.fg > 0.002
        )
    }

    /**
     * Regression test for the original "abandons the earlier data too
     * readily" complaint. With the Bayesian prior in place, the lag
     * plateau (h 0–9, SG ≈ 1.0524) should be respected by the fit —
     * residuals there should sit within typical iSpindle noise, with
     * no systematic bias that the prior is being over-ridden by a
     * snug-to-tail FG.
     */
    @Test fun `33h capture fit respects the lag plateau (h0-9)`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val r = AttenuationFit.fit(hours, sgs)!!
        val lagResids = hours.indices
            .filter { hours[it] < 9.0 }
            .map { sgs[it] - r.predict(hours[it]) }
        assertTrue("expected ≥ 100 lag-region samples; got ${lagResids.size}", lagResids.size >= 100)
        val mean = lagResids.average()
        val maxAbs = lagResids.maxOf { abs(it) }
        assertTrue(
            "lag mean residual $mean should be within ±0.0015 SG (no systematic bias)",
            abs(mean) < 0.0015
        )
        assertTrue(
            "lag max |residual| $maxAbs should be < 0.005 SG (within iSpindle noise)",
            maxAbs < 0.005
        )
    }

    @Test fun `33h capture fit RMS residual is below 2 mSG`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val r = AttenuationFit.fit(hours, sgs)!!
        // 2 mSG = 0.002 — comfortably above the per-point iSpindle noise
        // (~0.5 mSG estimated on this device), under the 3 mSG that
        // would indicate a structurally bad fit.
        assertTrue(
            "RMS residual ${r.rmsResidual} should be < 0.002",
            r.rmsResidual < 0.002
        )
    }

    /**
     * Bayesian self-consistency: the deterministic MAP time-to-target
     * must fall inside the 95 % credible interval drawn from the
     * Laplace posterior. If it doesn't, either the LM hasn't converged
     * to a real local minimum or the covariance is degenerate.
     *
     * With ~1900 points and a strong prior, the posterior on this
     * fixture is tight — the CI is sub-hour wide — so we only assert
     * non-trivial spread (> 0.1 h ≈ 6 min), not a specific width.
     */
    @Test fun `33h capture MAP ETA falls inside its own 95 percent credible interval`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val r = AttenuationFit.fit(hours, sgs)!!
        val target = r.fg + 0.001
        val mapEta = r.timeToReach(target)!!
        val q = r.etaQuantiles(target, Random(0x5E1ED), nSamples = 512)!!
        assertTrue(
            "MAP ETA $mapEta should sit inside [${q[0]}, ${q[2]}]",
            mapEta in q[0]..q[2]
        )
        assertTrue(
            "credible interval should be non-trivial (high - low > 0.1 h), got ${q[2] - q[0]}",
            q[2] - q[0] > 0.1
        )
    }

    /**
     * Predictive band sanity on real data: the band should exist, be
     * small at the most recent data point (data constrains the curve),
     * and have non-trivial width somewhere across the data span.
     *
     * Note: for this dataset the band does NOT monotonically widen
     * forward. With FG well-determined by the prior and data, far-
     * future predictions collapse onto the asymptote at variance
     * roughly equal to var(FG). The band is widest somewhere in the
     * middle of the descent where (k, tMid) covariance dominates.
     */
    @Test fun `33h capture predictive band is tight on data and non-degenerate`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val r = AttenuationFit.fit(hours, sgs)!!
        val nowH = hours.last()
        val midH = (hours.first() + nowH) / 2.0
        val grid = doubleArrayOf(midH, nowH)
        val bands = r.predictiveBand(
            times = grid,
            rng = Random(0x5E1ED),
            nSamples = 512,
            quantiles = doubleArrayOf(0.025, 0.5, 0.975)
        )
        val midWidth = bands[2][0] - bands[0][0]
        val nowWidth = bands[2][1] - bands[0][1]
        assertTrue(
            "band at last observation should be tight (< 0.005 SG); got $nowWidth",
            nowWidth < 0.005
        )
        assertTrue(
            "band at mid-data (width=$midWidth) should have non-trivial spread",
            midWidth > 0.0001
        )
    }

    /**
     * Cross-fixture continuity: the same brew sampled at 13.7 h and at
     * 33 h should give compatible OG estimates. FG is allowed to differ
     * because the 33 h capture has more data informing it.
     */
    @Test fun `same brew at different timepoints gives compatible OG`() {
        val (h1, s1) = loadFixture("ferment_capture_2026-04-26.csv")
        val (h2, s2) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val r1 = AttenuationFit.fit(h1, s1)!!
        val r2 = AttenuationFit.fit(h2, s2)!!
        assertEquals(
            "OG should agree across fixtures (same brew)",
            r1.og, r2.og, 0.002
        )
    }

    /**
     * The 13.7 h capture is genuinely under-determined for the LM:
     * only ~4 h of active descent and no asymptote in sight, so the
     * (FG, k, tMid) directions of the loss surface are nearly flat.
     * The Hessian comes out near-singular and `covariance` may be
     * null — that's an honest "we don't know" rather than a bug, and
     * the chart code already falls through gracefully.
     *
     * What we do require: the fit returns, OG and attenuation land in
     * sane places via the prior, and (when the covariance does come
     * out) it's symmetric/positive.
     */
    @Test fun `13_7h capture fit completes and lands in a sane place`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-26.csv")
        val r = AttenuationFit.fit(hours, sgs)!!
        assertTrue("OG ${r.og} should be near observed max ~1.0527", r.og in 1.050..1.055)
        val atten = (r.og - r.fg) / (r.og - 1.0)
        assertTrue(
            "attenuation $atten should be near 75% (prior dominates with so few asymptote points)",
            atten in 0.65..0.85
        )
        r.covariance?.let { cov ->
            for (i in 0..3) assertTrue("diag $i positive when covariance present", cov[i][i] > 0.0)
        }
    }

    private fun loadFixture(name: String): Pair<DoubleArray, DoubleArray> {
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
        return DoubleArray(xs.size) { (xs[it] - t0) / 3_600_000.0 } to ys.toDoubleArray()
    }
}
