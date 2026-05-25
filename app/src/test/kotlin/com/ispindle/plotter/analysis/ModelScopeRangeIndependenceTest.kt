package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SG model must depend on the fermentation data partition, not on the
 * chart's time-window selector. GraphScreen scopes the analyser to the
 * current episode ([FermentSegmenter.latestEpisodeStartMs]) rather than to
 * the (24 h / 7 d / 30 d / All) slice. These tests pin that the episode
 * scope is window-free and lands the model on the right OG, on the real
 * captures where a time slice used to mis-frame it.
 *
 * Reproduction this guards against (pre-fix, fitting the time slice):
 *  - long-cold brew, OG 1.0527 → FG 1.0081 over ~239 h:
 *      24 h slice → Lag(OG≈1.008), 7 d slice → ColdCrash(OG≈1.013),
 *      All → ColdCrash(OG≈1.0527). Three different stories for one brew.
 *  - two-brew live: All folds the finished brew 1 into the live brew 2's
 *      OG; the episode scope sees brew 2 alone.
 */
class ModelScopeRangeIndependenceTest {

    /** Fit the timeline on the readings at or after [fromMs] (episode scope). */
    private fun ogFrom(ts: LongArray, sg: DoubleArray, tc: DoubleArray, fromMs: Long): Pair<String, Double> {
        val idx = ts.indices.filter { ts[it] >= fromMs }
        val t0 = ts[idx.first()]
        val h = DoubleArray(idx.size) { (ts[idx[it]] - t0) / 3_600_000.0 }
        val s = DoubleArray(idx.size) { sg[idx[it]] }
        val t = DoubleArray(idx.size) { tc[idx[it]] }
        val tl = Fermentation.buildTimeline(h, s, null, t)
        val state = if (tl != null) Fermentation.stateAt(tl, h, s, tl.lastH)
        else Fermentation.analyse(h, s, null)
        val og = when (state) {
            is Fermentation.State.Active -> state.og
            is Fermentation.State.Slowing -> state.og
            is Fermentation.State.Conditioning -> state.og
            is Fermentation.State.Stuck -> state.og
            is Fermentation.State.ColdCrash -> state.og
            is Fermentation.State.Lag -> state.og
            else -> Double.NaN
        }
        return state::class.simpleName!! to og
    }

    /** Last-`hours` tail slice, the way the chart window used to scope it. */
    private fun tailCutoff(ts: LongArray, hours: Long): Long = ts.last() - hours * 3_600_000L

    @Test fun `single-episode brew is scoped to the whole brew regardless of window`() {
        val (ts, sg, tc) = load("ferment_capture_long_cold.csv")

        // No regime change within this capture → the episode is the whole
        // stream, so the model scope is window-independent by construction.
        val epStart = FermentSegmenter.latestEpisodeStartMs(ts, sg)!!
        assertEquals("single-episode capture should start at the first reading", ts.first(), epStart)

        val (_, ogEpisode) = ogFrom(ts, sg, tc, epStart)
        assertTrue("episode-scoped OG $ogEpisode should be the brew's real OG ~1.0527", ogEpisode in 1.050..1.055)

        // Pre-fix the 24 h tail slice read a wrong OG near the cold-tail SG;
        // confirm the slice really does mis-frame it, so the scope fix is
        // doing load-bearing work, not papering over an already-equal case.
        val (_, og24h) = ogFrom(ts, sg, tc, tailCutoff(ts, 24))
        assertTrue("a 24 h tail slice mis-reads OG (was ~1.008), got $og24h", og24h < 1.02)
    }

    @Test fun `two-brew live stream scopes the model to the live brew, not the whole history`() {
        val (ts, sg, tc) = load("real_two_brew_live.csv")

        val segs = FermentSegmenter.detect(ts, sg)
        assertEquals("brew 1 qualifies; brew 2 is the fresh live episode", 2, segs.size)

        // The episode containing the latest reading is brew 2 (after the
        // long gap + SG rise into fresh wort), not brew 1.
        val epStart = FermentSegmenter.latestEpisodeStartMs(ts, sg)!!
        assertEquals("episode start should be brew 2's first reading", segs[1].startMs, epStart)

        val (_, ogEpisode) = ogFrom(ts, sg, tc, epStart)
        assertTrue("episode-scoped OG $ogEpisode should be brew 2's ~1.077, not brew 1's ~1.0527", ogEpisode in 1.074..1.080)

        // 24 h and 7 d windows of this episode are identical data (brew 2 is
        // only ~15 h old), so the episode-scoped fit is the same either way.
        val (s24, og24) = ogFrom(ts, sg, tc, maxOf(epStart, tailCutoff(ts, 24)))
        val (s7, og7) = ogFrom(ts, sg, tc, maxOf(epStart, tailCutoff(ts, 24 * 7)))
        assertEquals("state must match across windows", s24, s7)
        assertEquals("OG must match across windows", og24, og7, 1e-9)
    }

    private fun load(name: String): Triple<LongArray, DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val tcIdx = header.indexOf("temperature_c")
        val ts = mutableListOf<Long>(); val sg = mutableListOf<Double>(); val tc = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val p = line.split(',')
            ts += p[tIdx].toDouble().toLong(); sg += p[sgIdx].toDouble(); tc += p[tcIdx].toDouble()
        }
        return Triple(ts.toLongArray(), sg.toDoubleArray(), tc.toDoubleArray())
    }
}
