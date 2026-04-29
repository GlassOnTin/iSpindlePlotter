package com.ispindle.plotter.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Phase classifier and predictive model for an in-flight fermentation.
 *
 * Combines two layers of evidence:
 *   - **Recent rate** from a 6-hour linear fit on the tail of the data.
 *     Robust to noise and works the moment we have any decline.
 *   - **Modified-Gompertz fit** ([AttenuationFit]) over the whole
 *     window (when there's enough signal), giving OG, FG, peak rate,
 *     lag time, and a long-range ETA.
 *
 * The state always reports observed OG (max SG seen) so the user has a
 * concrete reference even before the parametric fit converges.
 */
object Fermentation {

    // Heuristic thresholds (per hour, in SG units).
    private const val ACTIVE_RATE = -0.0005   // dSG/dt < this → actively dropping
    private const val SLOWING_RATE = -0.00008 // between this and ACTIVE → slowing
    private const val LAG_DROP = 0.003        // OG - current < this AND no decline → lag
    private const val MIN_HOURS = 1.0
    private const val MIN_POINTS = 6
    private const val RECENT_WINDOW_HOURS = 6.0
    private const val STUCK_DROP_THRESHOLD = 0.005 // need to have actually fermented...
    private const val STUCK_FG_GAP = 0.005         // ...and still be this far from FG
    private const val COMPLETE_FG_GAP = 0.0015     // within this of asymptote → done

    sealed class State {
        data object Insufficient : State()

        data class Lag(
            val og: Double,
            val current: Double,
            val durationHours: Double
        ) : State()

        data class Active(
            val og: Double,
            val current: Double,
            val ratePerHour: Double,
            val predictedFg: Double,
            val etaToFinishHours: Double?,
            val source: PredictionSource,
            val predictedFgSigma: Double? = null,
            val measurementSigma: Double? = null,
            /** 2.5 % posterior quantile on time-to-target. Logistic source only. */
            val etaCredibleLowHours: Double? = null,
            /** 97.5 % posterior quantile on time-to-target. Logistic source only. */
            val etaCredibleHighHours: Double? = null,
            /**
             * Detected flat segments (lag, mid-ferment diauxic shift, or
             * tail). The Gompertz model can't reproduce a mid plateau;
             * surfacing the detection lets the chart shade it and the
             * estimate text annotate "stalled at X for Y h".
             */
            val plateaus: List<Plateau> = emptyList(),
            /**
             * The pause this state's time T is currently *inside*, clipped
             * so durationH ends at T (ongoing-so-far). Null when T is not
             * within any non-lag plateau. Drives the brewing guidance to
             * pivot from generic active-phase advice ("hold temperature")
             * to pause-specific advice ("could be a diauxic shift; if SG
             * hasn't resumed in 24–48 h, raise temperature or rouse").
             */
            val currentPause: Plateau? = null
        ) : State()

        data class Slowing(
            val og: Double,
            val current: Double,
            val ratePerHour: Double,
            val predictedFg: Double,
            val etaToFinishHours: Double?,
            val source: PredictionSource,
            val predictedFgSigma: Double? = null,
            val measurementSigma: Double? = null,
            /** 2.5 % posterior quantile on time-to-target. Logistic source only. */
            val etaCredibleLowHours: Double? = null,
            /** 97.5 % posterior quantile on time-to-target. Logistic source only. */
            val etaCredibleHighHours: Double? = null,
            /** See [Active.plateaus]. */
            val plateaus: List<Plateau> = emptyList(),
            /** See [Active.currentPause]. */
            val currentPause: Plateau? = null
        ) : State()

        /**
         * SG has settled at or near the prior FG and the smoothed rate has
         * fallen below the iSpindle's resolution floor (~0.1 mSG/h).
         *
         * Deliberately *not* called "Complete": at this stage the SG signal
         * has reached measurement-noise territory, but the ferment is
         * usually still biologically active — yeast cleaning up diacetyl
         * and acetaldehyde, finishing maltotriose (sub-resolution SG drop),
         * and CO2 desorbing from supersaturated wort. The label nudges the
         * brewer toward the right next steps (diacetyl rest, stable-reading
         * verification) rather than implying "ready to package".
         */
        data class Conditioning(
            val og: Double,
            val fg: Double,
            /** See [Active.plateaus]. */
            val plateaus: List<Plateau> = emptyList()
        ) : State()

        data class Stuck(
            val og: Double,
            val current: Double,
            val expectedFg: Double,
            val flatHours: Double
        ) : State()

        /**
         * Brewer has dropped vessel temperature substantially, typically
         * to flocculate yeast and clarify the beer. The iSpindle reads a
         * lower SG simply because the wort is denser when cold (~0.0001
         * SG/°C of cooling) and CO2 dissolves more under any pressure
         * applied — that drop is a thermal/density artifact, not further
         * fermentation.
         *
         * Distinguished from [Conditioning] by a sustained temperature
         * drop. Surfaced separately so the brewer doesn't read the
         * apparent SG as a real attenuation gain.
         */
        data class ColdCrash(
            val og: Double,
            /** Reading-as-the-iSpindle-sees-it right now (cold-affected). */
            val apparentSg: Double,
            /** SG observed just before the crash started — the actual FG. */
            val fermentSg: Double,
            /** Current temperature. */
            val temperatureC: Double,
            /** Pre-crash reference temperature. */
            val fermentTemperatureC: Double,
            /** Hours since the crash began. */
            val durationH: Double
        ) : State()
    }

    enum class PredictionSource {
        /** [AttenuationFit] modified-Gompertz fit. The trusted source. */
        Gompertz,
        ExpDecay,
        Linear,   // last-resort: "current rate × remaining drop"
        Default   // 75 % attenuation prior, no usable rate signal
    }

    /**
     * Classify and predict.
     *
     * @param hours times in hours from the first sample, ascending.
     * @param sgs SG readings, same indexing.
     * @param calRSquared optional R² of the device's calibration polynomial,
     *        used to set a floor on measurement σ.
     */
    fun analyse(
        hours: DoubleArray,
        sgs: DoubleArray,
        calRSquared: Double? = null
    ): State {
        require(hours.size == sgs.size)
        val n = hours.size
        if (n < MIN_POINTS) return State.Insufficient
        if (hours.last() - hours.first() < MIN_HOURS) return State.Insufficient

        val og = sgs.max()
        val current = sgs.last()
        val drop = og - current
        val durationH = hours.last() - hours.first()

        // Per-point measurement σ: data noise from short-window detrending,
        // floored by the calibration polynomial's residual sigma. Used both
        // for outlier rejection and for FG uncertainty propagation below.
        val sigmaData = NoiseModel.estimateDataNoise(hours, sgs)
        val sigmaCal = calRSquared?.let { NoiseModel.fromCalibrationRSquared(it) }
        val sigma = NoiseModel.combine(sigmaData, sigmaCal)

        // 1. Recent-rate fit on the last 6 hours of data, with 3σ outlier
        //    rejection. The first pass sets the local trend; points whose
        //    residual exceeds 3σ from that trend are excluded from the
        //    second pass. Important when a noisy reading lands during the
        //    short tail window and would otherwise dominate the slope.
        val tailStart = hours.last() - RECENT_WINDOW_HOURS
        val tailIdx = hours.indices.filter { hours[it] >= tailStart }
        val tailHours = DoubleArray(tailIdx.size) { hours[tailIdx[it]] }
        val tailSgs = DoubleArray(tailIdx.size) { sgs[tailIdx[it]] }
        val tailFitInitial = if (tailIdx.size >= 3) Fits.fitLinear(tailHours, tailSgs) else null
        val tailFit = if (tailFitInitial != null && tailIdx.size >= 5) {
            val keep = tailHours.indices.filter { i ->
                kotlin.math.abs(tailSgs[i] - tailFitInitial.predict(tailHours[i])) <= 3.0 * sigma
            }
            if (keep.size >= 3 && keep.size < tailHours.size) {
                Fits.fitLinear(
                    DoubleArray(keep.size) { tailHours[keep[it]] },
                    DoubleArray(keep.size) { tailSgs[keep[it]] }
                ) ?: tailFitInitial
            } else tailFitInitial
        } else tailFitInitial
        val recentRate = tailFit?.slope

        // 2. Lag — flat early data, no decline yet.
        if (drop < LAG_DROP &&
            (recentRate == null || recentRate > SLOWING_RATE) &&
            durationH > 1.0
        ) {
            return State.Lag(og = og, current = current, durationHours = durationH)
        }

        // 3. Try the full-window modified-Gompertz fit. May be unreliable
        //    while we're still inside the active phase, but worth a shot —
        //    the bounded LM is conservative. Pass measurement σ so the
        //    result carries an FG uncertainty.
        val gompertz = AttenuationFit.fit(hours, sgs, sigma)

        // 3b. Detect sustained flat segments (lag / diauxic shift / tail).
        //     Independent of the parametric fit — the model can't reproduce
        //     a mid plateau, but the chart and the estimate text can.
        val plateaus = PlateauDetector.detect(hours, sgs)

        // 4. Predicted FG with graceful degradation.
        val (predictedFg, source) = pickFgEstimate(
            og = og,
            gompertz = gompertz,
            current = current,
            recentRate = recentRate
        )

        // The 75 %-attenuation prior — independent of data, used as the
        // sanity check for "is the current plateau where it ought to be?".
        // A data plateau much higher than this is the signature of a
        // stuck ferment, not a finished one.
        val priorFg = max(0.998, 1.000 + 0.25 * (og - 1.000))

        // Robust gate stats: Theil-Sen slope and a windowed median over the
        // 6-hour tail. Both are insensitive to a single outlying reading
        // wandering in or out of the window as the analysis range grows
        // sample by sample, which avoids the Conditioning state flickering
        // on and off when scrubbing the cursor across a noisy slowing
        // region. The OLS [recentRate] is still the displayed rate (so the
        // user sees the same number they always did) — robust stats only
        // drive the binary rate-near-zero / current-SG gates.
        val robustRate = if (tailIdx.size >= 3) Fits.theilSenSlope(tailHours, tailSgs)
            else recentRate
        val medianTailSg = if (tailIdx.isNotEmpty()) Fits.median(tailSgs) ?: current
            else current
        val rateNearZero = robustRate != null && abs(robustRate) < 0.0001
        val flatTail = flatTailDuration(hours, sgs)

        // 5. Complete? Data plateau AND that plateau is at-or-below the
        //    prior FG (i.e. we've plausibly attenuated as expected).
        if (rateNearZero &&
            drop > STUCK_DROP_THRESHOLD &&
            medianTailSg <= priorFg + 0.003
        ) {
            return State.Conditioning(og = og, fg = medianTailSg, plateaus = plateaus)
        }

        // 6. Stuck? Data plateau but well above the prior FG — fermentation
        //    has stalled with sugar still in the wort.
        if (rateNearZero &&
            drop > STUCK_DROP_THRESHOLD &&
            medianTailSg > priorFg + STUCK_FG_GAP &&
            flatTail >= 6.0
        ) {
            return State.Stuck(
                og = og, current = current,
                expectedFg = priorFg, flatHours = flatTail
            )
        }

        // 7. ETA via the chosen model.
        val now = hours.last()
        val terminalSg = predictedFg + 0.001
        val eta = when (source) {
            PredictionSource.Gompertz -> gompertz?.timeToReach(terminalSg)?.minus(now)
            else -> linearEta(current, terminalSg, recentRate)
        }?.takeIf { it.isFinite() && it >= 0.0 }

        // 7b. ETA credible interval — propagated from the Laplace
        //     posterior over the 4 Gompertz params. Only meaningful when
        //     source == Gompertz; nulls otherwise.
        val etaCredible: Pair<Double?, Double?> = if (source == PredictionSource.Gompertz && gompertz != null) {
            val rng = Random(BAYESIAN_SEED)
            val q = gompertz.etaQuantiles(terminalSg, rng)
            if (q != null && q.size >= 3) {
                val low = (q[0] - now).takeIf { it.isFinite() && it >= 0.0 }
                val high = (q[2] - now).takeIf { it.isFinite() && it >= 0.0 }
                low to high
            } else null to null
        } else null to null

        // 8. FG uncertainty: from the LM covariance when we trust the
        //    parametric fit, or from the prior's spread (≈±0.003 SG, the
        //    std of typical attenuation outcomes 60–90 % across mainstream
        //    beer styles) when we're falling back to the prior.
        val priorFgSigma = 0.06 * (og - 1.000).coerceAtLeast(0.005)
        val predictedFgSigma = when (source) {
            PredictionSource.Gompertz -> gompertz?.fgSigma
            else -> priorFgSigma
        }

        // 9. Active vs Slowing branch on rate magnitude.
        val rate = recentRate ?: 0.0
        return if (rate < ACTIVE_RATE) {
            State.Active(
                og = og, current = current, ratePerHour = rate,
                predictedFg = predictedFg, etaToFinishHours = eta, source = source,
                predictedFgSigma = predictedFgSigma, measurementSigma = sigma,
                etaCredibleLowHours = etaCredible.first,
                etaCredibleHighHours = etaCredible.second,
                plateaus = plateaus
            )
        } else {
            State.Slowing(
                og = og, current = current, ratePerHour = rate,
                predictedFg = predictedFg, etaToFinishHours = eta, source = source,
                predictedFgSigma = predictedFgSigma, measurementSigma = sigma,
                etaCredibleLowHours = etaCredible.first,
                etaCredibleHighHours = etaCredible.second,
                plateaus = plateaus
            )
        }
    }

    /**
     * Fixed seed for the Bayesian Monte Carlo passes so the displayed
     * ETA quantiles don't flicker between recompositions for the same
     * dataset. Re-running on different data produces different draws
     * (the Hessian and MAP move), but a stable input → stable output.
     */
    private const val BAYESIAN_SEED: Int = 0x5E1ED

    private fun pickFgEstimate(
        og: Double,
        gompertz: AttenuationFit.Result?,
        current: Double,
        recentRate: Double?
    ): Pair<Double, PredictionSource> {
        // Trust the Gompertz fit only when:
        //  * its OG matches what we actually observed,
        //  * its FG is mechanically plausible (above the 90 % attenuation
        //    floor and below OG),
        //  * its FG isn't just hugging the data's tail — when current
        //    minus FG is < ~0.002 the model has identified "where data
        //    happened to end", not a real asymptote, and
        //  * the implied attenuation is at least 50 %, which excludes the
        //    "still in lag, fit thinks lag is the asymptote" failure mode.
        if (gompertz != null) {
            val ogClose = abs(gompertz.og - og) < 0.005
            val fgPlausible = gompertz.fg in 0.985..(og - 0.001)
            // The "not hugging data" gate exists to reject mid-active fits
            // where the LM has just identified "where data happened to end"
            // rather than a real asymptote. But once observed attenuation is
            // ≥ 70 % the data has plausibly reached its asymptote, and FG ≈
            // current is the correct answer, not a fit failure.
            val observedAtten = (og - current) / max(og - 1.000, 1e-6)
            val asymptoteReached = observedAtten >= 0.70
            val notHuggingData = asymptoteReached || (current - gompertz.fg > 0.002)
            val attenuation = (og - gompertz.fg) / max(og - 1.000, 1e-6)
            val attenuationPlausible = attenuation >= 0.50
            if (ogClose && fgPlausible && notHuggingData && attenuationPlausible) {
                return gompertz.fg to PredictionSource.Gompertz
            }
        }
        // Fallback: 75 % attenuation prior. Source distinguishes whether
        // we have an active rate to back the linear ETA or are guessing.
        val attenuationPrior = max(0.998, 1.000 + 0.25 * (og - 1.000))
        val source = if (recentRate != null && recentRate < ACTIVE_RATE)
            PredictionSource.Linear else PredictionSource.Default
        return attenuationPrior to source
    }

    private fun linearEta(current: Double, target: Double, rate: Double?): Double? {
        if (rate == null || rate >= 0.0) return null
        val remaining = current - target
        if (remaining <= 0.0) return 0.0
        return remaining / -rate
    }

    /**
     * Estimates how long the most-recent flat segment has been flat, by
     * walking backwards from the last point until the SG has changed by
     * more than 0.0005.
     */
    private fun flatTailDuration(hours: DoubleArray, sgs: DoubleArray): Double {
        if (hours.size < 2) return 0.0
        val refSg = sgs.last()
        val refT = hours.last()
        for (i in hours.indices.reversed()) {
            if (abs(sgs[i] - refSg) > 0.0005) {
                return refT - hours[i]
            }
        }
        return refT - hours.first()
    }

    // ── Timeline / state machine ────────────────────────────────────────────
    //
    // The classifier above answers "what state is the ferment in *now*?".
    // The state machine below answers "what state was it in at any point in
    // the dataset?" — so the chart-cursor scrubbing UI can label past
    // points without re-running the analyser on a truncated window (which
    // would flicker between Slowing and Conditioning every time a noisy
    // sample crossed a binary threshold).
    //
    // Phases progress monotonically:
    //
    //     Lag → Active → Slowing → Conditioning   (or → Stuck)
    //
    // Mid-ferment plateaus (diauxic shifts, brief stalls) are detected
    // separately by [PlateauDetector] and surfaced as overlays — they're
    // *not* phase transitions. There's exactly one Active onset, one
    // Slowing onset, and one Conditioning (or Stuck) onset per ferment;
    // there can be many paused episodes inside any of those.

    enum class Phase { Lag, Active, Slowing, Conditioning, Stuck, ColdCrash }

    /** Tunables for the phase-machine walk-back. */
    private const val MIN_SLOWING_HOLD_HOURS = 3.0
    private const val MIN_CONDITIONING_HOLD_HOURS = 6.0
    private const val MIN_STUCK_HOLD_HOURS = 6.0
    /** Magnitude below which the rolling rate counts as "near zero". */
    private const val NEAR_ZERO_RATE = 0.00012

    /** Cold-crash detection. Tuned to pick up clear temperature drops
     *  (≥ 4 °C below the warm reference, sustained for ≥ 1.5 h) without
     *  false-positiving on the natural diurnal swing of an unheated
     *  fermenter (typically 1–3 °C). */
    private const val COLD_CRASH_DROP_C = 4.0
    private const val COLD_CRASH_HYSTERESIS_C = 2.0
    private const val MIN_COLD_CRASH_HOLD_HOURS = 1.5
    /** Window for averaging temperature so a single noisy reading doesn't
     *  break a run. */
    private const val TEMP_SMOOTH_WINDOW_HOURS = 1.0

    /**
     * Frozen snapshot of a ferment's phase progression plus everything
     * needed to recompose a [State] at any cursor position. Computed
     * once on the full dataset; the cursor uses [stateAt] to look up an
     * arbitrary T without re-running the analyser.
     */
    data class Timeline(
        val og: Double,
        val priorFg: Double,
        val firstH: Double,
        val lastH: Double,
        /** First hour the SG had dropped > LAG_DROP from OG. null if still in lag. */
        val activeOnsetH: Double?,
        /**
         * First hour of the *final* slowed run (rate ≥ -ACTIVE_RATE) that
         * persists until the end of data. Null if the live state hasn't
         * reached Slowing yet.
         */
        val slowingOnsetH: Double?,
        /** First hour of the final near-zero-rate-and-near-priorFg run. */
        val conditioningOnsetH: Double?,
        /** First hour of the final near-zero-rate-but-above-priorFg run. */
        val stuckOnsetH: Double?,
        /**
         * First hour of the sustained temperature drop at end-of-data.
         * Null when temperatures weren't supplied or no clear cold-crash
         * signal is present.
         */
        val coldCrashOnsetH: Double?,
        /** Median rolling-temp before the crash. Used to render "from X°C". */
        val fermentTemperatureC: Double?,
        /** SG just before the crash started — the actual FG. */
        val fermentSg: Double?,
        /** Per-sample current temperature. Null when temps weren't supplied. */
        val temps: DoubleArray?,
        val gompertz: AttenuationFit.Result?,
        val plateaus: List<Plateau>,
        val predictedFg: Double,
        val predictedFgSigma: Double?,
        val source: PredictionSource,
        val sigma: Double,
        val recentRate: Double?,
        /** End-of-data ETA quantiles from the Gompertz Bayesian draws. */
        val etaCredibleLowHours: Double?,
        val etaCredibleHighHours: Double?
    ) {
        fun phaseAt(hoursFromStart: Double): Phase {
            // ColdCrash overrides everything once entered — the apparent
            // SG and rate are thermal artifacts, not fermentation signal.
            if (coldCrashOnsetH != null && hoursFromStart >= coldCrashOnsetH) return Phase.ColdCrash
            // Latest entered onset wins. Stuck and Conditioning are
            // mutually exclusive — only one is non-null.
            if (stuckOnsetH != null && hoursFromStart >= stuckOnsetH) return Phase.Stuck
            if (conditioningOnsetH != null && hoursFromStart >= conditioningOnsetH) return Phase.Conditioning
            if (slowingOnsetH != null && hoursFromStart >= slowingOnsetH) return Phase.Slowing
            if (activeOnsetH != null && hoursFromStart >= activeOnsetH) return Phase.Active
            return Phase.Lag
        }
    }

    /**
     * Build a [Timeline] from the full dataset. Returns null when the
     * dataset is below the minimum size/duration the live classifier
     * needs (caller should fall through to State.Insufficient).
     */
    fun buildTimeline(
        hours: DoubleArray,
        sgs: DoubleArray,
        calRSquared: Double? = null,
        temps: DoubleArray? = null
    ): Timeline? {
        require(hours.size == sgs.size)
        val n = hours.size
        if (n < MIN_POINTS) return null
        if (hours.last() - hours.first() < MIN_HOURS) return null

        val og = sgs.max()
        val current = sgs.last()
        val priorFg = max(0.998, 1.000 + 0.25 * (og - 1.000))

        val sigmaData = NoiseModel.estimateDataNoise(hours, sgs)
        val sigmaCal = calRSquared?.let { NoiseModel.fromCalibrationRSquared(it) }
        val sigma = NoiseModel.combine(sigmaData, sigmaCal)

        val recentRate = endRate(hours, sgs, sigma)
        val gompertz = AttenuationFit.fit(hours, sgs, sigma)
        val plateaus = PlateauDetector.detect(hours, sgs)
        val (predictedFg, source) = pickFgEstimate(og, gompertz, current, recentRate)
        val priorFgSigma = 0.06 * (og - 1.000).coerceAtLeast(0.005)
        val predictedFgSigma = when (source) {
            PredictionSource.Gompertz -> gompertz?.fgSigma
            else -> priorFgSigma
        }

        val activeOnsetH = (0 until n).firstOrNull { (og - sgs[it]) > LAG_DROP }
            ?.let { hours[it] }

        // Rolling stats — OLS slope and median over a 6-hour window
        // ending at each sample. Cheap to precompute (≈ O(n × window))
        // and the walk-back needs them at every point.
        val rollingRate = DoubleArray(n) { Double.NaN }
        val rollingMedianSg = DoubleArray(n) { sgs[it] }
        for (i in 0 until n) {
            val ws = lookupWindowStart(hours, i, RECENT_WINDOW_HOURS)
            val len = i - ws + 1
            if (len < 3) continue
            val winH = DoubleArray(len) { hours[ws + it] }
            val winSg = DoubleArray(len) { sgs[ws + it] }
            rollingRate[i] = Fits.fitLinear(winH, winSg)?.slope ?: Double.NaN
            rollingMedianSg[i] = Fits.median(winSg) ?: sgs[i]
        }

        // Walk-back from end to find the start of each *final* run that
        // persists till end-of-data. Only assign onsets that the live
        // state has actually reached — past slowing-then-resumed-active
        // episodes (mid-ferment pauses) get filtered out because the
        // walk-back stops at the first re-acceleration.
        val drop = og - current
        val endRate = rollingRate[n - 1].takeIf { it.isFinite() }
        val endMedSg = rollingMedianSg[n - 1]
        val endIsFlat = endRate != null && abs(endRate) < NEAR_ZERO_RATE
        val endIsSlow = endRate != null && endRate > ACTIVE_RATE
        val endAtFg = endMedSg <= priorFg + 0.003
        val endAboveFg = endMedSg > priorFg + STUCK_FG_GAP
        val haveDescent = drop > STUCK_DROP_THRESHOLD

        val conditioningOnsetH = if (haveDescent && endIsFlat && endAtFg) {
            walkBackOnset(hours, n - 1, rollingRate, rollingMedianSg, MIN_CONDITIONING_HOLD_HOURS) { rate, sg ->
                abs(rate) < NEAR_ZERO_RATE && sg <= priorFg + 0.003
            }
        } else null

        val stuckOnsetH = if (haveDescent && endIsFlat && endAboveFg && conditioningOnsetH == null) {
            walkBackOnset(hours, n - 1, rollingRate, rollingMedianSg, MIN_STUCK_HOLD_HOURS) { rate, sg ->
                abs(rate) < NEAR_ZERO_RATE && sg > priorFg + STUCK_FG_GAP
            }
        } else null

        // Slowing onset: when the rate first dropped below the active
        // threshold for the run that persists to end-of-data. Conditioning
        // is a subset of Slowing (slow rate ⊃ near-zero rate), so the
        // walk-back naturally places slowingOnsetH ≤ conditioningOnsetH.
        val slowingOnsetH = if (
            haveDescent && activeOnsetH != null &&
            (endIsSlow || conditioningOnsetH != null || stuckOnsetH != null)
        ) {
            walkBackOnset(hours, n - 1, rollingRate, rollingMedianSg, MIN_SLOWING_HOLD_HOURS) { rate, _ ->
                rate > ACTIVE_RATE
            }?.coerceAtLeast(activeOnsetH)
        } else null

        // Cold-crash detection. Walk back from end while the rolling
        // 1-hour-mean temperature stays below (warmRef − hysteresis).
        // warmRef is the median rolling-mean temperature across the
        // dataset, robust to the cold tail itself: with cold-crash data
        // typically a small minority of the run, the median picks the
        // pre-crash plateau even when the tail is several degrees lower.
        val (coldCrashOnsetH, fermentTempC, fermentSg) = detectColdCrashOnset(hours, sgs, temps)

        val terminalSg = predictedFg + 0.001
        val etaCredible: Pair<Double?, Double?> = if (source == PredictionSource.Gompertz && gompertz != null) {
            val rng = Random(BAYESIAN_SEED)
            val q = gompertz.etaQuantiles(terminalSg, rng)
            if (q != null && q.size >= 3) {
                val low = (q[0] - hours.last()).takeIf { it.isFinite() && it >= 0.0 }
                val high = (q[2] - hours.last()).takeIf { it.isFinite() && it >= 0.0 }
                low to high
            } else null to null
        } else null to null

        return Timeline(
            og = og,
            priorFg = priorFg,
            firstH = hours.first(),
            lastH = hours.last(),
            activeOnsetH = activeOnsetH,
            slowingOnsetH = slowingOnsetH,
            conditioningOnsetH = conditioningOnsetH,
            stuckOnsetH = stuckOnsetH,
            coldCrashOnsetH = coldCrashOnsetH,
            fermentTemperatureC = fermentTempC,
            fermentSg = fermentSg,
            temps = temps,
            gompertz = gompertz,
            plateaus = plateaus,
            predictedFg = predictedFg,
            predictedFgSigma = predictedFgSigma,
            source = source,
            sigma = sigma,
            recentRate = recentRate,
            etaCredibleLowHours = etaCredible.first,
            etaCredibleHighHours = etaCredible.second
        )
    }

    /**
     * Reconstruct a [State] at an arbitrary time T, using [timeline]'s
     * frozen onsets for the phase label and the data plus global
     * Gompertz fit for the per-state numbers (current SG, rate, ETA, …).
     *
     * Used by the chart-cursor scrubbing UI. Pass [hoursFromStart] =
     * timeline.lastH for the same answer the live analyser produces.
     */
    fun stateAt(
        timeline: Timeline,
        hours: DoubleArray,
        sgs: DoubleArray,
        hoursFromStart: Double
    ): State {
        val n = hours.size
        val tClamped = hoursFromStart.coerceIn(timeline.firstH, timeline.lastH)
        val phase = timeline.phaseAt(tClamped)
        val idxAtT = lookupIndexAt(hours, tClamped)
        val sgAtT = sgs[idxAtT]
        val durationH = tClamped - timeline.firstH

        // Local rate over the 6 h window ending at T (OLS, same definition
        // as the live recentRate). Falls back to the global rate near the
        // start of the dataset where the window doesn't fit.
        val rateAtT = localRate(hours, sgs, idxAtT) ?: timeline.recentRate ?: 0.0

        // ETA = how long the global Gompertz curve says the wort has left
        // until the terminal SG, measured from T. For non-Gompertz
        // sources, fall back to a linear extrapolation from local rate.
        val terminalSg = timeline.predictedFg + 0.001
        val etaAtT: Double? = when (timeline.source) {
            PredictionSource.Gompertz -> timeline.gompertz?.timeToReach(terminalSg)
                ?.minus(tClamped)
                ?.takeIf { it.isFinite() && it >= 0.0 }
            else -> linearEta(sgAtT, terminalSg, rateAtT)
        }

        // Plateaus visible at T: any pause whose onset has happened by T.
        // An ongoing pause (endH > T) is reported with its duration up
        // *to* T, not its eventual full duration — so a cursor sitting
        // inside a pause shows "paused for 3 h so far", not "paused for
        // 5 h" derived from data the cursor hasn't reached yet.
        val plateausUpToT = timeline.plateaus
            .filter { it.startH <= tClamped }
            .map { if (it.endH > tClamped) it.copy(endH = tClamped) else it }
        // The pause the cursor is currently inside, if any. Used to swap
        // in stuck-ferment guidance instead of generic active advice
        // when scrubbing into a paused region. Lag plateaus don't count
        // here — State.Lag covers that case with its own description.
        val currentPause = timeline.plateaus
            .firstOrNull {
                it.kind != Plateau.Kind.Lag &&
                    it.startH <= tClamped && it.endH > tClamped
            }
            ?.copy(endH = tClamped)

        return when (phase) {
            Phase.Lag -> State.Lag(
                og = timeline.og,
                current = sgAtT,
                durationHours = durationH
            )
            Phase.Active -> State.Active(
                og = timeline.og,
                current = sgAtT,
                ratePerHour = rateAtT,
                predictedFg = timeline.predictedFg,
                etaToFinishHours = etaAtT,
                source = timeline.source,
                predictedFgSigma = timeline.predictedFgSigma,
                measurementSigma = timeline.sigma,
                etaCredibleLowHours = timeline.etaCredibleLowHours,
                etaCredibleHighHours = timeline.etaCredibleHighHours,
                plateaus = plateausUpToT,
                currentPause = currentPause
            )
            Phase.Slowing -> State.Slowing(
                og = timeline.og,
                current = sgAtT,
                ratePerHour = rateAtT,
                predictedFg = timeline.predictedFg,
                etaToFinishHours = etaAtT,
                source = timeline.source,
                predictedFgSigma = timeline.predictedFgSigma,
                measurementSigma = timeline.sigma,
                etaCredibleLowHours = timeline.etaCredibleLowHours,
                etaCredibleHighHours = timeline.etaCredibleHighHours,
                plateaus = plateausUpToT,
                currentPause = currentPause
            )
            Phase.Conditioning -> State.Conditioning(
                og = timeline.og,
                fg = sgAtT,
                plateaus = plateausUpToT
            )
            Phase.Stuck -> {
                val flatHours = if (timeline.stuckOnsetH != null)
                    (tClamped - timeline.stuckOnsetH).coerceAtLeast(0.0)
                else 0.0
                State.Stuck(
                    og = timeline.og,
                    current = sgAtT,
                    expectedFg = timeline.priorFg,
                    flatHours = flatHours
                )
            }
            Phase.ColdCrash -> {
                val crashStart = timeline.coldCrashOnsetH ?: tClamped
                val durationH = (tClamped - crashStart).coerceAtLeast(0.0)
                val tempAtT = timeline.temps?.getOrNull(idxAtT) ?: Double.NaN
                State.ColdCrash(
                    og = timeline.og,
                    apparentSg = sgAtT,
                    fermentSg = timeline.fermentSg ?: sgAtT,
                    temperatureC = tempAtT,
                    fermentTemperatureC = timeline.fermentTemperatureC ?: tempAtT,
                    durationH = durationH
                )
            }
        }
    }

    /**
     * Cold-crash onset detector. Returns:
     *  - hour from start where the temperature run (≤ warmRef − hysteresis)
     *    that ends at end-of-data began, or null if no clear crash;
     *  - the warm reference temperature (median rolling-mean before crash);
     *  - the SG observed at crash onset (the actual fermentation FG).
     *
     * Walks the temperature series in two passes: first computes a 1-hour
     * rolling mean (drops a single noisy reading), then walks back from
     * the last sample while the rolling mean stays below threshold.
     * Hysteresis between the entry threshold (warmRef − DROP) and the
     * walk-back threshold (warmRef − HYSTERESIS) avoids flickering when
     * the cold-crash floor is just above warmRef − DROP.
     */
    private fun detectColdCrashOnset(
        hours: DoubleArray,
        sgs: DoubleArray,
        temps: DoubleArray?
    ): Triple<Double?, Double?, Double?> {
        if (temps == null || temps.size != hours.size || hours.size < 6) {
            return Triple(null, null, null)
        }
        val n = hours.size

        // 1-h rolling mean temperature at each sample.
        val rollingTemp = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val ws = lookupWindowStart(hours, i, TEMP_SMOOTH_WINDOW_HOURS)
            var sum = 0.0
            var count = 0
            for (j in ws..i) { sum += temps[j]; count++ }
            if (count > 0) rollingTemp[i] = sum / count
        }
        val finite = rollingTemp.filter { it.isFinite() }
        if (finite.isEmpty()) return Triple(null, null, null)

        // Median of rolling-mean temperatures across the whole dataset
        // serves as the "warm reference". Robust against the cold tail
        // because a typical cold-crash region is a small fraction of the
        // run and lives in the lower tail of the distribution.
        val warmRef = Fits.median(finite.toDoubleArray()) ?: return Triple(null, null, null)

        val endRolling = rollingTemp[n - 1]
        if (!endRolling.isFinite() || endRolling >= warmRef - COLD_CRASH_DROP_C) {
            return Triple(null, warmRef, null)
        }

        // Walk back while the rolling mean stays below the hysteresis
        // threshold; stop at the first point that's back in warm range.
        var earliestIdx = n - 1
        for (i in n - 1 downTo 0) {
            val r = rollingTemp[i]
            if (!r.isFinite()) continue
            if (r < warmRef - COLD_CRASH_HYSTERESIS_C) earliestIdx = i else break
        }
        if (hours[n - 1] - hours[earliestIdx] < MIN_COLD_CRASH_HOLD_HOURS) {
            return Triple(null, warmRef, null)
        }
        // Pre-crash SG = the SG at the sample just before crash onset
        // (or the first sample if onset is at index 0).
        val preIdx = (earliestIdx - 1).coerceAtLeast(0)
        return Triple(hours[earliestIdx], warmRef, sgs[preIdx])
    }

    /**
     * Walk back from [endIdx] while [predicate] holds for the rolling
     * (rate, medianSG) at each point. Returns the timestamp at which
     * the run started, or null if the run is shorter than [minHoldHours].
     */
    private fun walkBackOnset(
        hours: DoubleArray,
        endIdx: Int,
        rollingRate: DoubleArray,
        rollingMedianSg: DoubleArray,
        minHoldHours: Double,
        predicate: (rate: Double, sg: Double) -> Boolean
    ): Double? {
        var earliestIdx = endIdx
        for (i in endIdx downTo 0) {
            val rate = rollingRate[i]
            if (rate.isNaN()) continue
            if (predicate(rate, rollingMedianSg[i])) earliestIdx = i else break
        }
        if (earliestIdx == endIdx) return null
        if (hours[endIdx] - hours[earliestIdx] < minHoldHours) return null
        return hours[earliestIdx]
    }

    private fun lookupWindowStart(hours: DoubleArray, endIdx: Int, windowHours: Double): Int {
        val cutoff = hours[endIdx] - windowHours
        for (i in endIdx downTo 0) if (hours[i] < cutoff) return i + 1
        return 0
    }

    private fun lookupIndexAt(hours: DoubleArray, t: Double): Int {
        val idx = hours.indexOfLast { it <= t }
        return if (idx < 0) 0 else idx
    }

    private fun localRate(hours: DoubleArray, sgs: DoubleArray, endIdx: Int): Double? {
        val ws = lookupWindowStart(hours, endIdx, RECENT_WINDOW_HOURS)
        val len = endIdx - ws + 1
        if (len < 3) return null
        val winH = DoubleArray(len) { hours[ws + it] }
        val winSg = DoubleArray(len) { sgs[ws + it] }
        return Fits.fitLinear(winH, winSg)?.slope
    }

    /**
     * The 6-hour-tail OLS slope at end-of-data with the same 3σ outlier
     * rejection that [analyse] uses. Factored out so [buildTimeline] can
     * share it.
     */
    private fun endRate(hours: DoubleArray, sgs: DoubleArray, sigma: Double): Double? {
        val tailStart = hours.last() - RECENT_WINDOW_HOURS
        val tailIdx = hours.indices.filter { hours[it] >= tailStart }
        val tailHours = DoubleArray(tailIdx.size) { hours[tailIdx[it]] }
        val tailSgs = DoubleArray(tailIdx.size) { sgs[tailIdx[it]] }
        val initial = if (tailIdx.size >= 3) Fits.fitLinear(tailHours, tailSgs) else null
        val refined = if (initial != null && tailIdx.size >= 5) {
            val keep = tailHours.indices.filter { i ->
                abs(tailSgs[i] - initial.predict(tailHours[i])) <= 3.0 * sigma
            }
            if (keep.size >= 3 && keep.size < tailHours.size) {
                Fits.fitLinear(
                    DoubleArray(keep.size) { tailHours[keep[it]] },
                    DoubleArray(keep.size) { tailSgs[keep[it]] }
                ) ?: initial
            } else initial
        } else initial
        return refined?.slope
    }
}
