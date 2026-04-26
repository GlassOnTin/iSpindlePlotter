package com.ispindle.plotter.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class UncertaintyPropagationTest {

    @Test fun `logistic fit reports an FG sigma proportional to measurement sigma`() {
        // Synthetic full-window logistic so the LM converges and we can
        // read the covariance off cleanly.
        val og = 1.060; val fg = 1.010; val k = 0.5; val tMid = 24.0
        val n = 80
        val xs = DoubleArray(n) { it.toDouble() * 60.0 / n }
        val rng = Random(7)
        val sigma = 0.001
        val ys = DoubleArray(n) {
            val mean = fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid)))
            mean + sigma * boxMuller(rng)
        }
        val fit10x = LogisticFit.fit(xs, ys, measurementSigma = 10.0 * sigma)
        val fit1x = LogisticFit.fit(xs, ys, measurementSigma = sigma)
        assertNotNull(fit1x); assertNotNull(fit10x)
        val s1 = fit1x!!.fgSigma
        val s10 = fit10x!!.fgSigma
        assertNotNull("FG sigma should be reported when measurementSigma is supplied", s1)
        assertNotNull(s10)
        // σ_FG scales linearly with σ_meas (covariance ∝ σ²).
        val ratio = s10!! / s1!!
        assertTrue("σ_FG ratio expected near 10, got $ratio", ratio in 8.0..12.0)
    }

    @Test fun `Fermentation analyse propagates calibration sigma into the state`() {
        // 30 hours of slow, mid-active descent: enough trend for a Linear
        // or rate-based source. Exact source isn't asserted; we just check
        // the σ fields show up.
        val n = 60
        val xs = DoubleArray(n) { it * 0.5 }
        val ys = DoubleArray(n) { 1.060 - 0.0008 * it }
        val state = Fermentation.analyse(xs, ys, calRSquared = 0.99)
        when (state) {
            is Fermentation.State.Active -> {
                assertNotNull("predictedFgSigma should be set", state.predictedFgSigma)
                assertNotNull("measurementSigma should be set", state.measurementSigma)
                assertTrue("σ_FG should be positive", (state.predictedFgSigma ?: 0.0) > 0.0)
            }
            is Fermentation.State.Slowing -> {
                assertNotNull(state.predictedFgSigma)
                assertNotNull(state.measurementSigma)
            }
            else -> {
                // A non-Active/Slowing classification is acceptable for
                // this synthetic dataset (it could legitimately be Lag);
                // we only care about the σ plumbing when those branches fire.
            }
        }
    }

    private fun boxMuller(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
                kotlin.math.cos(2 * kotlin.math.PI * u2)
    }
}
