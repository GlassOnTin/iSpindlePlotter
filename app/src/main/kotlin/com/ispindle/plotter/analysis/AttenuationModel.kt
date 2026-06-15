package com.ispindle.plotter.analysis

import kotlin.math.ln
import kotlin.random.Random

/**
 * Polymorphic facade over the two attenuation fitters
 * ([AttenuationFit] and [TwoComponentAttenuationFit]) so callers above the
 * analysis layer — Timeline, headline state machine, chart overlay — can
 * predict / sample / band against either model without case-splitting.
 *
 * The API is the union of the two fitters' public methods. Both fitters
 * already supply identical method shapes for [predict], [rateAt],
 * [timeToReach], [sample], [predictiveBand], [etaQuantiles]; this
 * interface just keeps the call sites that bind to one type today from
 * having to know which fit the [AttenuationModelSelector] settled on.
 *
 * [citation] backs the model-source line under the chart so the user
 * knows what's fitting their data when biphasic kicks in.
 *
 * [headlineDetail] supplies the biphasic split for the headline numbers
 * line ("phase 1 of 2, 73% of drop"). Null on the single-component
 * implementation so the existing copy stays untouched there.
 */
sealed interface AttenuationModel {
    val og: Double
    val fg: Double
    val rmsResidual: Double
    val pointCount: Int
    val fgSigma: Double?
    val citation: String
    val headlineDetail: String?
    /**
     * Whether the underlying fitter produced a Laplace covariance — i.e.
     * whether [predictiveBand] and [etaQuantiles] return a real spread vs
     * a degenerate point. Equivalent to the old `covariance != null`
     * branch the chart used directly on [AttenuationFit.Result].
     */
    val hasBand: Boolean
    /**
     * Bayesian Information Criterion for this fit:
     *   BIC = n·ln(RSS/n) + k·ln(n)
     * Used by the selector to compare candidate fits — lower is better,
     * the `k·ln(n)` term penalises a richer model for taking on more
     * parameters that the data may not justify.
     */
    val bic: Double

    fun predict(t: Double): Double
    fun rateAt(t: Double): Double
    fun timeToReach(target: Double): Double?
    fun sample(rng: Random): DoubleArray
    fun predictiveBand(
        times: DoubleArray,
        rng: Random,
        nSamples: Int = 256,
        quantiles: DoubleArray = doubleArrayOf(0.025, 0.5, 0.975)
    ): Array<DoubleArray>
    fun etaQuantiles(
        target: Double,
        rng: Random,
        nSamples: Int = 256,
        quantiles: DoubleArray = doubleArrayOf(0.025, 0.5, 0.975)
    ): DoubleArray?

    /** 4-parameter modified Gompertz wrapper. */
    data class SingleGompertz(val fit: AttenuationFit.Result) : AttenuationModel {
        override val og: Double get() = fit.og
        override val fg: Double get() = fit.fg
        override val rmsResidual: Double get() = fit.rmsResidual
        override val pointCount: Int get() = fit.pointCount
        override val fgSigma: Double? get() = fit.fgSigma
        override val citation: String get() = AttenuationFit.ReferenceCitation
        override val headlineDetail: String? get() = null
        override val hasBand: Boolean get() = fit.covariance != null
        override val bic: Double get() = bicOf(rmsResidual, pointCount, k = 4)

        override fun predict(t: Double): Double = fit.predict(t)
        override fun rateAt(t: Double): Double = fit.rateAt(t)
        override fun timeToReach(target: Double): Double? = fit.timeToReach(target)
        override fun sample(rng: Random): DoubleArray = fit.sample(rng)
        override fun predictiveBand(
            times: DoubleArray, rng: Random, nSamples: Int, quantiles: DoubleArray
        ): Array<DoubleArray> = fit.predictiveBand(times, rng, nSamples, quantiles)
        override fun etaQuantiles(
            target: Double, rng: Random, nSamples: Int, quantiles: DoubleArray
        ): DoubleArray? = fit.etaQuantiles(target, rng, nSamples, quantiles)
    }

    /** 7-parameter biphasic (two-component Gompertz) wrapper. */
    data class TwoComponent(val fit: TwoComponentAttenuationFit.Result) : AttenuationModel {
        override val og: Double get() = fit.og
        override val fg: Double get() = fit.fg
        override val rmsResidual: Double get() = fit.rmsResidual
        override val pointCount: Int get() = fit.pointCount
        override val fgSigma: Double? get() = fit.fgSigma
        override val citation: String get() = TwoComponentAttenuationFit.ReferenceCitation
        override val headlineDetail: String?
            get() = "biphasic · phase 1 ${"%.0f".format(fit.alpha * 100)}% of drop"
        override val hasBand: Boolean get() = fit.covariance != null
        override val bic: Double get() = bicOf(rmsResidual, pointCount, k = 7)

