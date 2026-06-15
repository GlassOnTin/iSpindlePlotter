package com.ispindle.plotter.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the Half-Light Hefeweizen capture (OG 1.049, FG 1.008):
 * a real ferment with a start krausen/CO2 false-rise, a mid-ferment diauxic
 * pause (~h60-81 at ~1.013), and a cold crash (~h84).
 *
 * Previously the pause was hidden: its resume was masked by the cold crash
 * starting right after it (tripping the resume gate), and the [PlateauDetector]
 * merge swept the warm pause forward into the long flat cold-conditioning tail.
 * The detector now (a) accepts a Mid whose end sits at a cold-crash onset and
 * (b) refuses to merge a pre-crash plateau across that onset.
 */
class HefeweizenDiagnosticTest {

    private fun load(name: String): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val lines = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readLines()
        val h = lines.first().split(',')
        val ti = h.indexOf("timestamp_ms"); val gi = h.indexOf("computed_gravity"); val ci = h.indexOf("temperature_c")
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ts = ArrayList<Double>()
        for (l in lines.drop(1)) {
            val p = l.split(','); xs += p[ti].toDouble(); ys += p[gi].toDouble(); ts += p[ci].toDouble()
        }
        val t0 = xs.first()
        return Triple(DoubleArray(xs.size) { (xs[it] - t0) / 3_600_000.0 }, ys.toDoubleArray(), ts.toDoubleArray())
    }

    /**
     * The current (warm, no cold crash) Hefeweizen settles at ~1.049 then has
     * an early krausen/CO2 rise peaking ~1.052. OG must read the settled lag
     * level, not the inflated krausen peak (raw robust max).
     */
    @Test
    fun ogIgnoresKrausenRise() {
        val (hours, sgs, temps) = load("ferment_hefeweizen_current.csv")
        // The raw robust max is the krausen peak.
        assertTrue("raw robustOg should be the krausen peak ~1.052", SeriesClean.robustOg(hours, sgs, temps) > 1.0510)
        val tl = Fermentation.buildTimeline(hours, sgs, calRSquared = 0.9959, temps = temps)
        assertNotNull("timeline should build", tl)
        assertTrue(
            "timeline OG should track the settled lag level ~1.049, not the krausen peak; was ${tl!!.og}",
            tl.og in 1.0470..1.0500
        )
        assertNull("no cold crash in this ferment", tl.coldCrashOnsetH)
    }

    @Test
    fun detectsTheMidFermentPause() {
        val (hours, sgs, temps) = load("ferment_hefeweizen.csv")
        val tl = Fermentation.buildTimeline(hours, sgs, calRSquared = 0.9959, temps = temps)
        assertNotNull("timeline should build", tl)
        val mids = tl!!.plateaus.filter { it.kind == Plateau.Kind.Mid }

        // The real diauxic pause (~h60-81, ~1.013) must be detected. It ends
        // right at the cold-crash onset, which used to hide it.
        val pause = mids.firstOrNull { it.startH in 45.0..72.0 && it.durationH >= 12.0 }
        assertNotNull("mid-ferment pause (~h60-81) should be detected; mids=$mids", pause)
        assertTrue(
            "pause SG should sit near the diauxic level ~1.013, was ${pause!!.sg}",
            pause.sg in 1.010..1.016
        )
    }
}
