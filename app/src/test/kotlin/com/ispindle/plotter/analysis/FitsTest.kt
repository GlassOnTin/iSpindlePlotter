package com.ispindle.plotter.analysis

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitsTest {

    @Test fun `linear OLS recovers known slope and intercept`() {
        val xs = DoubleArray(10) { it.toDouble() }
        val ys = DoubleArray(10) { 4.05 - 0.01 * it }
        val fit = Fits.fitLinear(xs, ys)!!
        assertEquals(4.05, fit.intercept, 1e-9)
        assertEquals(-0.01, fit.slope, 1e-9)
        assertEquals(0.0, fit.rmsResidual, 1e-9)
    }

    @Test fun `linear OLS handles light noise`() {
        val xs = DoubleArray(50) { it.toDouble() }
        val ys = DoubleArray(50) { 4.0 - 0.005 * it + 0.001 * (it.rem(7) - 3) }
        val fit = Fits.fitLinear(xs, ys)!!
        assertEquals(4.0, fit.intercept, 5e-3)
        assertEquals(-0.005, fit.slope, 5e-4)
        assertTrue("residual should be small", fit.rmsResidual < 5e-3)
    }

    @Test fun `linear timeToReach inverts the prediction`() {
        val fit = Fits.Linear(intercept = 4.0, slope = -0.005, rmsResidual = 0.0, pointCount = 10)
        assertEquals(120.0, fit.timeToReach(3.4)!!, 1e-9)
    }

    @Test fun `linear is null for too few points or constant x`() {
        assertNull(Fits.fitLinear(doubleArrayOf(1.0), doubleArrayOf(2.0)))
        assertNull(Fits.fitLinear(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 2.0)))
    }

    @Test fun `exponential recovers asymptote rate and offset`() {
        // Synthesised: SG(t) = 1.012 + 0.058 * exp(-t / 24)
        // → asymptote 1.012, initialOffset 0.058, rateConstant 1/24 ≈ 0.04167
        val k = 1.0 / 24.0
        val xs = DoubleArray(48) { it.toDouble() }
        val ys = DoubleArray(48) { 1.012 + 0.058 * exp(-k * it) }
        val fit = Fits.fitExponentialDecay(xs, ys)!!
        assertEquals(1.012, fit.asymptote, 1e-3)
        assertEquals(0.058, fit.initialOffset, 5e-3)
        assertEquals(k, fit.rateConstant, 1e-3)
        assertTrue(fit.rmsResidual < 1e-3)
    }

    @Test fun `exponential timeToReach is monotonic`() {
        val k = 0.05
        val xs = DoubleArray(60) { it.toDouble() }
        val ys = DoubleArray(60) { 1.010 + 0.060 * exp(-k * it) }
        val fit = Fits.fitExponentialDecay(xs, ys)!!
        val tToHalf = fit.timeToReach(1.040)!!
        val tToWithinOne = fit.timeToReach(1.011)!!
        assertTrue("approaching asymptote takes longer", tToWithinOne > tToHalf)
    }

    @Test fun `exponential refuses below 4 points`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0)
        val ys = doubleArrayOf(1.06, 1.04, 1.03)
        assertNull(Fits.fitExponentialDecay(xs, ys))
    }

    @Test fun `exponential refuses flat data`() {
        val xs = DoubleArray(20) { it.toDouble() }
        val ys = DoubleArray(20) { 1.000 }
        assertNull(Fits.fitExponentialDecay(xs, ys))
    }

    @Test fun `exponential survives mild noise`() {
        val k = 1.0 / 36.0
        val xs = DoubleArray(72) { it.toDouble() }
        val ys = DoubleArray(72) {
            // ±0.0005 SG cyclic noise
            1.014 + 0.066 * exp(-k * it) + 0.0005 * kotlin.math.sin(it * 0.7)
        }
        val fit = Fits.fitExponentialDecay(xs, ys)
        assertNotNull(fit)
        assertEquals(1.014, fit!!.asymptote, 2e-3)
        assertEquals(k, fit.rateConstant, 2e-3)
    }

    @Test fun `exponential timeToReach returns null below asymptote`() {
        val k = 0.05
        val xs = DoubleArray(20) { it.toDouble() }
        val ys = DoubleArray(20) { 1.010 + 0.060 * exp(-k * it) }
        val fit = Fits.fitExponentialDecay(xs, ys)!!
        // 1.005 is below the asymptote at 1.010 — unreachable in finite time
        assertNull(fit.timeToReach(1.005))
    }
}
