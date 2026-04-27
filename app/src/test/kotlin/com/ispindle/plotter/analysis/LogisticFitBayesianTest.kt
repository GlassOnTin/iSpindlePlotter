package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

/**
 * Tests for the v1 Bayesian additions to [LogisticFit]:
 *  - Soft Gaussian prior on attenuation (default `N(0.75, 0.10²)`).
 *  - Laplace approximation of the posterior, exposed as `Result.covariance`.
 *  - Sampling helpers (`sample`, `predictiveBand`, `etaQuantiles`).
 */
class LogisticFitBayesianTest {

    /**
     * On a clean S-curve where the asymptote is fully observed, the
     * data dominate the prior — fit should recover truth within the
     * historical tolerance even though prior centre (75 %) differs from
     * truth (80 %).
     */
    @Test fun `data overrules prior when asymptote is observed`() {
        val og = 1.060; val fg = 1.012; val k = 0.20; val tMid = 36.0
        val xs = DoubleArray(120) { it * 0.5 }
        val ys = DoubleArray(120) { fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) }
        val r = LogisticFit.fit(xs, ys)!!
        // Within 0.005 SG of truth — same tolerance as the historical
        // LogisticFitTest.recovers test.
        assertEquals(fg, r.fg, 0.005)
        assertEquals(og, r.og, 0.005)
        assertNotNull("covariance must be populated for v1", r.covariance)
    }

    /**
     * Mid-active data with no asymptote: many (FG, k, tMid) triples fit
     * equally well, the prior should pull the MAP toward 75 % attenuation
     * rather than letting FG snug-fit against the data tail.
     */
    @Test fun `prior pulls FG when data are uninformative about asymptote`() {
        val og = 1.060; val fg = 1.010; val k = 0.20; val tMid = 36.0
        // Sample only the first half of the curve — well past lag, but
        // long before the asymptote shows up. tMid = 36 h, sampled to
        // t = 30 h (so we never see SG flatten).
        val xs = DoubleArray(60) { it * 0.5 }
        val ys = DoubleArray(60) { fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) }
        val r = LogisticFit.fit(xs, ys)!!
        val attenuation = (r.og - r.fg) / (r.og - 1.0)
        assertTrue(
            "atten=$attenuation should be pulled toward prior mean 0.75 (within ±0.10)",
            attenuation in 0.65..0.85
        )
    }

    /**
     * Demonstrates the prior is doing real work: on a real mid-active
     * iSpindle capture (33 h in, ~half the descent visible), the prior
     * pulls the MAP toward 75 % attenuation. Disabling the prior lets
     * the LM drift to a different value — the exact direction depends
     * on the data's tail behaviour, but it's structurally outside the
     * 70-80 % band that the prior pins.
     */
    @Test fun `prior changes the converged MAP on under-determined real data`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val withPrior = LogisticFit.fit(hours, sgs)!!
        val withoutPrior = LogisticFit.fit(hours, sgs, attenuationPrior = null)!!

        val attenWith = (withPrior.og - withPrior.fg) / (withPrior.og - 1.0)
        val attenWithout = (withoutPrior.og - withoutPrior.fg) / (withoutPrior.og - 1.0)

        assertTrue(
            "with prior, atten=$attenWith should sit near 75% (within ±0.05)",
            attenWith in 0.70..0.80
        )
        assertTrue(
            "without prior, atten=$attenWithout should differ from prior-pulled value " +
                    "by > 0.05 (got Δ=${kotlin.math.abs(attenWithout - attenWith)})",
            kotlin.math.abs(attenWithout - attenWith) > 0.05
        )
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

    @Test fun `covariance is symmetric and positive on diagonal`() {
        val og = 1.060; val fg = 1.012; val k = 0.20; val tMid = 36.0
        val xs = DoubleArray(80) { it * 0.6 }
        val ys = DoubleArray(80) {
            fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) + 0.0005 * kotlin.math.sin(it * 0.5)
        }
        val r = LogisticFit.fit(xs, ys, measurementSigma = 0.0005)!!
        val cov = r.covariance!!
        for (i in 0..3) {
            assertTrue("diag $i positive", cov[i][i] > 0.0)
            for (j in 0..3) {
                assertEquals("symmetry [$i,$j]", cov[i][j], cov[j][i], 1e-12)
            }
        }
    }

    @Test fun `sample returns MAP when covariance is missing`() {
        val r = LogisticFit.Result(
            og = 1.060, fg = 1.012, k = 0.2, tMid = 36.0,
            rmsResidual = 0.0, pointCount = 100, fgSigma = null, covariance = null
        )
        val s = r.sample(Random(1))
        assertEquals(1.060, s[0], 1e-12)
        assertEquals(1.012, s[1], 1e-12)
        assertEquals(0.2, s[2], 1e-12)
        assertEquals(36.0, s[3], 1e-12)
    }

    /**
     * The marginal sample spread on FG should match `fgSigma` (≈ 1σ).
     * This is the consistency check between the analytic Laplace
     * marginal and the Monte Carlo draws.
     */
    @Test fun `sampled FG spread matches fgSigma to within Monte Carlo error`() {
        val og = 1.060; val fg = 1.012; val k = 0.30; val tMid = 24.0
        val xs = DoubleArray(120) { it * 0.5 }
        val ys = DoubleArray(120) { fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) }
        val r = LogisticFit.fit(xs, ys, measurementSigma = 0.0010)!!
        val n = 4000
        val rng = Random(7)
        val draws = DoubleArray(n) { r.sample(rng)[1] }
        val mean = draws.average()
        val variance = draws.map { (it - mean) * (it - mean) }.average()
        val sampleSigma = kotlin.math.sqrt(variance)
        // Tolerate 25 % MC error at n=4000 — comfortably wide for a
        // smooth Gaussian, robust against jitter.
        assertEquals("sampled σ_FG should match fgSigma", r.fgSigma!!, sampleSigma, 0.25 * r.fgSigma!!)
    }

    @Test fun `predictiveBand widens past last observation`() {
        val og = 1.060; val fg = 1.012; val k = 0.30; val tMid = 24.0
        val xs = DoubleArray(80) { it * 0.5 }   // up to t = 39.5 h
        val ys = DoubleArray(80) { fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) }
        val r = LogisticFit.fit(xs, ys, measurementSigma = 0.0010)!!
        val grid = doubleArrayOf(20.0, 39.0, 60.0, 100.0)   // last data ≈ 39.5
        val bands = r.predictiveBand(grid, Random(3), nSamples = 256,
            quantiles = doubleArrayOf(0.025, 0.5, 0.975))
        val lows = bands[0]; val highs = bands[2]
        val widths = DoubleArray(grid.size) { highs[it] - lows[it] }
        assertTrue("band non-zero on data", widths[0] > 0.0)
        // Far-future widths should grow past in-sample widths, since
        // joint covariance over (FG, k, tMid) projects into bigger
        // spreads at extrapolated times.
        assertTrue(
            "band widens past data: in=${widths[1]}, out=${widths[3]}",
            widths[3] > widths[1]
        )
    }

    @Test fun `etaQuantiles returns ordered triple bracketing the MAP ETA`() {
        val og = 1.060; val fg = 1.012; val k = 0.20; val tMid = 36.0
        val xs = DoubleArray(120) { it * 0.5 }
        val ys = DoubleArray(120) { fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) }
        val r = LogisticFit.fit(xs, ys, measurementSigma = 0.0010)!!
        val target = 1.020
        val q = r.etaQuantiles(target, Random(11), nSamples = 512)!!
        assertEquals(3, q.size)
        assertTrue("ascending: ${q.toList()}", q[0] <= q[1] && q[1] <= q[2])
        val mapEta = r.timeToReach(target)!!
        // Median of the posterior should bracket the MAP ETA inside
        // the 95 % CI (so MAP ∈ [low, high]).
        assertTrue(
            "MAP ETA $mapEta should fall inside the credible interval [${q[0]}, ${q[2]}]",
            mapEta in q[0]..q[2]
        )
    }

    @Test fun `etaQuantiles null when no covariance`() {
        val r = LogisticFit.Result(
            og = 1.060, fg = 1.012, k = 0.2, tMid = 36.0,
            rmsResidual = 0.0, pointCount = 100, fgSigma = null, covariance = null
        )
        assertNull(r.etaQuantiles(1.020, Random(0)))
    }
}
