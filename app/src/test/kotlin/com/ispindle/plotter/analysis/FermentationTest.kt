package com.ispindle.plotter.analysis

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FermentationTest {

    @Test fun `flat early data classifies as Lag`() {
        val xs = DoubleArray(40) { it * 0.25 }   // 0..10 hours
        val ys = DoubleArray(40) { 1.0524 + 0.0002 * kotlin.math.sin(it * 0.5) }
        val s = Fermentation.analyse(xs, ys)
        assertTrue("got $s", s is Fermentation.State.Lag)
        s as Fermentation.State.Lag
        assertEquals(1.0524, s.og, 0.0005)
    }

    @Test fun `mid-S-curve classifies as Active with reasonable ETA`() {
        val og = 1.060; val fg = 1.012; val k = 0.15; val tMid = 36.0
        // Sample only the active descent up to t = 32 — past lag, before
        // the asymptote would otherwise be observed.
        val xs = DoubleArray(64) { it * 0.5 }
        val ys = DoubleArray(64) { fg + (og - fg) / (1.0 + exp(k * (xs[it] - tMid))) }
        val s = Fermentation.analyse(xs, ys)
        assertTrue("got $s", s is Fermentation.State.Active)
        s as Fermentation.State.Active
        assertEquals(og, s.og, 0.002)
        assertTrue("predicted FG ${s.predictedFg} should be plausibly low") {
            s.predictedFg in 1.005..1.020
        }
        assertTrue("rate ${s.ratePerHour} should be clearly negative") { s.ratePerHour < -0.0005 }
        assertTrue("ETA ${s.etaToFinishHours} should be positive and finite") {
            s.etaToFinishHours != null && s.etaToFinishHours!! > 0.0
        }
    }

    @Test fun `flat tail with significant prior drop classifies as Stuck`() {
        val xs = DoubleArray(80) { it * 0.5 }   // 0..40 hours
        val ys = DoubleArray(80) {
            // Drops fast in first 10 h, then plateaus at 1.040 for 30 h.
            if (xs[it] < 10.0) 1.060 - 0.0020 * xs[it] else 1.040
        }
        val s = Fermentation.analyse(xs, ys)
        assertTrue("got $s", s is Fermentation.State.Stuck)
        s as Fermentation.State.Stuck
        assertEquals(1.060, s.og, 0.001)
        assertEquals(1.040, s.current, 0.001)
        assertTrue("flat hours ${s.flatHours} should be > 20") { s.flatHours > 20.0 }
    }

    @Test fun `data near asymptote with prior drop classifies as Conditioning`() {
        val og = 1.054; val fg = 1.012
        val xs = DoubleArray(100) { it * 0.5 }
        val ys = DoubleArray(100) {
            // Logistic that has fully reached FG by the end of the window.
            fg + (og - fg) / (1.0 + exp(0.4 * (xs[it] - 12.0)))
        }
        val s = Fermentation.analyse(xs, ys)
        assertTrue("got $s", s is Fermentation.State.Conditioning)
        s as Fermentation.State.Conditioning
        assertEquals(og, s.og, 0.001)
        assertEquals(fg, s.fg, 0.002)
    }

    @Test fun `early lag with a float drop-in spike stays Lag, not Slowing`() {
        // ~4 h of a fresh, barely-fermenting wort flat at 1.0770, but the
        // first reading is the drop-in settling spike at 1.1070. Raw
        // max(SG) would make the apparent drop ~0.030 and mislabel this as
        // Slowing; the robust OG keeps the drop near zero -> Lag.
        val xs = DoubleArray(48) { it * 0.0833 }   // ~5 min cadence, ~4 h
        val ys = DoubleArray(48) { if (it == 0) 1.1070 else 1.0770 + 0.0002 * kotlin.math.sin(it * 0.5) }
        val s = Fermentation.analyse(xs, ys)
        assertTrue("got $s — spike must not push this past Lag", s is Fermentation.State.Lag)
        s as Fermentation.State.Lag
        assertEquals("OG should be the ~1.077 plateau, not the 1.107 spike", 1.0770, s.og, 0.0008)
    }

    @Test fun `under 6 points reports Insufficient`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0)
        val ys = doubleArrayOf(1.060, 1.058, 1.055)
        assertEquals(Fermentation.State.Insufficient, Fermentation.analyse(xs, ys))
    }

    private inline fun assertTrue(message: String, condition: () -> Boolean) {
        if (!condition()) throw AssertionError(message)
    }
}
