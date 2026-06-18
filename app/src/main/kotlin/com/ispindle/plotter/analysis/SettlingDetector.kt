package com.ispindle.plotter.analysis

import kotlin.math.max

/**
 * A late, constant-temperature SG step-down attributed to yeast settling
 * (clarification), as distinct from continued fermentation or a cold crash.
 *
 * A floating hydrometer reads the bulk density of its surrounding
 * suspension: `ρ_bulk = ρ_beer + φ·(ρ_yeast − ρ_beer)`, with the yeast
 * volume fraction φ inflating the reading. When the yeast flocculates and
 * drops out of the upper layer the float sits in, the apparent SG steps
 * down to the clarified value — a few mSG, at constant temperature, after
 * fermentation is essentially complete. (Order-of-magnitude: φ ~ 0.5–2 %
 * and ρ_yeast − ρ_beer ~ 0.08 give ΔSG ~ 0.0007–0.0024.)
 */
data class SettlingEvent(
    /** Hour the SG began leaving the fermentation-FG plateau. */
    val startH: Double,
    /** End of the warm window (cold-crash onset, or the last sample). */
    val endH: Double,
    /** SG with yeast still in suspension (the pre-settle plateau). */
    val fgWithYeast: Double,
    /** Settled SG once the yeast dropped out. */
    val clarifiedSg: Double,
    /** fgWithYeast − clarifiedSg. */
    val dropSg: Double,
)

/**
 * Detects the yeast-settling / clarification step in a finished ferment.
 *
 * The step is too gentle (≈ 0.1 mSG/h) for [PlateauDetector]'s flat-slope
 * test — it merges the pre- and post-settle plateaus into one. So this works
 * on the **cumulative level**: split the terminal warm flat region into
 * thirds and compare the first-third median (yeast in suspension) against the
 * last-third median (clarified). The constant-temperature gate separates it
 * from a cold crash; the magnitude gates separate it from noise (floor) and
 * from a diauxic resume (ceiling).
 */
object SettlingDetector {
    /** Slope steeper than this means fermentation is still actively dropping. */
    private const val ACTIVE_RATE = -0.0005
    /** Ferment must be essentially done at the upper plateau. Matches the
     *  `asymptoteReached` bar in [Fermentation.pickFgEstimate]. */
    private const val MIN_ATTENUATION = 0.70
    /** Region must be long enough for each third to clear the 2.5 h
     *  [PlateauDetector] minimum. */
    private const val MIN_REGION_HOURS = 8.0
    /** Constant temperature: a cold crash is ≥ 4 °C, so 1.5 °C separates them
     *  cleanly while tolerating diurnal swing. */
    private const val MAX_TEMP_SPREAD_C = 1.5
    /** Floor: above iSpindle reading noise (the 1.5 mSG krausen margin). */
    private const val MIN_DROP = 0.0015
    /** Ceiling: a diauxic resume drops far past this. */
    private const val MAX_DROP = 0.006
    private const val WINDOW_HOURS = 6.0
    private const val SMOOTH_HALF_HOURS = 0.5

