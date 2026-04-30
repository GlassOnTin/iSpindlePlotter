package com.ispindle.plotter.analysis

/**
 * A region of the SG curve where the gravity is essentially flat — the
 * derivative is statistically zero across a sustained span.
 *
 * Three flavours show up in real ferments:
 *
 * - **Lag**: the start-of-ferment plateau, before the yeast spins up.
 *   Already classified at the [Fermentation.State] level (`State.Lag`),
 *   but reported here too so the chart can shade it.
 * - **Mid**: a *diauxic shift* — yeast finishes the easy sugars (glucose,
 *   maltose), pauses while it re-tunes its enzyme machinery, then resumes
 *   on the harder ones (maltotriose). Classic biphasic beer signature.
 *   A single-stage modified-Gompertz curve structurally cannot reproduce
 *   this: it has one inflection. We detect it for chart annotation; the
 *   model itself smooths through it.
 * - **Tail**: an end-of-ferment plateau. Either the asymptote (Complete)
 *   or a stall well above predicted FG (Stuck).
 */
data class Plateau(
    /** Hours from the first sample where the plateau begins. */
    val startH: Double,
    /** Hours from the first sample where the plateau ends. */
    val endH: Double,
    /** Mean SG across the plateau. */
    val sg: Double,
    val kind: Kind
) {
    val durationH: Double get() = endH - startH

    enum class Kind { Lag, Mid, Tail }
}

/**
 * Detector for sustained flat segments in an SG time series.
 *
 * Algorithm: walk a uniform 30-minute grid across the data, fit a linear
 * slope inside a centred 3-hour window at each grid point, and find
 * maximal contiguous runs where `|slope| < FLAT_RATE_THRESHOLD`. Runs
 * shorter than `MIN_PLATEAU_HOURS` are discarded. The window-based slope
 * (rather than per-bin point-to-point differences) is what makes the
 * detector robust to single-reading noise spikes.
 *
 * Threshold rationale: per-point iSpindle noise on the user's calibrated
 * device sits around 0.5 mSG. Over a 3-hour window with ~180 samples the
 * OLS slope's standard error is ~σ / (Δt · √(n/12)) ≈ 4 × 10⁻⁵ SG/h —
 * well below the 3 × 10⁻⁴ SG/h threshold here. Active descent in beer
 * fermentation is typically 1–2 mSG/h (≥ 10⁻³ SG/h), so the threshold
 * sits comfortably between "real flat" and "real active" with margin in
 * both directions.
 */
object PlateauDetector {

    private const val FLAT_RATE_THRESHOLD = 0.0003
    private const val MIN_PLATEAU_HOURS = 2.5
    private const val WINDOW_HOURS = 1.5
    private const val GRID_STEP_HOURS = 0.5

    fun detect(hours: DoubleArray, sgs: DoubleArray): List<Plateau> {
        require(hours.size == sgs.size)
        val n = hours.size
        if (n < 6) return emptyList()
        val firstH = hours.first()
        val lastH = hours.last()
        if (lastH - firstH < MIN_PLATEAU_HOURS) return emptyList()

        // Build a uniform grid and probe slope at each grid point. NaN
        // slope (window contains < 3 samples or all xs equal) breaks any
        // ongoing run — treat it the same as "not flat".
        val gridSize = ((lastH - firstH) / GRID_STEP_HOURS).toInt() + 1
        val grid = DoubleArray(gridSize) { firstH + it * GRID_STEP_HOURS }
        val slopes = DoubleArray(gridSize) { gi ->
            slopeInWindow(hours, sgs, grid[gi] - WINDOW_HOURS / 2, grid[gi] + WINDOW_HOURS / 2)
        }

        val plateaus = mutableListOf<Plateau>()
        var runStart = -1
        for (gi in 0..gridSize) {
            // "Flat" here means *not actively descending*. A rising slope
            // (e.g. iSpindle settling drift in the first hour, foam crash,
            // temperature spike) also counts — fermentation is by definition
            // monotonic-down, so any non-descent is part of a plateau.
            val flat = gi < gridSize && !slopes[gi].isNaN() &&
                slopes[gi] > -FLAT_RATE_THRESHOLD
            if (flat) {
                if (runStart < 0) runStart = gi
                continue
            }
            if (runStart >= 0) {
                // A flat detection at grid centre t reflects the slope
                // computed over [t - W/2, t + W/2]. So a run of flat grid
                // points {gi_first..gi_last} implies flatness across
                // [grid[gi_first] - W/2, grid[gi_last] + W/2], clamped to
                // the actual data span.
                val gridFirst = runStart
                val gridLast = gi - 1
                val s = (grid[gridFirst] - WINDOW_HOURS / 2).coerceAtLeast(firstH)
                val e = (grid[gridLast] + WINDOW_HOURS / 2).coerceAtMost(lastH)
                if (e - s >= MIN_PLATEAU_HOURS) {
                    val sg = meanSgInRange(hours, sgs, s, e)
                    if (!sg.isNaN()) {
                        val kind = when {
                            // Run touches the data start → Lag plateau.
                            s <= firstH + 1e-6 -> Plateau.Kind.Lag
                            // Run touches the data end → Tail plateau.
                            e >= lastH - 1e-6 -> Plateau.Kind.Tail
                            else -> Plateau.Kind.Mid
                        }
                        plateaus += Plateau(startH = s, endH = e, sg = sg, kind = kind)
                    }
                }
                runStart = -1
            }
        }
        return plateaus
    }

    private fun slopeInWindow(
        hours: DoubleArray,
        sgs: DoubleArray,
        lo: Double,
        hi: Double
    ): Double {
        val keep = ArrayList<Int>(64)
        for (i in hours.indices) if (hours[i] in lo..hi) keep += i
        if (keep.size < 3) return Double.NaN
        val xs = DoubleArray(keep.size) { hours[keep[it]] }
        val ys = DoubleArray(keep.size) { sgs[keep[it]] }
        return Fits.fitLinear(xs, ys)?.slope ?: Double.NaN
    }

    private fun meanSgInRange(
        hours: DoubleArray,
        sgs: DoubleArray,
        lo: Double,
        hi: Double
    ): Double {
        var sum = 0.0
        var count = 0
        for (i in hours.indices) {
            if (hours[i] in lo..hi) {
                sum += sgs[i]
                count++
            }
        }
        return if (count == 0) Double.NaN else sum / count
    }
}
