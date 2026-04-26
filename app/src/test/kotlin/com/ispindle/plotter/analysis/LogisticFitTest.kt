package com.ispindle.plotter.analysis

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticFitTest {

    private val tol = 0.005

    @Test fun `recovers known parameters from a clean S-curve`() {
        val og = 1.060; val fg = 1.012; val k = 0.20; val tMid = 36.0
        val xs = DoubleArray(120) { it * 0.5 + 0.0 }
        val ys = DoubleArray(120) { fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) }
        val r = LogisticFit.fit(xs, ys)!!
        assertEquals(og, r.og, tol)
        assertEquals(fg, r.fg, tol)
        assertEquals(k, r.k, 0.05)
        assertEquals(tMid, r.tMid, 1.5)
        assertTrue(r.rmsResidual < 1e-3)
    }

    @Test fun `survives mild noise`() {
        val og = 1.052; val fg = 1.013; val k = 0.15; val tMid = 48.0
        val xs = DoubleArray(200) { it * 0.5 }
        val ys = DoubleArray(200) {
            val ideal = fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid)))
            ideal + 0.0005 * kotlin.math.sin(it * 0.7)  // ±0.5 SG-points jitter
        }
        val r = LogisticFit.fit(xs, ys)!!
        assertEquals(og, r.og, 0.002)
        assertEquals(fg, r.fg, 0.003)
        assertTrue(r.rmsResidual < 1e-3)
    }

    @Test fun `refuses below minimum sample count`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0)
        val ys = doubleArrayOf(1.060, 1.058, 1.055)
        assertNull(LogisticFit.fit(xs, ys))
    }

    @Test fun `refuses flat data`() {
        val xs = DoubleArray(40) { it.toDouble() }
        val ys = DoubleArray(40) { 1.020 }
        assertNull(LogisticFit.fit(xs, ys))
    }

    @Test fun `timeToReach inverts predict`() {
        val r = LogisticFit.Result(
            og = 1.060, fg = 1.012, k = 0.2, tMid = 36.0,
            rmsResidual = 0.0, pointCount = 100
        )
        val target = 1.030
        val t = r.timeToReach(target)!!
        assertEquals(target, r.predict(t), 1e-9)
    }

    @Test fun `unreachable target returns null`() {
        val r = LogisticFit.Result(
            og = 1.060, fg = 1.012, k = 0.2, tMid = 36.0,
            rmsResidual = 0.0, pointCount = 100
        )
        assertNull(r.timeToReach(1.005))   // below FG
        assertNull(r.timeToReach(1.080))   // above OG
    }

    @Test fun `rateAt is most negative at inflection`() {
        val r = LogisticFit.Result(1.060, 1.010, 0.3, 50.0, 0.0, 100)
        val mid = r.rateAt(50.0)
        val early = r.rateAt(20.0)
        val late = r.rateAt(80.0)
        assertTrue("midpoint rate $mid should be more negative than early $early") {
            mid < early
        }
        assertTrue("midpoint rate $mid should be more negative than late $late") { mid < late }
    }

    private inline fun assertTrue(message: String, condition: () -> Boolean) {
        if (!condition()) throw AssertionError(message)
    }
}
