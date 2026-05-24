package com.ispindle.plotter.analysis

/**
 * One contiguous span of readings that looks like a real fermentation.
 *
 * Carries enough metadata for the UI to label and pick between segments,
 * and for the analyser to scope its work. SG fields are observed values
 * at the segment boundaries — the model fitter, when run on the segment,
 * may give slightly different OG/FG estimates.
 */
data class FermentSegment(
    val startMs: Long,
    val endMs: Long,
    /** Maximum SG observed in the segment (≈ OG before the prior bites). */
    val ogObserved: Double,
    /** Most recent SG in the segment (≈ FG when complete). */
    val fgObserved: Double,
    val pointCount: Int
) {
    val durationHours: Double get() = (endMs - startMs) / 3_600_000.0
}

/**
 * Splits a readings stream into contiguous fermentation episodes.
 *
 * The Room database for a device collects every reading the iSpindel has
 * ever sent — multiple ferments, calibration sessions in water, periods
 * spent dry on a shelf. The chart and analyser otherwise treat all of
 * that as one continuous time series, which mis-frames the model and
 * blunts the time-window filters.
 *
 * Segmentation is two-pass:
 *
 *  1. **Boundary detection.** Cut between consecutive readings whenever
 *     either is true:
 *     - a time gap longer than [GAP_THRESHOLD_HOURS] (device offline)
 *       *that also coincides with an SG discontinuity* — the SG on
 *       resuming differs from the pre-gap SG by more than
 *       [NEW_FERMENT_RISE]. A gap alone is not a cut: a logging outage
 *       during a long cold-conditioning hold resumes at the same SG and
 *       belongs to the same ferment (the timeline / cold-crash logic is
 *       built to model exactly that gappy-but-single curve). A genuine
 *       new brew across a gap shows a rise (fresh high-gravity wort);
 *       a swap to water/another liquid shows a drop — both are
 *       discontinuities and both cut.
 *     - a single-step SG rise greater than [NEW_FERMENT_RISE] (fresh
 *       wort poured in, or the device was lifted out and re-floated in
 *       a different liquid).
 *
 *  2. **Qualification.** Each candidate span is promoted to a
 *     [FermentSegment] only if all of these hold:
 *     - duration ≥ [MIN_FERMENT_HOURS],
 *     - max SG − min SG ≥ [MIN_FERMENT_DROP] (real attenuation, not
 *       calibration-in-water noise),
 *     - head-window median SG > tail-window median SG by at least a
 *       quarter of the observed drop (a sustained downward trend, not
 *       random noise that happened to span [MIN_FERMENT_DROP]). The
 *       windows are the first/last [TREND_WINDOW_FRACTION] of the span,
 *       not index halves — so a long flat conditioning tail (which now
 *       stays attached to its ferment, see boundary detection) can't
 *       drag the comparison points into the plateau and reject a real
 *       brew.
 *
 * The criteria are deliberately conservative — short or low-drop
 * ferments (cider top-up, a wine ferment that finishes in 4 h, etc.)
 * will be missed and the user falls back to "All". Tightening for
 * recall will need either user-driven overrides or a more
 * sophisticated classifier; out of scope for the v0 segmenter.
 */
object FermentSegmenter {

    private const val GAP_THRESHOLD_HOURS = 8.0
    private const val NEW_FERMENT_RISE = 0.005
    private const val MIN_FERMENT_DROP = 0.005
    private const val MIN_FERMENT_HOURS = 6.0
    private const val MIN_POINTS_PER_SEGMENT = 6

    /**
     * Fraction of a span used as the head/tail comparison windows in the
     * trend test. Narrow enough that the head window captures the
     * high-SG start of a ferment even when a long flat conditioning tail
     * dominates the point count, wide enough to median out reading noise.
     */
    private const val TREND_WINDOW_FRACTION = 0.15

    /**
     * How far back (hours) we look at a candidate SG-rise cut to confirm
     * it's a *new* SG regime rather than a recovery from a dip. If the SG
     * was already at or above the rise's destination within this window,
     * the rise is treated as noise (e.g., a brief sensor excursion that
     * settled back to baseline) and the cut is rejected.
     */
    private const val RISE_LOOKBACK_HOURS = 6.0
    private const val RISE_LOOKBACK_TOLERANCE = 0.001

