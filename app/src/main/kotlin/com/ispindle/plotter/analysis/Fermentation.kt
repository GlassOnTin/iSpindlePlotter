package com.ispindle.plotter.analysis

import kotlin.math.abs
import kotlin.math.max

/**
 * Phase classifier and predictive model for an in-flight fermentation.
 *
 * Combines two layers of evidence:
 *   - **Recent rate** from a 6-hour linear fit on the tail of the data.
 *     Robust to noise and works the moment we have any decline.
 *   - **Logistic fit** over the whole window (when there's enough signal),
 *     giving OG, FG, inflection, and a long-range ETA.
 *
 * The state always reports observed OG (max SG seen) so the user has a
 * concrete reference even before the logistic converges.
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
            val measurementSigma: Double? = null
        ) : State()

        data class Slowing(
            val og: Double,
            val current: Double,
            val ratePerHour: Double,
            val predictedFg: Double,
            val etaToFinishHours: Double?,
            val source: PredictionSource,
            val predictedFgSigma: Double? = null,
            val measurementSigma: Double? = null
        ) : State()

        data class Complete(
            val og: Double,
            val fg: Double
        ) : State()

        data class Stuck(
            val og: Double,
            val current: Double,
            val expectedFg: Double,
            val flatHours: Double
        ) : State()
    }

    enum class PredictionSource {
        Logistic,
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

        // 3. Try the full-window logistic. May be unreliable while we're
        //    still inside the active phase, but worth a shot — the
        //    bounded LM is conservative. Pass measurement σ so the result
        //    carries an FG uncertainty.
        val logistic = LogisticFit.fit(hours, sgs, sigma)

        // 4. Predicted FG with graceful degradation.
        val (predictedFg, source) = pickFgEstimate(
            og = og,
            logistic = logistic,
            current = current,
            recentRate = recentRate
        )

        // The 75 %-attenuation prior — independent of data, used as the
        // sanity check for "is the current plateau where it ought to be?".
        // A data plateau much higher than this is the signature of a
        // stuck ferment, not a finished one.
        val priorFg = max(0.998, 1.000 + 0.25 * (og - 1.000))
        val rateNearZero = recentRate != null && abs(recentRate) < 0.0001
        val flatTail = flatTailDuration(hours, sgs)

        // 5. Complete? Data plateau AND that plateau is at-or-below the
        //    prior FG (i.e. we've plausibly attenuated as expected).
        if (rateNearZero &&
            drop > STUCK_DROP_THRESHOLD &&
            current <= priorFg + 0.003
        ) {
            return State.Complete(og = og, fg = current)
        }

        // 6. Stuck? Data plateau but well above the prior FG — fermentation
        //    has stalled with sugar still in the wort.
        if (rateNearZero &&
            drop > STUCK_DROP_THRESHOLD &&
            current > priorFg + STUCK_FG_GAP &&
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
            PredictionSource.Logistic -> logistic?.timeToReach(terminalSg)?.minus(now)
            else -> linearEta(current, terminalSg, recentRate)
        }?.takeIf { it.isFinite() && it >= 0.0 }

        // 8. FG uncertainty: from the LM covariance when we trust the
        //    logistic, or from the prior's spread (≈±0.003 SG, the std of
        //    typical attenuation outcomes 60–90 % across mainstream beer
        //    styles) when we're falling back to the 75 %-attenuation prior.
        val priorFgSigma = 0.06 * (og - 1.000).coerceAtLeast(0.005)
        val predictedFgSigma = when (source) {
            PredictionSource.Logistic -> logistic?.fgSigma
            else -> priorFgSigma
        }

        // 9. Active vs Slowing branch on rate magnitude.
        val rate = recentRate ?: 0.0
        return if (rate < ACTIVE_RATE) {
            State.Active(
                og = og, current = current, ratePerHour = rate,
                predictedFg = predictedFg, etaToFinishHours = eta, source = source,
                predictedFgSigma = predictedFgSigma, measurementSigma = sigma
            )
        } else {
            State.Slowing(
                og = og, current = current, ratePerHour = rate,
                predictedFg = predictedFg, etaToFinishHours = eta, source = source,
                predictedFgSigma = predictedFgSigma, measurementSigma = sigma
            )
        }
    }

    private fun pickFgEstimate(
        og: Double,
        logistic: LogisticFit.Result?,
        current: Double,
        recentRate: Double?
    ): Pair<Double, PredictionSource> {
        // Trust the logistic only when:
        //  * its OG matches what we actually observed,
        //  * its FG is mechanically plausible (above the 90 % attenuation
        //    floor and below OG),
        //  * its FG isn't just hugging the data's tail — when current
        //    minus FG is < ~0.002 the model has identified "where data
        //    happened to end", not a real asymptote, and
        //  * the implied attenuation is at least 50 %, which excludes the
        //    "still in lag, fit thinks lag is the asymptote" failure mode.
        if (logistic != null) {
            val ogClose = abs(logistic.og - og) < 0.005
            val fgPlausible = logistic.fg in 0.985..(og - 0.001)
            val notHuggingData = current - logistic.fg > 0.002
            val attenuation = (og - logistic.fg) / max(og - 1.000, 1e-6)
            val attenuationPlausible = attenuation >= 0.50
            if (ogClose && fgPlausible && notHuggingData && attenuationPlausible) {
                return logistic.fg to PredictionSource.Logistic
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
}
