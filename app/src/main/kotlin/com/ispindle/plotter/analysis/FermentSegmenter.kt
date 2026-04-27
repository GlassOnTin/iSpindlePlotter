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
 *     - a time gap longer than [GAP_THRESHOLD_HOURS] (device offline),
 *     - a single-step SG rise greater than [NEW_FERMENT_RISE] (fresh
 *       wort poured in, or the device was lifted out and re-floated in
 *       a different liquid).
 *
 *  2. **Qualification.** Each candidate span is promoted to a
 *     [FermentSegment] only if all of these hold:
 *     - duration ≥ [MIN_FERMENT_HOURS],
 *     - max SG − min SG ≥ [MIN_FERMENT_DROP] (real attenuation, not
 *       calibration-in-water noise),
 *     - first-half median SG > second-half median SG by at least a
 *       quarter of the observed drop (downward trend, not random
 *       noise that happened to span [MIN_FERMENT_DROP]).
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
                cuts += i
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

            var maxSg = sgs[lo]
            var minSg = sgs[lo]
            for (i in lo until hi) {
                if (sgs[i] > maxSg) maxSg = sgs[i]
                if (sgs[i] < minSg) minSg = sgs[i]
            }
            val drop = maxSg - minSg
            if (drop < MIN_FERMENT_DROP) continue

            // Trend test: first-half median must be at least drop/4 above
            // the second-half median. This rules out segments where the
            // observed drop is just an excursion (e.g., a cold-side
            // temperature spike that drove an apparent SG bump and then
            // recovered) rather than a sustained ferment.
            val mid = (lo + hi) / 2
            val firstHalfMedian = medianOf(sgs, lo, mid)
            val secondHalfMedian = medianOf(sgs, mid, hi)
            if (firstHalfMedian - secondHalfMedian < drop / 4.0) continue

            segments += FermentSegment(
                startMs = timestamps[lo],
                endMs = timestamps[hi - 1],
                ogObserved = maxSg,
                fgObserved = sgs[hi - 1],
                pointCount = hi - lo
            )
        }
        return segments
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
