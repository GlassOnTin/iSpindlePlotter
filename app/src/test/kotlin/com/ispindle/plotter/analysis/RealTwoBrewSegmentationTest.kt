package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression on a real two-brew capture (`real_two_brew.csv`, exported from
 * a live device DB): brew 1 (Apr 26 → May 9, active ferment then days of
 * cold conditioning, with two multi-day logging outages *inside* the
 * conditioning hold) followed by a 354 h gap and the first ~23 min of a
 * fresh brew (OG ~1.077, no attenuation yet).
 *
 * Pre-fix, the unconditional 8 h gap cut fragmented brew 1's conditioning
 * tail at each logging outage and discarded the fragments (sub-threshold
 * drop), so the brew-1 segment ended mid-cold-crash on May 1 and ~8 days of
 * conditioning data were dropped. With the continuity-gated gap cut, the
 * outages (SG continuous across the gap) no longer split the ferment, while
 * the 354 h gap into the fresh brew (SG rises 1.011 → 1.107) still does.
 */
class RealTwoBrewSegmentationTest {

    @Test fun `the two-brew stream yields exactly one qualified segment spanning all of brew 1`() {
        val (ts, sgs, _) = loadCapture("real_two_brew.csv")
        val segs = FermentSegmenter.detect(ts, sgs)

        assertEquals("brew 1 must be one segment; brew 2 is too short to qualify", 1, segs.size)
        val s = segs.single()

        // OG is brew 1's max (~1.0527), NOT the brew-2 settling spike (~1.1066).
        assertTrue("OG ${s.ogObserved} should be brew 1's ~1.0527, not brew 2's spike", s.ogObserved in 1.050..1.055)

        // Spans into the conditioning hold past May 1 (the pre-fix cut point
        // at ~139.7 h). Apr 26 01:05 → May 9 02:16 ≈ 313 h including the
        // internal logging gaps.
        assertTrue(
            "duration ${s.durationHours} h should cover conditioning (> 300 h), not stop at the May-1 outage",
            s.durationHours > 300.0
        )

        // End is brew 1's last reading (May 9), strictly before brew 2 starts (May 23).
        val brew2StartMs = 1779570000000L // 2026-05-23 ~20:40 UTC
        assertTrue("segment must end before brew 2 begins", s.endMs < brew2StartMs)
        val brew1EndApproxMs = 1778328000000L // 2026-05-09 ~12:00 UTC (upper bound)
        assertTrue("segment should end around May 9, not May 1", s.endMs in 1778290000000L..brew1EndApproxMs)
    }

    @Test fun `the single segment, fed to the timeline, reads as a conditioning brew not a stalled active one`() {
        val (ts, sgs, temps) = loadCapture("real_two_brew.csv")
        val seg = FermentSegmenter.detect(ts, sgs).single()

        val idx = ts.indices.filter { ts[it] in seg.startMs..seg.endMs }
        val t0 = ts[idx.first()]
        val h = DoubleArray(idx.size) { (ts[idx[it]] - t0) / 3_600_000.0 }
        val segSgs = DoubleArray(idx.size) { sgs[idx[it]] }
        val segTemps = DoubleArray(idx.size) { temps[idx[it]] }

        val tl = Fermentation.buildTimeline(h, segSgs, temps = segTemps)
        assertNotNull("timeline must build on the full brew-1 segment", tl)
        tl!!

        val phase = tl.phaseAt(tl.lastH)
        assertTrue(
            "phase at end should be ColdCrash or Conditioning (cold hold), was $phase",
            phase == Fermentation.Phase.ColdCrash || phase == Fermentation.Phase.Conditioning
        )
        assertTrue(
            "predictedFg ${tl.predictedFg} should reflect the warm asymptote ~1.0135, not the cold-affected SG",
            tl.predictedFg in 1.012..1.018
        )
    }

    /**
     * The same two-brew device, captured later (`real_two_brew_live.csv`):
     * brew 2 is now ~15 h in with a real ~4-6 mSG decline, but its span
     * still opens with the float drop-in settling transient (an up-spike to
     * ~1.107 and a down-spike to ~1.072 on a settled ~1.0775 wort).
     *
     * Pre-fix, raw max/min read OG off the 1.107 spike and inflated `drop`
     * to ~35 mSG, so the trend gate (drop/4 ≈ 8.7 mSG) rejected the genuine
     * ~4 mSG decline and brew 2 never qualified — the selector showed "(1)".
     * With SeriesClean's MAD spike rejection in the qualification pass, brew
     * 2 qualifies as a second segment and the selector can navigate both.
     */
    @Test fun `mature brew 2 qualifies as a second segment despite the settling spike`() {
        val (ts, sgs, _) = loadCapture("real_two_brew_live.csv")
        val segs = FermentSegmenter.detect(ts, sgs)

        assertEquals("both brews must now be detected", 2, segs.size)

        // Segment 2: OG is the settled wort (~1.077), NOT the 1.10657 spike.
        assertTrue("brew 2 OG ${segs[1].ogObserved} should be the settled ~1.077", segs[1].ogObserved in 1.074..1.085)
        assertTrue("brew 2 OG ${segs[1].ogObserved} must not be the >1.09 settling spike", segs[1].ogObserved < 1.09)
        val drop2 = segs[1].ogObserved - segs[1].fgObserved
        assertTrue("brew 2 drop $drop2 should be the real decline, not the ~35 mSG spurious drop", drop2 in 0.003..0.012)
        assertTrue("brew 2 should be a live, multi-hour run", segs[1].durationHours >= 6.0)
        assertTrue("brew 2 must start after brew 1 ends", segs[1].startMs > segs[0].endMs)

        // Segment 1 is unchanged from the earlier capture.
        assertTrue("brew 1 OG ${segs[0].ogObserved} should still be ~1.0527", segs[0].ogObserved in 1.050..1.055)
        assertTrue("brew 1 should still span the conditioning hold (> 300 h)", segs[0].durationHours > 300.0)

        // The live brew (index 1) is auto-selected, not the "All" fallback.
        assertEquals(
            "default selection should be the live brew 2",
            1, FermentSegmenter.defaultSelection(segs, ts.last())
        )
    }

    private fun loadCapture(name: String): Triple<LongArray, DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val tcIdx = header.indexOf("temperature_c")
        val ts = mutableListOf<Long>()
        val sg = mutableListOf<Double>()
        val tc = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val p = line.split(',')
            ts += p[tIdx].toDouble().toLong()
            sg += p[sgIdx].toDouble()
            tc += p[tcIdx].toDouble()
        }
        return Triple(ts.toLongArray(), sg.toDoubleArray(), tc.toDoubleArray())
    }
}
