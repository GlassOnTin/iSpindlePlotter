package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class FermentSegmenterTest {

    private val hourMs = 3_600_000L

    @Test fun `clean single ferment returns one segment`() {
        val (ts, sgs) = syntheticLogistic(start = 0L, durationH = 30.0, og = 1.050, fg = 1.012)
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("expected one segment", 1, segs.size)
        val s = segs.single()
        assertTrue("og ${s.ogObserved} should be near 1.050", s.ogObserved in 1.048..1.051)
        assertTrue("fg ${s.fgObserved} should be near 1.012", s.fgObserved in 1.011..1.014)
        assertTrue("duration ${s.durationHours} should be ~30h", s.durationHours in 29.0..31.0)
    }

    @Test fun `gap of 12h between two ferments yields two segments`() {
        val (ts1, s1) = syntheticLogistic(0L, 30.0, og = 1.050, fg = 1.012)
        val (ts2, s2) = syntheticLogistic(
            start = ts1.last() + 12 * hourMs,
            durationH = 30.0, og = 1.060, fg = 1.014
        )
        val ts = ts1 + ts2
        val sgs = s1 + s2
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("expected two segments", 2, segs.size)
        // Synthetic logistic at h=0 hasn't quite reached its asymptote, so
        // observed OG sits a few mSG below the parameter. Bounds reflect
        // the actual achievable max.
        assertTrue("first OG ${segs[0].ogObserved} should sit near 1.050", segs[0].ogObserved in 1.046..1.052)
        assertTrue("second OG ${segs[1].ogObserved} should sit near 1.060", segs[1].ogObserved in 1.055..1.062)
    }

    @Test fun `sudden 10mSG rise mid-stream is treated as a new ferment boundary`() {
        // First ferment finishes near 1.012; the user pours in fresh wort
        // and SG jumps to 1.060 between two consecutive readings.
        val (ts1, s1) = syntheticLogistic(0L, 30.0, og = 1.050, fg = 1.012)
        // Second ferment begins immediately (no time gap) but starts at 1.060.
        val (ts2, s2) = syntheticLogistic(
            start = ts1.last() + 5 * 60_000L,  // 5 min later, no gap
            durationH = 30.0, og = 1.060, fg = 1.014
        )
        val ts = ts1 + ts2
        val sgs = s1 + s2
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("expected two segments split on the rise", 2, segs.size)
    }

    @Test fun `flat session in water is rejected (no qualifying drop)`() {
        // 24h of readings clustered at 1.000 ± noise — calibration session.
        val n = 24 * 6  // every 10 min
        val ts = LongArray(n) { it.toLong() * 600_000L }
        val sgs = DoubleArray(n) { 1.000 + (kotlin.random.Random(42).nextDouble() - 0.5) * 0.0008 }
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("water-only session is not a ferment", 0, segs.size)
    }

    @Test fun `short ferment under MIN_FERMENT_HOURS is rejected`() {
        val (ts, sgs) = syntheticLogistic(0L, durationH = 4.0, og = 1.050, fg = 1.012)
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("4h ferment is below the 6h floor", 0, segs.size)
    }

    @Test fun `low-drop ferment under MIN_FERMENT_DROP is rejected`() {
        // 30h span but only a 2 mSG drop — looks like calibration drift, not
        // a real ferment.
        val (ts, sgs) = syntheticLogistic(0L, 30.0, og = 1.050, fg = 1.048)
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("2 mSG drop is below the 5 mSG floor", 0, segs.size)
    }

    @Test fun `conditioning resumed after a logging gap stays one segment`() {
        // A real ferment: 30 h active descent OG 1.050 → ~1.012, then the
        // logger drops out for 72 h (device offline during cold
        // conditioning), then 24 h of flat conditioning resumes at the same
        // SG. A bare gap rule would cut here and discard the conditioning
        // tail; the continuity rule keeps it as one ferment because the SG
        // picks up where it left off.
        val (ts1, s1) = syntheticLogistic(0L, 30.0, og = 1.050, fg = 1.012)
        val resumeStart = ts1.last() + 72 * hourMs
        val condN = 24 * 6  // 24 h every 10 min
        val ts2 = LongArray(condN) { resumeStart + it.toLong() * 600_000L }
        // Flat at ~1.012 with sub-mSG noise — same SG regime as pre-gap.
        val rng = kotlin.random.Random(7)
        val s2 = DoubleArray(condN) { 1.012 + (rng.nextDouble() - 0.5) * 0.0006 }
        val ts = ts1 + ts2
        val sgs = s1 + s2

        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("gap during conditioning must not split the ferment", 1, segs.size)
        val s = segs.single()
        assertEquals(
            "segment must extend to the last conditioning reading",
            ts2.last(), s.endMs
        )
        assertTrue("fg ${s.fgObserved} should be near the 1.012 plateau", s.fgObserved in 1.010..1.014)
    }

    @Test fun `gap with a fresh-wort rise still splits into two segments`() {
        // First ferment finishes near 1.012; 72 h offline; then fresh
        // high-gravity wort (OG 1.060) is poured in — an SG discontinuity
        // across the gap, so the continuity rule still cuts.
        val (ts1, s1) = syntheticLogistic(0L, 30.0, og = 1.050, fg = 1.012)
        val (ts2, s2) = syntheticLogistic(
            start = ts1.last() + 72 * hourMs,
            durationH = 30.0, og = 1.060, fg = 1.014
        )
        val ts = ts1 + ts2
        val sgs = s1 + s2
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("fresh-wort rise across a gap is a real boundary", 2, segs.size)
    }

    @Test fun `gap into a low-SG water session does not get absorbed into the ferment`() {
        // First ferment finishes near 1.012; 72 h offline; the device is
        // then re-floated in plain water (~1.000) for calibration — a
        // downward SG discontinuity. The drop must cut, so the water
        // session is not merged into the ferment (which would drag its
        // observed FG down to 1.000).
        val (ts1, s1) = syntheticLogistic(0L, 30.0, og = 1.050, fg = 1.012)
        val waterStart = ts1.last() + 72 * hourMs
        val waterN = 24 * 6
        val tsW = LongArray(waterN) { waterStart + it.toLong() * 600_000L }
        val rng = kotlin.random.Random(11)
        val sW = DoubleArray(waterN) { 1.000 + (rng.nextDouble() - 0.5) * 0.0008 }
        val ts = ts1 + tsW
        val sgs = s1 + sW

        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("only the ferment qualifies; the water session is rejected", 1, segs.size)
        val s = segs.single()
        assertTrue(
            "ferment FG ${s.fgObserved} must reflect the 1.012 plateau, not the 1.000 water tail",
            s.fgObserved in 1.010..1.014
        )
        assertTrue("segment must end before the water session", s.endMs <= ts1.last())
    }

    @Test fun `defaultSelection prefers the live brew over a stale finished segment`() {
        val (ts, sgs) = syntheticLogistic(0L, 30.0, og = 1.050, fg = 1.012)
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals(1, segs.size)

        // No segments → null.
        assertEquals(null, FermentSegmenter.defaultSelection(emptyList(), ts.last()))
        // Latest reading inside the last segment → that segment.
        assertEquals(
            segs.lastIndex,
            FermentSegmenter.defaultSelection(segs, segs.last().endMs)
        )
        // Latest reading a full gap (> 8 h) past the last segment → null,
        // so the UI shows "All" and the live unqualified brew is visible.
        val liveTailMs = segs.last().endMs + 20 * hourMs
        assertEquals(null, FermentSegmenter.defaultSelection(segs, liveTailMs))
        // Null latest timestamp → fall back to the last segment.
        assertEquals(segs.lastIndex, FermentSegmenter.defaultSelection(segs, null))
    }

    @Test fun `temperature excursion that recovers is rejected via trend test`() {
        // 30h span where SG dips by 8 mSG mid-window and recovers — net drop
        // is only ~1 mSG even though the range is large. Should not register
        // as a ferment.
        val n = 30 * 6
        val ts = LongArray(n) { it.toLong() * 600_000L }
        val sgs = DoubleArray(n) { i ->
            val t = i / 6.0  // hours
            val excursion = if (t in 10.0..15.0) -0.008 else 0.0
            1.0500 + excursion
        }
        val segs = FermentSegmenter.detect(ts, sgs)
        assertEquals("excursion-and-recover is not a ferment", 0, segs.size)
    }

    /**
     * Generates a logistic SG curve from `og` to `fg` over `durationH`,
     * sampled every 10 minutes. Returns (timestamps, SGs).
     */
    private fun syntheticLogistic(
        start: Long,
        durationH: Double,
        og: Double,
        fg: Double,
        intervalMin: Int = 10
    ): Pair<LongArray, DoubleArray> {
        val n = (durationH * 60 / intervalMin).toInt() + 1
        val ts = LongArray(n) { start + it.toLong() * intervalMin * 60_000L }
        val k = 6.0 / durationH
        val tMid = durationH / 2.0
        val sgs = DoubleArray(n) {
            val h = it.toDouble() * intervalMin / 60.0
            fg + (og - fg) / (1.0 + exp(k * (h - tMid)))
        }
        return ts to sgs
    }
}
