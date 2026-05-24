package com.ispindle.plotter.analysis

import kotlin.math.abs

/**
 * Rejects gross artefacts from a raw SG series before it feeds OG
 * estimation and the modified-Gompertz fit.
 *
 * Two independent filters, both aimed at the float drop-in transient — the
 * iSpindle is dropped into wort, bobs for a few readings while the angle
 * and the temperature sensor settle, and emits one or two SG values tens of
 * mSG away from the true reading. Because OG is read as `max(SG)` and the
 * fit is plain least-squares, a single such spike pins OG to the artefact
 * and drags the whole curve (observed: a 1.1066 spike on a true-1.077 wort
 * inflated OG by ~29 mSG and made the fit 10× worse).
 *
 *  1. **MAD spike rejection** (always on). Detrend with a short rolling
 *     median so the comparison follows the ferment curve, then reject any
 *     point whose residual exceeds [MAD_K] robust sigmas. Tuned to catch
 *     gross outliers only — normal ~1 mSG reading noise survives.
 *
 *  2. **Temperature-gated startup trim** (when temperatures are supplied).
 *     Drops the leading run of readings whose temperature is still far from
 *     the settled reference, i.e. the sensor hasn't equilibrated and the
 *     SG it implies is unreliable regardless of sign. Bounded so a slow,
 *     genuine warm-up can't eat real fermentation data.
 *
 * The filters are deliberately conservative: on a clean capture both are
 * no-ops, so existing behaviour is unchanged.
 */
object SeriesClean {

    /** Robust-sigma multiplier for spike rejection. */
    private const val MAD_K = 4.0
    /** Floor on robust sigma (~iSpindle SG resolution) so a near-noiseless
     *  plateau doesn't make the threshold collapse to zero. */
    private const val SIGMA_FLOOR = 0.0005
    /** Half-window (in points) for the detrending rolling median. */
    private const val MEDIAN_HALF_WINDOW = 3
    /** Below this many points, MAD rejection is skipped (too few to
     *  estimate a trend or a robust sigma). */
    private const val MIN_FOR_MAD = 8

    /** Hours from start ignored when picking the settled-temp reference. */
    private const val SETTLE_REF_START_H = 0.25
    /** Temperature deviation from the settled reference that still counts
     *  as "equilibrating". */
    private const val TEMP_SETTLE_TOL_C = 2.0
    /** Never trim leading readings beyond this many hours from the start. */
    private const val MAX_TRIM_H = 0.5
    /** …nor more than this fraction of the series. */
    private const val MAX_TRIM_FRACTION = 0.2

    /** Never let cleaning drop the survivor count below this. */
    private const val MIN_KEEP = 6

    /**
     * Indices (ascending) to keep after both filters. Falls back to "keep
     * everything" when the series is too short or cleaning would strip it
     * below [MIN_KEEP] — the fit's own guards take over from there.
     */
    fun keptIndices(
        hours: DoubleArray,
        sgs: DoubleArray,
        temps: DoubleArray? = null
    ): IntArray {
        require(hours.size == sgs.size)
        val n = hours.size
        val all = IntArray(n) { it }
        if (n < MIN_KEEP) return all

        val trimEnd = leadingTrimEnd(hours, temps, n)
        val spike = madOutliers(hours, sgs, n)

        val kept = ArrayList<Int>(n)
        for (i in trimEnd until n) if (!spike[i]) kept.add(i)
        return if (kept.size >= MIN_KEEP) kept.toIntArray() else all
    }

    /**
     * OG estimate robust to a startup spike: the maximum over the kept
     * (inlier) points. Falls back to the raw maximum if cleaning kept
     * nothing usable.
     */
    fun robustOg(
        hours: DoubleArray,
        sgs: DoubleArray,
        temps: DoubleArray? = null
    ): Double {
        val keep = keptIndices(hours, sgs, temps)
        var m = Double.NEGATIVE_INFINITY
        for (i in keep) if (sgs[i] > m) m = sgs[i]
        return if (m.isFinite()) m else sgs.max()
    }

    /** Boolean mask of MAD spike outliers (true = outlier). */
    private fun madOutliers(hours: DoubleArray, sgs: DoubleArray, n: Int): BooleanArray {
        val out = BooleanArray(n)
        if (n < MIN_FOR_MAD) return out
        // Residual from a short rolling median (index window, clamped).
        val resid = DoubleArray(n) { i ->
            val lo = (i - MEDIAN_HALF_WINDOW).coerceAtLeast(0)
            val hi = (i + MEDIAN_HALF_WINDOW).coerceAtMost(n - 1)
            val win = DoubleArray(hi - lo + 1) { sgs[lo + it] }
            sgs[i] - (Fits.median(win) ?: sgs[i])
        }
        val absResid = DoubleArray(n) { abs(resid[it]) }
        val mad = Fits.median(absResid) ?: 0.0
        val sigma = (1.4826 * mad).coerceAtLeast(SIGMA_FLOOR)
        val threshold = MAD_K * sigma
        for (i in 0 until n) if (absResid[i] > threshold) out[i] = true
        return out
    }

    /**
     * Index one past the last leading reading whose temperature is still
     * equilibrating. Returns 0 when temps are absent or the series already
     * starts settled.
     */
    private fun leadingTrimEnd(hours: DoubleArray, temps: DoubleArray?, n: Int): Int {
        if (temps == null || temps.size != n) return 0
        val t0 = hours[0]
        // Settled reference: median temperature over readings past the
        // first SETTLE_REF_START_H (the equilibration window). Fall back to
        // the overall median if too few late readings exist.
        val refVals = ArrayList<Double>(n)
        for (i in 0 until n) if (hours[i] - t0 >= SETTLE_REF_START_H) refVals.add(temps[i])
        val settled = (if (refVals.size >= 3) Fits.median(refVals.toDoubleArray())
            else Fits.median(temps.copyOf())) ?: return 0

        val maxByFraction = (n * MAX_TRIM_FRACTION).toInt()
        var end = 0
        while (end < n) {
            if (abs(temps[end] - settled) <= TEMP_SETTLE_TOL_C) break
            if (hours[end] - t0 > MAX_TRIM_H) break
            if (end >= maxByFraction) break
            end++
        }
        return end
    }
}