        override fun predict(t: Double): Double = fit.predict(t)
        override fun rateAt(t: Double): Double = fit.rateAt(t)
        override fun timeToReach(target: Double): Double? = fit.timeToReach(target)
        override fun sample(rng: Random): DoubleArray = fit.sample(rng)
        override fun predictiveBand(
            times: DoubleArray, rng: Random, nSamples: Int, quantiles: DoubleArray
        ): Array<DoubleArray> = fit.predictiveBand(times, rng, nSamples, quantiles)
        override fun etaQuantiles(
            target: Double, rng: Random, nSamples: Int, quantiles: DoubleArray
        ): DoubleArray? = fit.etaQuantiles(target, rng, nSamples, quantiles)
    }

    companion object {
        private fun bicOf(rms: Double, n: Int, k: Int): Double {
            if (n <= k || rms <= 0.0) return Double.POSITIVE_INFINITY
            val rss = rms * rms * n
            return n * ln(rss / n) + k * ln(n.toDouble())
        }
    }
}

/**
 * Picks between the single and two-component Gompertz fits by BIC.
 *
 * Single is the default — runs on every call. Two-component is attempted
 * only when there's structural evidence the data is biphasic: a detected
 * Mid plateau (the diauxic shift signature) and enough data span past it
 * to characterise the second phase. Even when attempted, two-component
 * only wins if BIC improves by at least [BIC_PROMOTION_THRESHOLD] — a
 * conservative gate that requires the data to clearly justify the extra
 * three parameters. On truly unimodal brews this keeps the simpler model
 * in play; on a real biphasic, the BIC margin is hundreds (≈ 6500 on the
 * brew2 late capture) and the two-component wins cleanly.
 *
 * Returns null only if even the single fit fails.
 */
object AttenuationModelSelector {

    /**
     * Minimum BIC drop required to promote two-component over single.
     * BIC differences scale with sample count and effect size; a
     * threshold of 100 is well past the textbook "decisive" Δ > 10 and
     * leaves a comfortable margin for noise-driven fluctuations on the
     * brew-size data the app routinely sees.
     */
    private const val BIC_PROMOTION_THRESHOLD = 100.0

    /**
     * Minimum hours of data past the detected diauxic plateau's end
     * required to fit phase 2 at all. Phase 2 is the slow-kinetic
     * maltotriose pass; characterising its inflection needs at least
     * this much of its descent to be in the captured window.
     */
    private const val MIN_PHASE_2_HOURS = 12.0

    fun fit(
        xs: DoubleArray,
        ys: DoubleArray,
        plateaus: List<Plateau>,
        measurementSigma: Double? = null,
        attenuationPrior: AttenuationFit.AttenuationPrior? = AttenuationFit.DefaultAttenuationPrior,
        fgFloor: Double? = null,
        temps: DoubleArray? = null
    ): AttenuationModel? {
        val single = AttenuationFit.fit(xs, ys, measurementSigma, attenuationPrior, fgFloor, temps)
            ?: return null
        val singleModel = AttenuationModel.SingleGompertz(single)

        // Anchor on the most prominent diauxic pause, not merely the first.
        // A brief flat blip on the early steep descent can register as a Mid
        // ahead of the real, much longer pause; promoting on the longest Mid
        // keeps phase 1 / phase 2 split at the true shift.
        val midPause = plateaus.filter { it.kind == Plateau.Kind.Mid }.maxByOrNull { it.durationH }
            ?: return singleModel
        // Need enough post-pause data to identify phase 2's inflection.
        if (xs.isEmpty() || xs.last() - midPause.endH < MIN_PHASE_2_HOURS) return singleModel
        val hint = TwoComponentAttenuationFit.BiphasicHint(
            pauseStartH = midPause.startH,
            pauseEndH = midPause.endH,
            pauseSg = midPause.sg
        )
        val two = TwoComponentAttenuationFit.fit(
            xs, ys, hint, measurementSigma, attenuationPrior, fgFloor, temps
        ) ?: return singleModel
        val twoModel = AttenuationModel.TwoComponent(two)

        // Promote only when the data clearly prefers the richer model.
        return if (singleModel.bic - twoModel.bic >= BIC_PROMOTION_THRESHOLD) twoModel
        else singleModel
    }
}
