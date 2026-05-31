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
 * The selector's job is conservative: keep the simpler single-component
 * model unless the data clearly justifies the richer biphasic one.
 * These tests pin the contract — what gets promoted, what doesn't, and
 * what the wrapper exposes to callers above the fitter layer.
 */
class AttenuationModelSelectorTest {

    @Test fun `unimodal data with no plateau stays single-component`() {
        // Clean Gompertz, no diauxic structure, no Mid plateau detected.
        val n = 200
        val og = 1.060; val fg = 1.012; val muMax = 0.0030; val lambda = 8.0
        val a = og - fg
        val hours = DoubleArray(n) { it * (100.0 / (n - 1)) }
        val sgs = DoubleArray(n) { i ->
            val t = hours[i]
            val arg = muMax * E / a * (lambda - t) + 1.0
            og - a * exp(-exp(arg.coerceIn(-700.0, 30.0)))
        }
        val plateaus = PlateauDetector.detect(hours, sgs)
        val chosen = AttenuationModelSelector.fit(hours, sgs, plateaus)
        assertNotNull(chosen)
        assertTrue("expected SingleGompertz on unimodal; got $chosen",
            chosen is AttenuationModel.SingleGompertz)
    }

    @Test fun `synthetic biphasic data with a detected plateau is promoted`() {
        // True biphasic: two phases with a flat middle the plateau
        // detector will find. The selector should switch to two-component.
        val pts = mutableListOf<Pair<Double, Double>>()
        var t = 0.0
        val rng = Random(7)
        fun add(sg: Double) {
            pts += t to (sg + (rng.nextDouble() - 0.5) * 0.0004)
            t += 0.05
        }
        while (t < 4.0) add(1.052)                              // lag
        while (t < 12.0) { val u = (t - 4.0) / 8.0; add(1.052 - u * 0.022) } // descent 1
        while (t < 16.0) add(1.030)                             // diauxic pause
        while (t < 32.0) { val u = (t - 16.0) / 16.0; add(1.030 - u * 0.020) } // descent 2
        val hours = DoubleArray(pts.size) { pts[it].first }
        val sgs = DoubleArray(pts.size) { pts[it].second }
        val plateaus = PlateauDetector.detect(hours, sgs)
        assertTrue("fixture should produce a Mid plateau the selector can latch on; got $plateaus",
            plateaus.any { it.kind == Plateau.Kind.Mid })
        val chosen = AttenuationModelSelector.fit(hours, sgs, plateaus)
        assertNotNull(chosen)
        assertTrue("expected TwoComponent on biphasic data; got $chosen",
            chosen is AttenuationModel.TwoComponent)
    }

    @Test fun `brew2 capture promotes to two-component`() {
        val (h, sg) = loadHoursSg("ferment_brew2_late.csv")
        val plateaus = PlateauDetector.detect(h, sg)
        val chosen = AttenuationModelSelector.fit(h, sg, plateaus)
        assertNotNull(chosen)
        assertTrue("brew2 is biphasic and should pick TwoComponent; got $chosen",
            chosen is AttenuationModel.TwoComponent)
        chosen as AttenuationModel.TwoComponent
        // RMS should be at least 30 % better than the single-component
        // baseline that the selector would otherwise have returned.
        val single = AttenuationFit.fit(h, sg)!!
        assertTrue(
            "two-component RMS ${chosen.rmsResidual} should beat single ${single.rmsResidual} by ≥ 30 %",
            chosen.rmsResidual < single.rmsResidual * 0.7
        )
        // BIC margin should be solidly past the promotion threshold —
        // brew2 is the canonical biphasic, not a borderline case.
        val singleBic = AttenuationModel.SingleGompertz(single).bic
        val twoBic = chosen.bic
        assertTrue(
            "BIC margin (single $singleBic − two $twoBic = ${singleBic - twoBic}) " +
                "should be > 200 for a clear biphasic capture",
            singleBic - twoBic > 200.0
        )
    }

    @Test fun `data ending right after the diauxic pause stays single`() {
        // A capture that catches phase 1 + the pause but only a few hours
        // of phase 2 isn't enough to identify phase 2's inflection. The
        // MIN_PHASE_2_HOURS guard should hold the selector at single.
        val pts = mutableListOf<Pair<Double, Double>>()
        var t = 0.0
        fun add(sg: Double) { pts += t to sg; t += 0.05 }
        while (t < 4.0) add(1.052)
        while (t < 12.0) { val u = (t - 4.0) / 8.0; add(1.052 - u * 0.022) }
        while (t < 18.0) add(1.030)
        // Only ~2 h past the plateau end (16h is plateau, ends ~18h, capture
        // ends 20h) — too little for phase 2 identification.
        while (t < 20.0) { val u = (t - 18.0) / 4.0; add(1.030 - u * 0.005) }
        val hours = DoubleArray(pts.size) { pts[it].first }
        val sgs = DoubleArray(pts.size) { pts[it].second }
        val plateaus = PlateauDetector.detect(hours, sgs)
        val chosen = AttenuationModelSelector.fit(hours, sgs, plateaus)
        assertNotNull(chosen)
        assertTrue(
            "expected SingleGompertz when phase 2 evidence is too thin; got $chosen",
            chosen is AttenuationModel.SingleGompertz
        )
    }

    @Test fun `selector returns null only when single fit itself fails`() {
        // Pathological input: empty arrays. The selector should not
        // throw — it should return null.
        assertNull(AttenuationModelSelector.fit(DoubleArray(0), DoubleArray(0), emptyList()))
    }

    @Test fun `model wrapper exposes consistent og fg predictions`() {
        // Round-trip test: fit a synthetic, wrap the result, confirm the
        // polymorphic API agrees with the underlying fitter values.
        val n = 100
        val og = 1.050; val fg = 1.012; val muMax = 0.003; val lambda = 6.0
        val a = og - fg
        val hours = DoubleArray(n) { it * (60.0 / (n - 1)) }
        val sgs = DoubleArray(n) { i ->
            val t = hours[i]
            val arg = muMax * E / a * (lambda - t) + 1.0
            og - a * exp(-exp(arg.coerceIn(-700.0, 30.0)))
        }
        val singleFit = AttenuationFit.fit(hours, sgs)!!
        val wrapped = AttenuationModel.SingleGompertz(singleFit)
        assertEquals("og round-trips", singleFit.og, wrapped.og, 1e-12)
        assertEquals("fg round-trips", singleFit.fg, wrapped.fg, 1e-12)
        assertEquals("predict round-trips at t=30",
            singleFit.predict(30.0), wrapped.predict(30.0), 1e-12)
        assertEquals("rateAt round-trips at t=10",
            singleFit.rateAt(10.0), wrapped.rateAt(10.0), 1e-12)
    }

    private fun loadHoursSg(name: String): Pair<DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val ts = mutableListOf<Long>(); val sgs = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val p = line.split(',')
            ts += p[tIdx].toDouble().toLong(); sgs += p[sgIdx].toDouble()
        }
        val t0 = ts.first()
        val h = DoubleArray(ts.size) { (ts[it] - t0) / 3_600_000.0 }
        return h to sgs.toDoubleArray()
    }
}