    fun detect(timestamps: LongArray, sgs: DoubleArray): List<FermentSegment> {
        require(timestamps.size == sgs.size)
        val n = timestamps.size
        if (n < MIN_POINTS_PER_SEGMENT) return emptyList()

        // Pass 1: collect cut indices. Each cut splits the array at index
        // `i`, meaning span [prevCut, i) ends and the next span starts at i.
        val cuts = mutableListOf(0)
        for (i in 1 until n) {
            val gapH = (timestamps[i] - timestamps[i - 1]) / 3_600_000.0
            if (gapH > GAP_THRESHOLD_HOURS) {
                // A gap alone isn't a boundary — only a gap that coincides
                // with an SG discontinuity is. Compare short medians on
                // either side (robust to a single noisy reading landing at
                // the boundary) and cut only when the SG regime actually
                // changed. A continuous resume (logging dropped mid cold-
                // conditioning, SG picks up where it left off) stays one
                // ferment.
                val preGap = medianOf(sgs, (i - 3).coerceAtLeast(0), i)
                val postGap = medianOf(sgs, i, (i + 3).coerceAtMost(n))
                if (kotlin.math.abs(postGap - preGap) > NEW_FERMENT_RISE) cuts += i
                continue
            }
            val rise = sgs[i] - sgs[i - 1]
            if (rise <= NEW_FERMENT_RISE) continue
            // Confirm the rise lands at a SG higher than the recent past
            // — otherwise it's just recovery from a dip (cleaning blip,
            // device lifted, transient temperature excursion).
            val lookbackStartMs = timestamps[i] - (RISE_LOOKBACK_HOURS * 3_600_000.0).toLong()
            var foundPriorPeak = false
            for (j in i - 1 downTo 0) {
                if (timestamps[j] < lookbackStartMs) break
                if (sgs[j] >= sgs[i] - RISE_LOOKBACK_TOLERANCE) {
                    foundPriorPeak = true
                    break
                }
            }
            if (!foundPriorPeak) cuts += i
        }
        cuts += n

        // Pass 2: qualify each span.
        val segments = mutableListOf<FermentSegment>()
        for (b in 0 until cuts.size - 1) {
            val lo = cuts[b]
            val hi = cuts[b + 1]
            if (hi - lo < MIN_POINTS_PER_SEGMENT) continue
            val durationH = (timestamps[hi - 1] - timestamps[lo]) / 3_600_000.0
            if (durationH < MIN_FERMENT_HOURS) continue

            // Reject the iSpindle float drop-in settling transient before
            // measuring the span. A fresh device bobs for a reading or two
            // emitting SG values tens of mSG off the true wort (an up-spike
            // and/or a down-spike), and raw max/min would otherwise read OG
            // off the spike *and* inflate `drop` — which in turn raises the
            // trend gate (drop/4) above the genuine decline and rejects a
            // real, freshly-started ferment. SeriesClean's MAD filter drops
            // those gross outliers (no-op on clean spans / keep-all when the
            // span is too short, so finished brews are unaffected). The
            // reported time extent below stays the raw span — only the
            // observed-SG qualification metrics use the cleaned subset.
            val spanHours = DoubleArray(hi - lo) { (timestamps[lo + it] - timestamps[lo]) / 3_600_000.0 }
            val spanSgs = DoubleArray(hi - lo) { sgs[lo + it] }
            val kept = SeriesClean.keptIndices(spanHours, spanSgs)
            val cleanSgs = DoubleArray(kept.size) { spanSgs[kept[it]] }

            var maxSg = cleanSgs[0]
            var minSg = cleanSgs[0]
            for (v in cleanSgs) {
                if (v > maxSg) maxSg = v
                if (v < minSg) minSg = v
            }
            val drop = maxSg - minSg
            if (drop < MIN_FERMENT_DROP) continue

            // Trend test: the head window median must be at least drop/4
            // above the tail window median. This rules out segments where
            // the observed drop is just an excursion (e.g., a cold-side
            // temperature spike that drove an apparent SG bump and then
            // recovered) rather than a sustained ferment. Head/tail
            // windows (not index halves) keep the comparison anchored to
            // the actual start and end of the span — a long flat
            // conditioning tail would otherwise push an index midpoint
            // deep into the plateau and collapse the two medians together.
            val windowLen = (cleanSgs.size * TREND_WINDOW_FRACTION).toInt().coerceAtLeast(1)
            val headMedian = medianOf(cleanSgs, 0, windowLen)
            val tailMedian = medianOf(cleanSgs, cleanSgs.size - windowLen, cleanSgs.size)
            if (headMedian - tailMedian < drop / 4.0) continue

            segments += FermentSegment(
                startMs = timestamps[lo],
                endMs = timestamps[hi - 1],
                ogObserved = maxSg,
                fgObserved = cleanSgs[cleanSgs.size - 1],
                pointCount = hi - lo
            )
        }
        return segments
    }

    /**
     * Which segment the UI should select by default.
     *
     * Normally the most recent qualified segment — that's the brew the
     * user is actively watching. But when a *newer* episode is logging
     * past the end of the last segment and hasn't yet met the
     * qualification floor (a brew in its first hours: too short, no
     * attenuation yet), returning that stale last segment would hide the
     * live brew. In that case return null so the caller falls back to its
     * time-window ("All") view, where the fresh readings are visible.
     *
     * "Newer episode" is judged by the same [GAP_THRESHOLD_HOURS] used for
     * boundary detection: readings more than that past the last segment's
     * end are a distinct, not-yet-segmented episode.
     */
    fun defaultSelection(segments: List<FermentSegment>, latestReadingMs: Long?): Int? {
        if (segments.isEmpty()) return null
        val last = segments.last()
        if (latestReadingMs != null &&
            latestReadingMs - last.endMs > (GAP_THRESHOLD_HOURS * 3_600_000L).toLong()
        ) {
            return null
        }
        return segments.lastIndex
    }

    private fun medianOf(arr: DoubleArray, fromIndex: Int, toIndex: Int): Double {
        val len = toIndex - fromIndex
        if (len <= 0) return Double.NaN
        val copy = DoubleArray(len) { arr[fromIndex + it] }
        copy.sort()
        return if (len % 2 == 0) (copy[len / 2 - 1] + copy[len / 2]) / 2.0
        else copy[len / 2]
    }
}
