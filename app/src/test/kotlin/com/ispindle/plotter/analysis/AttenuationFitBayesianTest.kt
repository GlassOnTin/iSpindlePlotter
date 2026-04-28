package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.exp
import kotlin.random.Random

/**
 * Tests for the Bayesian additions to [AttenuationFit]:
 *  - Soft Gaussian prior on attenuation (default `N(0.75, 0.10²)`).
 *  - Laplace approximation of the posterior, exposed as `Result.covariance`.
 *  - Sampling helpers (`sample`, `predictiveBand`, `etaQuantiles`).
 */
class AttenuationFitBayesianTest {

    private fun gompertz(og: Double, fg: Double, muMax: Double, lambda: Double, t: Double): Double {
        val a = og - fg
        val arg = muMax * E / a * (lambda - t) + 1.0
        return when {
            arg > 30.0 -> og
            arg < -700.0 -> fg
            else -> og - a * exp(-exp(arg))
        }
    }

    /**
     * On a clean Gompertz curve where the asymptote is fully observed, the
     * data dominate the prior — fit should recover truth even though the
     * prior centre (75 %) differs from truth (80 %).
     */
    @Test fun `data overrules prior when asymptote is observed`() {
        val og = 1.060; val fg = 1.012; val muMax = 0.0020; val lambda = 12.0
        val xs = DoubleArray(120) { it * 0.5 }
        val ys = DoubleArray(120) { gompertz(og, fg, muMax, lambda, xs[it]) }
        val r = AttenuationFit.fit(xs, ys)!!
        assertEquals(fg, r.fg, 0.005)
        assertEquals(og, r.og, 0.005)
        assertNotNull("covariance must be populated", r.covariance)
    }

    /**
     * Mid-active data with no asymptote: many (FG, muMax, lambda) triples
     * fit equally well, the prior should pull the MAP toward 75 %
     * attenuation rather than letting FG snug-fit against the data tail.
     */
    @Test fun `prior pulls FG when data are uninformative about asymptote`() {
        val og = 1.060; val fg = 1.010; val muMax = 0.0020; val lambda = 12.0
        // Sample only the first 30 h, which covers the lag and the
        // descent's leading edge but stops well short of the asymptote.
        val xs = DoubleArray(60) { it * 0.5 }
        val ys = DoubleArray(60) { gompertz(og, fg, muMax, lambda, xs[it]) }
        val r = AttenuationFit.fit(xs, ys)!!
        val attenuation = (r.og - r.fg) / (r.og - 1.0)
        assertTrue(
            "atten=$attenuation should be pulled toward prior mean 0.75 (within ±0.10)",
            attenuation in 0.65..0.85
        )
    }

    /**
     * Real mid-active iSpindle capture (33 h in, ~half the descent visible):
     * with the prior, attenuation should sit near 75 %; without it, the LM
     * lands somewhere different. The exact direction depends on the data's
     * tail behaviour, but the gap should exceed 0.05.
     */
    @Test fun `prior changes the converged MAP on under-determined real data`() {
        val (hours, sgs) = loadFixture("ferment_capture_2026-04-27_31h.csv")
        val withPrior = AttenuationFit.fit(hours, sgs)!!
        val withoutPrior = AttenuationFit.fit(hours, sgs, attenuationPrior = null)!!

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
        val og = 1.060; val fg = 1.012; val muMax = 0.0020; val lambda = 12.0
        val xs = DoubleArray(80) { it * 0.6 }
        val ys = DoubleArray(80) {
            gompertz(og, fg, muMax, lambda, xs[it]) + 0.0005 * kotlin.math.sin(it * 0.5)
        }
        val r = AttenuationFit.fit(xs, ys, measurementSigma = 0.0005)!!
        val cov = r.covariance!!
        for (i in 0..3) {
            assertTrue("diag $i positive", cov[i][i] > 0.0)
            for (j in 0..3) {
                assertEquals("symmetry [$i,$j]", cov[i][j], cov[j][i], 1e-12)
            }
        }
    }

