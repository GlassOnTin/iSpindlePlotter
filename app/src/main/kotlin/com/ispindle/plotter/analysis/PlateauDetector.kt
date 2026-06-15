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

    /**
     * Minimum fraction of the slope window that the kept samples must span.
     * A window with only a small bunched cluster of post-gap readings can
     * give a near-zero local slope that has nothing to say about the empty
     * side of the window — extrapolating that as "flat across the whole
     * window" fabricates a plateau in the logging-gap region. Requiring the
     * kept span to cover at least this fraction of the window keeps those
     * gap-straddling phantoms out without rejecting normal sparse data.
     */
    private const val MIN_WINDOW_COVERAGE = 0.5

    /**
     * Post-plateau context (hours) used to test whether a Mid plateau is
     * followed by genuine active descent — a real diauxic shift resumes
     * with rates well above the flat threshold, a slowing-tail flat patch
     * does not. Six hours is wide enough to span a typical resumption
     * while staying local enough that the *next* pause doesn't dominate.
     */
    private const val RESUME_CHECK_HOURS = 6.0

    /**
     * Descent rate the post-plateau context must exceed for the plateau
     * to count as a real pause (more negative than this value — units are
     * SG/h, so 0.0006 SG/h = 0.6 mSG/h, twice the flat-detection floor).
     */
    private const val RESUMED_DESCENT_THRESHOLD = 2.0 * FLAT_RATE_THRESHOLD

    /**
     * How far before a Mid plateau's end a cold-crash onset may sit and still
     * be treated as "the crash that masked this pause's resume". Small slack
     * so a flat run that overlaps the first gentle hours of the crash still
     * qualifies, without letting the crash explain a pause it precedes by much.
     */
    private const val COLD_CRASH_OVERLAP_H = 2.0

    fun detect(
        hours: DoubleArray,
        sgs: DoubleArray,
        coldCrashOnsetH: Double? = null
    ): List<Plateau> {
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
                val sFromGrid = (grid[gridFirst] - WINDOW_HOURS / 2).coerceAtLeast(firstH)
                val eFromGrid = (grid[gridLast] + WINDOW_HOURS / 2).coerceAtMost(lastH)
                // Qualify against the grid extent — that's what the flat
                // run's slope evidence actually covers. Clipping the visible
                // span to readings (below) is for the drawn band only and
                // shouldn't push a real plateau under MIN_PLATEAU_HOURS just
                // because the run happened to start mid-sample-interval.
                if (eFromGrid - sFromGrid >= MIN_PLATEAU_HOURS) {
                    // Clip the reported extent inward to where readings
                    // actually exist, so the plateau band doesn't leak into
                    // a logging gap that the W/2 grid extension swept over.
                    // The Kind classification stays anchored to the grid
                    // extent so a run that touches the data boundary keeps
                    // its Lag/Tail label even after the clip pulls it in.
                    val s = firstReadingAtOrAfter(hours, sFromGrid)
                    val e = lastReadingAtOrBefore(hours, eFromGrid)
                    if (s != null && e != null && e > s) {
                        val sg = meanSgInRange(hours, sgs, s, e)
                        if (!sg.isNaN()) {
                            val kind = when {
                                // Run touches the data start → Lag plateau.
                                sFromGrid <= firstH + 1e-6 -> Plateau.Kind.Lag
                                // Run touches the data end → Tail plateau.
                                eFromGrid >= lastH - 1e-6 -> Plateau.Kind.Tail
                                else -> Plateau.Kind.Mid
                            }
                            plateaus += Plateau(startH = s, endH = e, sg = sg, kind = kind)
                        }
                    }
                }
                runStart = -1
            }
        }
        return mergeOverlapping(plateaus, hours, sgs, coldCrashOnsetH)
            .filter { p ->
                // Only Mid plateaus need the resume gate. Lag and Tail are
                // boundary-anchored regions where "did descent resume?"
                // doesn't apply.
                p.kind != Plateau.Kind.Mid || isFollowedByActiveDescent(hours, sgs, p.endH, coldCrashOnsetH)
            }
    }

    /**
     * Whether descent of at least [RESUMED_DESCENT_THRESHOLD] resumes
     * within [RESUME_CHECK_HOURS] after the plateau ends. Distinguishes a
     * real mid-active pause — a diauxic shift, where the yeast resumes
     * a fresh attenuation run — from a slowing-tail flat sub-region of
     * an otherwise continuous deceleration toward FG, which would
     * otherwise mint a pause stripe every few hours of the long tail.
     *
     * Live-edge plateaus (the post-context extends past the last reading)
     * pass through: we can't yet see if the brew will resume, but the
     * current-pause headline guidance still needs to fire while the user
     * is watching the ferment hold flat. The gap-coverage rule inside
     * [slopeInWindow] still rejects post-windows dominated by a logging
     * outage, so a plateau ending right at a long gap won't sneak through.
     */
    private fun isFollowedByActiveDescent(
        hours: DoubleArray,
        sgs: DoubleArray,
        plateauEndH: Double,
        coldCrashOnsetH: Double?
    ): Boolean {
        val lastH = hours.last()
        if (lastH - plateauEndH < RESUME_CHECK_HOURS) return true
        // A cold crash beginning right around the pause's end masks the resume:
        // the gentle post-pause slope is the crash pulling apparent density up,
        // not a slowing tail. The crash is itself evidence the ferment ran past
        // the pause — you don't cold-crash mid-tail — so accept it as a real
        // diauxic Mid rather than demanding a vigorous (warm) resumption that
        // the crash has pre-empted. The onset must sit near the pause END (a
        // little before, to allow the flat run to overlap the early crash, up
        // to RESUME_CHECK_HOURS after); otherwise every flat patch in the
        // post-crash cold-conditioning tail would qualify.
        if (coldCrashOnsetH != null &&
            coldCrashOnsetH >= plateauEndH - COLD_CRASH_OVERLAP_H &&
            coldCrashOnsetH <= plateauEndH + RESUME_CHECK_HOURS
        ) return true
        val slope = slopeInWindow(hours, sgs, plateauEndH, plateauEndH + RESUME_CHECK_HOURS)
        return !slope.isNaN() && slope < -RESUMED_DESCENT_THRESHOLD
    }

    /**
     * Coalesce same-kind plateaus that overlap or sit closer than
     * `WINDOW_HOURS` apart. The window-based detector emits two adjacent
     * plateaus whenever a single grid point dips just past the slope
     * threshold (a noise blip in an otherwise flat patch); their reported
     * extents already overlap by up to `WINDOW_HOURS / 2` on each side
     * because of the W/2 boundary extension. Merging restores them to the
     * single flat region they actually represent and stops the chart from
     * drawing two abutting stripes of the same colour.
     *
     * Different kinds are never merged: a Mid touching a Tail stays two
     * regions so the labels remain meaningful.
     */
    private fun mergeOverlapping(
        plateaus: List<Plateau>,
        hours: DoubleArray,
        sgs: DoubleArray,
        coldCrashOnsetH: Double? = null
    ): List<Plateau> {
        if (plateaus.size < 2) return plateaus
        val sorted = plateaus.sortedBy { it.startH }
        val merged = mutableListOf<Plateau>()
        var cur = sorted.first()
        for (next in sorted.drop(1)) {
            // Never let a pre-crash plateau absorb a post-crash one: a real
            // diauxic pause that runs into the cold crash would otherwise be
            // merged into the long flat cold-conditioning tail and lose the
            // crash-adjacent end the resume gate needs to keep it.
            val bridgesCrash = coldCrashOnsetH != null &&
                cur.endH <= coldCrashOnsetH && next.endH > coldCrashOnsetH
            if (!bridgesCrash && next.kind == cur.kind && next.startH - cur.endH < WINDOW_HOURS) {
                val newEnd = maxOf(cur.endH, next.endH)
                cur = cur.copy(
                    endH = newEnd,
                    sg = meanSgInRange(hours, sgs, cur.startH, newEnd)
                )
            } else {
                merged += cur
                cur = next
            }
        }
        merged += cur
        return merged
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
        // Refuse a slope when the kept samples cluster in a small fraction
        // of the window — that's the signature of a logging gap dominating
        // the rest of the window. The kept span is monotonic (hours is
        // sorted), so last-minus-first is the actual covered extent.
        val coveredSpan = xs[xs.size - 1] - xs[0]
        if (coveredSpan < MIN_WINDOW_COVERAGE * (hi - lo)) return Double.NaN
        val ys = DoubleArray(keep.size) { sgs[keep[it]] }
        return Fits.fitLinear(xs, ys)?.slope ?: Double.NaN
    }

    /**
     * Hour of the first reading at or after [from], or `null` when none
     * exists. Used to clip a plateau's reported start to actual data —
     * the grid-based start can sit `WINDOW_HOURS / 2` before the first
     * post-gap reading, which would draw the plateau band over an empty
     * logging stretch.
     */
    private fun firstReadingAtOrAfter(hours: DoubleArray, from: Double): Double? {
        for (h in hours) if (h >= from) return h
        return null
    }

    /** Hour of the last reading at or before [until], or `null` when none. */
    private fun lastReadingAtOrBefore(hours: DoubleArray, until: Double): Double? {
        for (i in hours.indices.reversed()) if (hours[i] <= until) return hours[i]
        return null
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
