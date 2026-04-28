package com.ispindle.plotter.analysis

import kotlin.math.E
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttenuationFitTest {

    private val tol = 0.005

    /** Synthetic modified-Gompertz curve generator. */
    private fun gompertz(og: Double, fg: Double, muMax: Double, lambda: Double, t: Double): Double {
        val a = og - fg
        val arg = muMax * E / a * (lambda - t) + 1.0
        return when {
            arg > 30.0 -> og
            arg < -700.0 -> fg
            else -> og - a * exp(-exp(arg))
        }
    }

    @Test fun `recovers known parameters from a clean Gompertz curve`() {
        val og = 1.060; val fg = 1.012; val muMax = 0.0020; val lambda = 12.0
        val xs = DoubleArray(120) { it * 0.5 + 0.0 }
        val ys = DoubleArray(120) { gompertz(og, fg, muMax, lambda, xs[it]) }
        // Pure-MLE test: explicitly disable the v1 attenuation prior so
        // the fit is a pure data least-squares against the synthetic
        // curve. The MAP fit (default) is exercised in the Bayesian test.
        val r = AttenuationFit.fit(xs, ys, attenuationPrior = null)!!
        assertEquals(og, r.og, tol)
        assertEquals(fg, r.fg, tol)
        assertEquals(muMax, r.muMax, 0.0005)
        assertEquals(lambda, r.lambda, 1.5)
        assertTrue("rms ${r.rmsResidual} should be small on noiseless input", r.rmsResidual < 1e-3)
    }

    @Test fun `survives mild noise`() {
        val og = 1.052; val fg = 1.013; val muMax = 0.0015; val lambda = 18.0
        val xs = DoubleArray(200) { it * 0.5 }
        val ys = DoubleArray(200) {
            gompertz(og, fg, muMax, lambda, xs[it]) +
                0.0005 * kotlin.math.sin(it * 0.7)  // ±0.5 SG-points jitter
        }
        val r = AttenuationFit.fit(xs, ys, attenuationPrior = null)!!
        assertEquals(og, r.og, 0.002)
        assertEquals(fg, r.fg, 0.003)
        assertTrue("rms ${r.rmsResidual} should sit at noise level", r.rmsResidual < 1e-3)
    }

    @Test fun `refuses below minimum sample count`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0)
        val ys = doubleArrayOf(1.060, 1.058, 1.055)
        assertNull(AttenuationFit.fit(xs, ys))
    }

    @Test fun `refuses flat data`() {
        val xs = DoubleArray(40) { it.toDouble() }
        val ys = DoubleArray(40) { 1.020 }
        assertNull(AttenuationFit.fit(xs, ys))
    }

    @Test fun `timeToReach inverts predict`() {
        val r = AttenuationFit.Result(
            og = 1.060, fg = 1.012, muMax = 0.002, lambda = 12.0,
            rmsResidual = 0.0, pointCount = 100
        )
        val target = 1.030
        val t = r.timeToReach(target)!!
        assertEquals(target, r.predict(t), 1e-9)
    }

    @Test fun `unreachable target returns null`() {
        val r = AttenuationFit.Result(
            og = 1.060, fg = 1.012, muMax = 0.002, lambda = 12.0,
            rmsResidual = 0.0, pointCount = 100
        )
        assertNull(r.timeToReach(1.005))   // below FG
        assertNull(r.timeToReach(1.080))   // above OG
    }

    /**
     * Sanity: |dSG/dt| peaks at the inflection point t* = lambda + A/(muMax·e),
     * not at the lag time itself, and the peak value equals muMax (the
     * Zwietering parameterisation defines muMax that way).
     */
    @Test fun `peak rate sits at the inflection and equals muMax`() {
        val og = 1.060; val fg = 1.010; val muMax = 0.003; val lambda = 24.0
        val r = AttenuationFit.Result(og, fg, muMax, lambda, 0.0, 100)
        val a = og - fg
        val tInfl = lambda + a / (muMax * E)
        val peak = -r.rateAt(tInfl)               // negative slope → flip sign
        assertEquals("|rate| at inflection should equal muMax", muMax, peak, 1e-6)
        assertTrue("|rate| at lag time should be smaller than at inflection",
            -r.rateAt(lambda) < peak)
        assertTrue("|rate| well past inflection should be smaller",
            -r.rateAt(tInfl + 60.0) < peak)
    }

    @Test fun `lag region holds within a hair of OG`() {
        val og = 1.060; val fg = 1.010; val muMax = 0.003; val lambda = 24.0
        val r = AttenuationFit.Result(og, fg, muMax, lambda, 0.0, 100)
        // By construction, only ~6.6 % of the total drop has occurred at
        // t = lambda. Earlier than lambda the curve must sit even closer
        // to OG.
        val a = og - fg
        val sgAtLambda = r.predict(lambda)
        assertTrue(
            "SG at λ=$lambda should sit within 8 % of A from OG; got $sgAtLambda",
            og - sgAtLambda < 0.08 * a
        )
        val sgEarly = r.predict(lambda - 5.0)
        assertTrue(
            "SG 5h before λ should be even closer to OG; got $sgEarly vs $sgAtLambda",
            og - sgEarly < og - sgAtLambda
        )
    }

    private inline fun assertTrue(message: String, condition: () -> Boolean) {
        if (!condition()) throw AssertionError(message)
    }
}
