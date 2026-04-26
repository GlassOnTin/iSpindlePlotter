package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class NoiseModelTest {

    @Test fun `data noise recovers known sigma from a synthetic linear trend`() {
        val rng = Random(42)
        val sigmaTrue = 0.001
        val n = 200
        val xs = DoubleArray(n) { it * 0.05 }            // 0..10 hours
        val ys = DoubleArray(n) { 1.050 - 0.0005 * xs[it] + rng.gaussian(sigmaTrue) }
        val sigmaEst = NoiseModel.estimateDataNoise(xs, ys, windowH = 2.0)
        assertNotNull(sigmaEst)
        // Pooled-residual estimator should sit within 25% of the true σ on
        // a 200-point sample with this much data per window.
        assertEquals("estimated σ should be near $sigmaTrue", sigmaTrue, sigmaEst!!, sigmaTrue * 0.25)
    }

    @Test fun `data noise returns null for too-short series`() {
        val xs = doubleArrayOf(0.0, 0.5, 1.0, 1.5)
        val ys = doubleArrayOf(1.05, 1.05, 1.05, 1.05)
        assertNull(NoiseModel.estimateDataNoise(xs, ys))
    }

    @Test fun `cal sigma maps R-squared to SG units sensibly`() {
        // R² = 0.9959, 0.05 SG range → σ_cal ≈ 0.05 * sqrt(0.0041/12) ≈ 9.2e-4
        val sigma = NoiseModel.fromCalibrationRSquared(0.9959, sgRange = 0.05)
        assertEquals(0.00092, sigma, 5e-5)
        // Perfect calibration → zero contribution.
        assertEquals(0.0, NoiseModel.fromCalibrationRSquared(1.0), 1e-12)
    }

    @Test fun `combine adds in quadrature with a hard floor`() {
        val total = NoiseModel.combine(0.0003, 0.0009)
        assertEquals(sqrt(0.0003 * 0.0003 + 0.0009 * 0.0009), total, 1e-9)
        // Floor at 1e-5 protects callers who would otherwise divide by 0.
        assertTrue(NoiseModel.combine(0.0, 0.0) >= 1e-5)
    }

    private fun Random.gaussian(sigma: Double): Double {
        // Box-Muller — sufficient for a test, no need to import a math lib.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * kotlin.math.PI * u2) * sigma
    }
}