    @Test fun `sample returns MAP when covariance is missing`() {
        val r = AttenuationFit.Result(
            og = 1.060, fg = 1.012, muMax = 0.002, lambda = 12.0,
            rmsResidual = 0.0, pointCount = 100, fgSigma = null, covariance = null
        )
        val s = r.sample(Random(1))
        assertEquals(1.060, s[0], 1e-12)
        assertEquals(1.012, s[1], 1e-12)
        assertEquals(0.002, s[2], 1e-12)
        assertEquals(12.0, s[3], 1e-12)
    }

    /**
     * The marginal sample spread on FG should match `fgSigma` (≈ 1σ).
     * Consistency check between the analytic Laplace marginal and the
     * Monte Carlo draws.
     */
    @Test fun `sampled FG spread matches fgSigma to within Monte Carlo error`() {
        val og = 1.060; val fg = 1.012; val muMax = 0.0030; val lambda = 8.0
        val xs = DoubleArray(120) { it * 0.5 }
        val ys = DoubleArray(120) { gompertz(og, fg, muMax, lambda, xs[it]) }
        val r = AttenuationFit.fit(xs, ys, measurementSigma = 0.0010)!!
        val n = 4000
        val rng = Random(7)
        val draws = DoubleArray(n) { r.sample(rng)[1] }
        val mean = draws.average()
        val variance = draws.map { (it - mean) * (it - mean) }.average()
        val sampleSigma = kotlin.math.sqrt(variance)
        assertEquals(
            "sampled σ_FG should match fgSigma",
            r.fgSigma!!, sampleSigma, 0.30 * r.fgSigma!!
        )
    }

    @Test fun `predictiveBand widens past last observation`() {
        val og = 1.060; val fg = 1.012; val muMax = 0.0030; val lambda = 8.0
        val xs = DoubleArray(80) { it * 0.5 }   // up to t = 39.5 h
        val ys = DoubleArray(80) { gompertz(og, fg, muMax, lambda, xs[it]) }
        val r = AttenuationFit.fit(xs, ys, measurementSigma = 0.0010)!!
        val grid = doubleArrayOf(20.0, 39.0, 60.0, 100.0)
        val bands = r.predictiveBand(grid, Random(3), nSamples = 256,
            quantiles = doubleArrayOf(0.025, 0.5, 0.975))
        val lows = bands[0]; val highs = bands[2]
        val widths = DoubleArray(grid.size) { highs[it] - lows[it] }
        assertTrue("band non-zero on data", widths[0] > 0.0)
        assertTrue(
            "band widens past data: in=${widths[1]}, out=${widths[3]}",
            widths[3] > widths[1]
        )
    }

    @Test fun `etaQuantiles returns ordered triple bracketing the MAP ETA`() {
        val og = 1.060; val fg = 1.012; val muMax = 0.0020; val lambda = 12.0
        val xs = DoubleArray(120) { it * 0.5 }
        val ys = DoubleArray(120) { gompertz(og, fg, muMax, lambda, xs[it]) }
        val r = AttenuationFit.fit(xs, ys, measurementSigma = 0.0010)!!
        val target = 1.020
        val q = r.etaQuantiles(target, Random(11), nSamples = 512)!!
        assertEquals(3, q.size)
        assertTrue("ascending: ${q.toList()}", q[0] <= q[1] && q[1] <= q[2])
        val mapEta = r.timeToReach(target)!!
        assertTrue(
            "MAP ETA $mapEta should fall inside the credible interval [${q[0]}, ${q[2]}]",
            mapEta in q[0]..q[2]
        )
    }

    @Test fun `etaQuantiles null when no covariance`() {
        val r = AttenuationFit.Result(
            og = 1.060, fg = 1.012, muMax = 0.002, lambda = 12.0,
            rmsResidual = 0.0, pointCount = 100, fgSigma = null, covariance = null
        )
        assertNull(r.etaQuantiles(1.020, Random(0)))
    }
}