    fun detect(
        hours: DoubleArray,
        sgs: DoubleArray,
        temps: DoubleArray?,
        og: Double,
        coldCrashOnsetH: Double?,
    ): SettlingEvent? {
        if (temps == null || temps.size != hours.size) return null
        val n = hours.size
        if (n < 9) return null

        // 1. Warm window: everything strictly before any cold-crash onset.
        val warmEndH = coldCrashOnsetH?.minus(0.01) ?: hours.last()
        val warmEndIdx = hours.indexOfLast { it <= warmEndH }
        if (warmEndIdx < 8) return null

        // 2. Region start: walk back over the post-fermentation tail (gentle
        //    rate) and stop where active fermentation was still happening.
        var startIdx = warmEndIdx
        var i = warmEndIdx
        while (i > 0) {
            val slope = localSlope(hours, sgs, i) ?: break
            if (slope <= ACTIVE_RATE) break   // hit active descent
            startIdx = i
            i--
        }
        if (hours[warmEndIdx] - hours[startIdx] < MIN_REGION_HOURS) return null
        val count = warmEndIdx - startIdx + 1
        if (count < 9) return null

        // 3. Constant temperature across the region (10th–90th pct spread,
        //    so a single noisy reading doesn't trip the gate).
        val regionTemps = DoubleArray(count) { temps[startIdx + it] }.also { it.sort() }
        if (pct(regionTemps, 0.90) - pct(regionTemps, 0.10) >= MAX_TEMP_SPREAD_C) return null

        // 4. Cumulative level test: first-third vs last-third median SG.
        val third = count / 3
        if (third < 3) return null
        val lastStart = warmEndIdx - third + 1
        val fgWithYeast = medianRange(sgs, startIdx, startIdx + third - 1) ?: return null
        val clarifiedSg = medianRange(sgs, lastStart, warmEndIdx) ?: return null
        val dropSg = fgWithYeast - clarifiedSg
        if (dropSg < MIN_DROP || dropSg > MAX_DROP) return null

        // 5. Ferment essentially done at the upper plateau.
        if ((og - fgWithYeast) / max(og - 1.0, 1e-6) < MIN_ATTENUATION) return null

        // 6. A settle is a step *between two plateaus*: the ferment must have
        //    reached a stable FG (first third flat) and then re-flattened at
        //    the clarified level (last third flat). A continuous gentle
        //    decline through the warm tail is slow attenuation, not settling,
        //    and is rejected — attributing it to yeast dropping would be
        //    unfounded without the pre-settle plateau.
        if (internalDrop(sgs, startIdx, startIdx + third - 1) > MIN_DROP) return null
        if (internalDrop(sgs, lastStart, warmEndIdx) > MIN_DROP) return null

        // 7. Pin the step start: first (smoothed) sample that has dropped a
        //    noise-margin below the fermentation-FG plateau.
        val leaveLevel = fgWithYeast - MIN_DROP / 2.0
        var stepStartIdx = startIdx
        for (j in startIdx..warmEndIdx) {
            if (smoothed(hours, sgs, j, startIdx, warmEndIdx) <= leaveLevel) {
                stepStartIdx = j
                break
            }
        }

        return SettlingEvent(
            startH = hours[stepStartIdx],
            endH = hours[warmEndIdx],
            fgWithYeast = fgWithYeast,
            clarifiedSg = clarifiedSg,
            dropSg = dropSg,
        )
    }

    /** OLS slope over the [WINDOW_HOURS] window ending at [endIdx]. */
    private fun localSlope(hours: DoubleArray, sgs: DoubleArray, endIdx: Int): Double? {
        val cutoff = hours[endIdx] - WINDOW_HOURS
        var ws = endIdx
        while (ws > 0 && hours[ws - 1] >= cutoff) ws--
        val len = endIdx - ws + 1
        if (len < 3) return null
        return Fits.fitLinear(
            DoubleArray(len) { hours[ws + it] },
            DoubleArray(len) { sgs[ws + it] }
        )?.slope
    }

    /** Median SG over a ±[SMOOTH_HALF_HOURS] window around [idx], clipped to
     *  [lo, hi]. Rejects single-sample spikes when locating the step start. */
    private fun smoothed(hours: DoubleArray, sgs: DoubleArray, idx: Int, lo: Int, hi: Int): Double {
        var a = idx
        while (a > lo && hours[idx] - hours[a - 1] <= SMOOTH_HALF_HOURS) a--
        var b = idx
        while (b < hi && hours[b + 1] - hours[idx] <= SMOOTH_HALF_HOURS) b++
        return medianRange(sgs, a, b) ?: sgs[idx]
    }

    private fun medianRange(sgs: DoubleArray, lo: Int, hi: Int): Double? {
        if (hi < lo) return null
        return Fits.median(DoubleArray(hi - lo + 1) { sgs[lo + it] })
    }

    /** Drop across [lo, hi]: early-half median − late-half median. ~0 on a
     *  flat plateau; large on a sustained decline. */
    private fun internalDrop(sgs: DoubleArray, lo: Int, hi: Int): Double {
        val mid = (lo + hi) / 2
        val early = medianRange(sgs, lo, mid) ?: return 0.0
        val late = medianRange(sgs, mid + 1, hi) ?: return 0.0
        return early - late
    }

    /** [p]-quantile of an already-sorted array. */
    private fun pct(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
