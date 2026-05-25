package com.ispindle.plotter.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Slowing" is the asymptotic approach to FG, not any slow window-averaged
 * rate. A big beer that pauses mid-active — vigorous descent, then a flat
 * patch while still well above FG (a diauxic shift or a temperature dip) —
 * must stay Active, with the pause reported, not flip to Slowing.
 *
 * Guards the absolute proximity gate ([Fermentation.SLOWING_NEAR_FG_GAP]):
 * the fractional attenuation gate alone scaled with beer size and let
 * "slowing" fire ~25 mSG short of FG on a 60 mSG-drop beer.
 */
class SlowingNearFgTest {

    /**
     * Real capture: OG ≈ 1.077 brew that descended hard (peak ≈ −5 mSG/h),
     * then went flat ~6 h ago at SG ≈ 1.037 — still ~18 mSG above the
     * predicted FG (≈ 1.0185). Pre-fix this read 70 % attenuated + slow
     * rate → Slowing. It is mid-ferment: must be Active.
     */
    @Test fun `big beer paused well above FG stays Active, not Slowing`() {
        val (h, sg, tc) = load("ferment_capture_brew2_paused.csv")
        val tl = Fermentation.buildTimeline(h, sg, null, tc)!!
        val state = Fermentation.stateAt(tl, h, sg, tl.lastH)

        assertTrue("OG ${tl.og} should be near the observed max ~1.077", tl.og in 1.074..1.080)
        assertTrue(
            "current ${sg.last()} should still be well above predicted FG ${tl.predictedFg} " +
                "(gap ${"%.4f".format(sg.last() - tl.predictedFg)} > ${Fermentation.SLOWING_NEAR_FG_GAP})",
            sg.last() - tl.predictedFg > Fermentation.SLOWING_NEAR_FG_GAP
        )
        assertTrue("slowing onset must not fire mid-ferment; got ${tl.slowingOnsetH}", tl.slowingOnsetH == null)
        assertTrue("expected Active for a paused mid-ferment brew; got $state", state is Fermentation.State.Active)
        state as Fermentation.State.Active
        // The ongoing flat patch must register as the *current* pause at the
        // live edge so the headline view shows diauxic-shift guidance — not
        // only when a cursor is scrubbed into a past pause. PlateauDetector
        // ends the plateau ~1 h short of "now"; live-edge recognition bridges
        // that. (Regression: without it currentPause is structurally null.)
        assertTrue(
            "the live brew is sitting in a pause; currentPause should be set, got ${state.currentPause}",
            state.currentPause != null
        )
        assertTrue(
            "the current pause SG ${state.currentPause?.sg} should be the ~1.037 plateau",
            state.currentPause!!.sg in 1.034..1.040
        )
    }

    /**
     * Complementary side of the gate: a brew genuinely closing on FG — a
     * slow but still-descending tail within the near-FG band — must reach a
     * finishing state (Slowing or Conditioning), not be held Active by the
     * gap clause. Confirms the absolute gap tightened the phase rather than
     * disabling it.
     *
     * Piecewise synthetic: ~30 h of active descent 1.050 → 1.016, then a
     * gentle slowing tail to ~1.013 (rate ≈ −0.2 mSG/h — in the slowing
     * band, not dead-flat, so it isn't read as Stuck).
     */
    @Test fun `brew closing on FG with a slow tail reaches a finishing state`() {
        val n = 180
        val h = DoubleArray(n) { it * 0.25 }
        val sg = DoubleArray(n) { i ->
            val t = h[i]
            if (t <= 30.0) 1.050 - 0.00113 * t                 // active descent
            else 1.0161 - 0.0002 * (t - 30.0)                  // gentle slowing tail
        }
        val tl = Fermentation.buildTimeline(h, sg, null, null)!!
        val state = Fermentation.stateAt(tl, h, sg, tl.lastH)
        val gap = sg.last() - tl.predictedFg
        assertTrue(
            "fixture should sit inside the near-FG band (gap ${"%.4f".format(gap)})",
            gap <= Fermentation.SLOWING_NEAR_FG_GAP
        )
        assertTrue(
            "a brew closing on FG should read Slowing or Conditioning, not be held Active; got $state",
            state is Fermentation.State.Slowing || state is Fermentation.State.Conditioning
        )
    }

    private fun load(name: String): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val tcIdx = header.indexOf("temperature_c")
        val ts = mutableListOf<Long>(); val sgs = mutableListOf<Double>(); val tcs = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val p = line.split(',')
            ts += p[tIdx].toDouble().toLong(); sgs += p[sgIdx].toDouble(); tcs += p[tcIdx].toDouble()
        }
        val t0 = ts.first()
        val h = DoubleArray(ts.size) { (ts[it] - t0) / 3_600_000.0 }
        return Triple(h, sgs.toDoubleArray(), tcs.toDoubleArray())
    }
}
