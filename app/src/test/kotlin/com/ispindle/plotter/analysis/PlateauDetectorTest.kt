package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class PlateauDetectorTest {

    @Test fun `flat data produces a single Lag-or-Tail plateau covering the whole span`() {
        // Sustained flat at SG=1.05 for 6 h, sampled every 3 min (120 points).
        // Both endpoints touch the data boundaries, so by Kind precedence the
        // plateau is classified Lag (start touches 0).
        val n = 120
        val hours = DoubleArray(n) { it * (6.0 / (n - 1)) }
        val sgs = DoubleArray(n) { 1.0500 + 0.0001 * Random(42).nextDouble() }
        val plateaus = PlateauDetector.detect(hours, sgs)
        assertEquals("one plateau across the whole flat span", 1, plateaus.size)
        val p = plateaus.single()
        assertEquals(Plateau.Kind.Lag, p.kind)
        assertTrue("startH ${p.startH} should be near 0", p.startH < 0.5)
        assertTrue("endH ${p.endH} should be near 6", p.endH > 5.5)
    }

    @Test fun `pure descent has no plateau detected`() {
        // Logistic curve descending from 1.05 → 1.01 over 30 h with no flat
        // plateau anywhere — the slope is non-trivial across the whole span.
        val n = 600
        val hours = DoubleArray(n) { it * (30.0 / (n - 1)) }
        val og = 1.050; val fg = 1.010; val k = 0.4; val tMid = 15.0
        val sgs = DoubleArray(n) { fg + (og - fg) / (1.0 + exp(k * (hours[it] - tMid))) }
        val plateaus = PlateauDetector.detect(hours, sgs)
        // The asymptotic flat tail is *very* slow (well below threshold near
        // the end) so a Tail plateau may appear as the curve flattens
        // toward fg. What must NOT appear is any Mid plateau.
        val mids = plateaus.filter { it.kind == Plateau.Kind.Mid }
        assertEquals("no mid plateau on a clean monotonic descent: $plateaus", 0, mids.size)
    }

    @Test fun `synthetic biphasic ferment is detected as Mid plateau between two descents`() {
        // Construct a plausible biphasic ferment:
        //   h 0-4    : lag plateau at 1.052
        //   h 4-12   : first descent 1.052 → 1.030
        //   h 12-15  : MID plateau at 1.030 (3 h flat)
        //   h 15-25  : second descent 1.030 → 1.010
        // This is the diauxic-shift signature we want to surface.
        val pts = mutableListOf<Pair<Double, Double>>()
        var t = 0.0
        val rng = Random(1234)
        fun add(sg: Double) {
            pts += t to (sg + (rng.nextDouble() - 0.5) * 0.0004)
            t += 0.05  // 3 min
        }
        while (t < 4.0) add(1.052)
        while (t < 12.0) {
            val u = (t - 4.0) / 8.0
            add(1.052 - u * 0.022)
        }
        while (t < 15.0) add(1.030)
        while (t < 25.0) {
            val u = (t - 15.0) / 10.0
            add(1.030 - u * 0.020)
        }
        val hours = DoubleArray(pts.size) { pts[it].first }
        val sgs = DoubleArray(pts.size) { pts[it].second }

        val plateaus = PlateauDetector.detect(hours, sgs)
        val lag = plateaus.firstOrNull { it.kind == Plateau.Kind.Lag }
        val mid = plateaus.firstOrNull { it.kind == Plateau.Kind.Mid }

        assertTrue("expected a Lag plateau; got $plateaus", lag != null)
        assertTrue("expected a Mid plateau; got $plateaus", mid != null)

        mid!!
        assertTrue(
            "Mid SG ${mid.sg} should be ~1.030",
            mid.sg in 1.028..1.032
        )
        assertTrue(
            "Mid duration ${mid.durationH} should be ~3 h",
            mid.durationH in 2.5..4.0
        )
        assertTrue(
            "Mid startH ${mid.startH} should sit at ~12 h",
            mid.startH in 10.5..13.5
        )
    }

    @Test fun `runs shorter than MIN_PLATEAU_HOURS are filtered out`() {
        // Lag at 1.05 for 1 h then immediate descent. 1 h is well below
        // the MIN_PLATEAU_HOURS floor, so the detector should report
        // nothing (or a Tail-only run from the descent's end if it hits
        // its own floor; we don't care which, as long as no Lag/Mid).
        val pts = mutableListOf<Pair<Double, Double>>()
        var t = 0.0
        while (t < 1.0) { pts += t to 1.0500; t += 0.05 }
        while (t < 10.0) { pts += t to (1.0500 - (t - 1.0) * 0.004); t += 0.05 }
        val hours = DoubleArray(pts.size) { pts[it].first }
        val sgs = DoubleArray(pts.size) { pts[it].second }
        val plateaus = PlateauDetector.detect(hours, sgs)
        assertTrue(
            "no Lag or Mid plateau expected from a 1 h flat segment: $plateaus",
            plateaus.none { it.kind == Plateau.Kind.Lag || it.kind == Plateau.Kind.Mid }
        )
    }

    @Test fun `insufficient data returns empty list`() {
        assertEquals(emptyList<Plateau>(), PlateauDetector.detect(DoubleArray(0), DoubleArray(0)))
        // n=3 below the n>=6 floor.
        assertEquals(
            emptyList<Plateau>(),
            PlateauDetector.detect(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(1.05, 1.05, 1.05))
        )
    }
}
