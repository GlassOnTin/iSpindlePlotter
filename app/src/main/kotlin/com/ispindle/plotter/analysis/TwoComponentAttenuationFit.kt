package com.ispindle.plotter.analysis

import kotlin.math.E
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 7-parameter biphasic attenuation model — sum of two modified Gompertz
 * curves sharing OG, FG, and a partition fraction:
 *
 *     SG(t) = OG − α·A·G(t; μ₁, λ₁) − (1−α)·A·G(t; μ₂, λ₂)
 *     A     = OG − FG
 *     G(t; μ, λ; A_phase) = exp(−exp((μ·e/A_phase)·(λ−t) + 1))
 *
 * Phase 1 (α-weighted) is the easy-sugar pass — glucose, fructose, maltose
 * — peaks fast and accounts for most of the visible descent. Phase 2 is
 * the maltotriose/dextrin pass: lower amplitude, lower peak rate, longer
 * tail. The diauxic shift between them is the "pause at 1.037" the single-
 * component Gompertz can't represent (one curve, one inflection, one
 * exponential asymptote — biphasic data has two).
 *
 * Fit by Levenberg-Marquardt with the same Bayesian attenuation prior the
 * single-component fitter uses (Gaussian on (OG−FG)/(OG−1)). Phases are
 * order-canonicalised after the fit so phase 1 is always the one with the
 * earlier inflection — keeps reporting and tests stable across runs.
 *
 * Identifiability:
 *  - α floored at 0.05 and ceilinged at 0.95 — pure single-phase data
 *    that lands at a boundary should fall back to the single-component
 *    model via [AttenuationModelSelector], not be forced through here.
 *  - λ₁ < λ₂ enforced post-fit by swap; bounds during fit allow either
 *    ordering so the LM doesn't fight the canonicalisation.
 *  - Requires the data span to at least cover both phases' inflections;
 *    enforced by a minimum-points threshold and an initial-guess heuristic
 *    that uses the detected diauxic plateau when one exists.
 *
 * Same API surface as [AttenuationFit.Result]: [Result.predict],
 * [Result.rateAt], [Result.sample], [Result.predictiveBand],
 * [Result.etaQuantiles]. The chart overlay and state machine consume the
 * result through that same shape, so swapping the underlying model is a
 * no-op for callers above the fitter.
 */
object TwoComponentAttenuationFit {

    const val ReferenceCitation: String =
        "Two-component modified Gompertz (biphasic) · sum of two Zwietering 1990 curves"

    private const val N_PARAMS = 7
    // Param index conventions, used throughout this file.
    private const val OG = 0
    private const val FG = 1
    private const val ALPHA = 2
    private const val MU1 = 3
    private const val LAM1 = 4
    private const val MU2 = 5
    private const val LAM2 = 6

    /**
     * Initial-guess hint, normally derived from a detected diauxic
     * plateau (`PlateauDetector.Plateau` of Kind.Mid). Passing null lets
     * the fitter pick generic defaults — slower convergence and a higher
     * chance of landing in a degenerate minimum on borderline cases.
     */
    data class BiphasicHint(
        /** Plateau start time (hours from data start). */
        val pauseStartH: Double,
        /** Plateau end time (hours from data start). */
        val pauseEndH: Double,
        /** Mean SG across the plateau — the diauxic "landing" gravity. */
        val pauseSg: Double
    )

    data class Result(
        val og: Double,
        val fg: Double,
        /** Fraction of the total OG−FG drop carried by phase 1 (0 < α < 1). */
        val alpha: Double,
        /** Phase 1 peak |dSG/dt|, SG units / hour. Always positive. */
        val mu1: Double,
        /** Phase 1 lag time in hours (Zwietering λ). */
        val lambda1: Double,
        /** Phase 2 peak |dSG/dt|. */
        val mu2: Double,
        /** Phase 2 lag time in hours. */
        val lambda2: Double,
        val rmsResidual: Double,
        val pointCount: Int,
        val fgSigma: Double? = null,
        /**
         * 7×7 parameter covariance, in the order [og, fg, α, μ₁, λ₁, μ₂,
         * λ₂]. Null when n < 8 or the Hessian was singular.
         */
        val covariance: Array<DoubleArray>? = null
    ) {
        /** Phase 1's contribution to the OG → FG drop, in SG units. */
        val drop1: Double get() = alpha * (og - fg)

        /** Phase 2's contribution to the OG → FG drop, in SG units. */
        val drop2: Double get() = (1.0 - alpha) * (og - fg)

        fun predict(t: Double): Double =
            predictAt(og, fg, alpha, mu1, lambda1, mu2, lambda2, t)

        /** dSG/dt at time t (sum of the two phase derivatives). Negative during descent. */
        fun rateAt(t: Double): Double {
            val a = og - fg
            if (a <= 0.0) return 0.0
            val a1 = alpha * a
            val a2 = (1.0 - alpha) * a
            val r1 = if (a1 > 1e-9 && mu1 > 0.0) {
                val arg = mu1 * E / a1 * (lambda1 - t) + 1.0
                -uvOf(arg) * mu1 * E
            } else 0.0
            val r2 = if (a2 > 1e-9 && mu2 > 0.0) {
                val arg = mu2 * E / a2 * (lambda2 - t) + 1.0
                -uvOf(arg) * mu2 * E
            } else 0.0
            return r1 + r2
        }

        fun timeToReach(target: Double): Double? =
            inverseAt(og, fg, alpha, mu1, lambda1, mu2, lambda2, target)

        /**
         * Draw one parameter sample from the Laplace approximation, in the
         * same order as [covariance]. Snaps degenerate draws back to the
         * MAP — same convention as the single-component fitter.
         */
        fun sample(rng: Random): DoubleArray {
            val cov = covariance
            val mean = doubleArrayOf(og, fg, alpha, mu1, lambda1, mu2, lambda2)
            if (cov == null) return mean
            val l = choleskyN(cov) ?: return mean
            val z = DoubleArray(N_PARAMS) { boxMuller(rng) }
            val out = mean.copyOf()
            for (i in 0 until N_PARAMS) {
                for (j in 0..i) out[i] += l[i][j] * z[j]
            }
            // Degenerate snap: any draw that breaks the model's basic
            // monotonicity falls back to MAP rather than emit nonsense.
            if (out[OG] - out[FG] <= 0.0) return mean
            if (out[ALPHA] !in 0.0..1.0) return mean
            if (out[MU1] <= 0.0 || out[MU2] <= 0.0) return mean
            return out
        }

        fun predictiveBand(
            times: DoubleArray,
            rng: Random,
            nSamples: Int = 256,
            quantiles: DoubleArray = doubleArrayOf(0.025, 0.5, 0.975)
        ): Array<DoubleArray> {
            val cov = covariance
            if (cov == null) {
                val mapCurve = DoubleArray(times.size) { predict(times[it]) }
                return Array(quantiles.size) { mapCurve.copyOf() }
            }
            val curves = Array(nSamples) {
                val s = sample(rng)
                DoubleArray(times.size) { i ->
                    predictAt(s[OG], s[FG], s[ALPHA], s[MU1], s[LAM1], s[MU2], s[LAM2], times[i])
                }
            }
            return Array(quantiles.size) { qi ->
                val q = quantiles[qi].coerceIn(0.0, 1.0)
                DoubleArray(times.size) { ti ->
                    val sorted = DoubleArray(nSamples) { curves[it][ti] }.also { it.sort() }
                    val idx = ((q * (nSamples - 1)).toInt()).coerceIn(0, nSamples - 1)
                    sorted[idx]
                }
            }
        }

        fun etaQuantiles(
            target: Double,
            rng: Random,
            nSamples: Int = 256,
            quantiles: DoubleArray = doubleArrayOf(0.025, 0.5, 0.975)
        ): DoubleArray? {
            covariance ?: return null
            val draws = ArrayList<Double>(nSamples)
            repeat(nSamples) {
                val s = sample(rng)
                val t = inverseAt(s[OG], s[FG], s[ALPHA], s[MU1], s[LAM1], s[MU2], s[LAM2], target)
                    ?: return@repeat
                if (t.isFinite()) draws += t
            }
            if (draws.size < nSamples / 4) return null
            draws.sort()
            return DoubleArray(quantiles.size) { qi ->
                val q = quantiles[qi].coerceIn(0.0, 1.0)
                val idx = ((q * (draws.size - 1)).toInt()).coerceIn(0, draws.size - 1)
                draws[idx]
            }
        }
    }

    /**
     * Fit a two-component Gompertz to [xs] (hours from start) / [ys] (SG).
     *
     * @param hint diauxic-plateau-derived initial-guess hint. When present,
     *        the LM starts with the inflection times bracketing the plateau
     *        and α partitioned to match the SG drop into the plateau —
     *        often the difference between converging in 30 iterations and
     *        landing in a degenerate corner with α at a bound.
     * @param measurementSigma per-point noise σ for scaling the Laplace
     *        covariance; falls back to in-sample residual std at n-7 dof.
     * @param attenuationPrior soft Gaussian prior on (OG−FG)/(OG−1).
     * @param fgFloor hard lower bound on FG.
     */
    fun fit(
        xs: DoubleArray,
        ys: DoubleArray,
        hint: BiphasicHint? = null,
        measurementSigma: Double? = null,
        attenuationPrior: AttenuationFit.AttenuationPrior? = AttenuationFit.DefaultAttenuationPrior,
        fgFloor: Double? = null,
        temps: DoubleArray? = null
    ): Result? {
        require(xs.size == ys.size)
        val keep = SeriesClean.keptIndices(xs, ys, temps)
        return if (keep.size in 8 until xs.size) {
            fitCore(
                DoubleArray(keep.size) { xs[keep[it]] },
                DoubleArray(keep.size) { ys[keep[it]] },
                hint, measurementSigma, attenuationPrior, fgFloor
            )
        } else fitCore(xs, ys, hint, measurementSigma, attenuationPrior, fgFloor)
    }

    private fun fitCore(
        xs: DoubleArray,
        ys: DoubleArray,
        hint: BiphasicHint?,
        measurementSigma: Double?,
        attenuationPrior: AttenuationFit.AttenuationPrior?,
        fgFloor: Double?
    ): Result? {
        require(xs.size == ys.size)
        val n = xs.size
        // Floor at 8 (one more than free params) so degenerate fits don't
        // get fed in. Realistically the model needs ≥ 30 to identify both
        // phases — that gate is in [AttenuationModelSelector], not here.
        if (n < 8) return null
        val xSpan = xs.last() - xs.first()
        val yMin = ys.min(); val yMax = ys.max()
        if (yMax - yMin < 1e-4 || xSpan <= 0.0) return null

        val ogInit = yMax
        val fgInit = max(0.990, 1.000 + 0.20 * (ogInit - 1.000))
        val totalDrop = ogInit - fgInit
        // If we know the diauxic plateau, partition the drop by SG: phase 1
        // does the bit above the plateau, phase 2 the bit below. The
        // inflection guesses bracket the pause so phase 1 has already
        // attenuated most of its share by the time the plateau starts and
        // phase 2 kicks in just after it ends.
        val (alphaInit, lam1Init, lam2Init, drop1Init, drop2Init) =
            if (hint != null && totalDrop > 1e-4) {
                val a = ((ogInit - hint.pauseSg) / totalDrop).coerceIn(0.10, 0.90)
                Quintuple(
                    a,
                    hint.pauseStartH * 0.5,
                    hint.pauseEndH + xSpan * 0.05,
                    a * totalDrop,
                    (1.0 - a) * totalDrop
                )
            } else {
                val a = 0.65
                Quintuple(a, xs.first() + 0.10 * xSpan, xs.first() + 0.50 * xSpan,
                    a * totalDrop, (1.0 - a) * totalDrop)
            }
        // Peak rate ≈ 2·A_phase / lag_to_inflection — the Gompertz peak
        // sits well above the average rate (peak = drop·e/τ where τ is the
        // characteristic time, not the lag itself). Underestimating μ here
        // is what lets the LM dwell at an over-flat phase 1 and absorb the
        // actual descent into phase 2 — a degenerate local minimum the
        // damping schedule won't climb back out of.
        val tau1 = max(lam1Init * 2.0 - xs.first(), 1.0)
        val tau2 = max((xs.last() - lam2Init) * 2.0, 1.0)
        val mu1Init = max(2.0 * drop1Init / tau1, 1e-4)
        val mu2Init = max(2.0 * drop2Init / tau2, 1e-5)

        // Marquardt scaling: J^T J on its diagonal is the natural rescaling
        // for parameters spanning ~6 orders of magnitude (μ ≈ 1e-3,
        // λ ≈ 1e1, α ≈ 1). We damp the diagonal additively, scaled by its
        // current magnitude — the original Marquardt update, not the cheap
        // (1+damping) multiplier the single-component fitter uses. Without
        // this the smaller-scale partials' contribution to the linear solve
        // gets swamped and steps for μ/α stall while OG/FG/λ still move.
        var p = doubleArrayOf(ogInit, fgInit, alphaInit, mu1Init, lam1Init, mu2Init, lam2Init)
        applyBounds(p, ogInit, xs.first(), xs.last(), attenuationPrior, fgFloor)
        var damping = 1e-3
        val maxIter = 400
        var prevRss = totalRss(xs, ys, p, attenuationPrior)

        for (iter in 0 until maxIter) {
            val (jtj, jtr) = jacobianBlocks(xs, ys, p, attenuationPrior)
            // Add damping × diag(J^T J) — Marquardt's original. Each
            // diagonal entry is scaled by its own size, so parameters with
            // small partials (μ, often α) get a proportionally smaller
            // damping floor and can still move when the larger-scale ones
            // are converging.
            for (i in 0 until N_PARAMS) jtj[i][i] += damping * jtj[i][i]
            val delta = solveN(jtj, jtr) ?: break
            val candidate = DoubleArray(N_PARAMS) { p[it] + delta[it] }
            applyBounds(candidate, ogInit, xs.first(), xs.last(), attenuationPrior, fgFloor)
            val newRss = totalRss(xs, ys, candidate, attenuationPrior)
            if (newRss < prevRss) {
                p = candidate
                damping = max(damping * 0.5, 1e-9)
                if (abs(prevRss - newRss) < 1e-15) break
                prevRss = newRss
            } else {
                damping = min(damping * 4.0, 1e9)
                if (damping > 1e8) break
            }
        }

        // Canonical ordering: phase 1 is the earlier inflection. Swap if
        // the LM converged with phases the other way round; this preserves
        // the model output but stabilises diagnostics and tests.
        if (p[LAM1] > p[LAM2]) {
            val swappedAlpha = 1.0 - p[ALPHA]
            val tmpMu = p[MU1]; val tmpLam = p[LAM1]
            p[MU1] = p[MU2]; p[LAM1] = p[LAM2]
            p[MU2] = tmpMu; p[LAM2] = tmpLam
            p[ALPHA] = swappedAlpha
        }

        val finalRss = dataRss(xs, ys, p)
        if (!finalRss.isFinite()) return null
        if (p[MU1] <= 0.0 || p[MU2] <= 0.0 || p[OG] - p[FG] <= 0.0) return null
        if (p[ALPHA] !in 0.01..0.99) return null

        val sigma = measurementSigma ?: if (n > N_PARAMS) sqrt(finalRss / (n - N_PARAMS)) else null
        val cov: Array<DoubleArray>? = if (sigma != null && n >= N_PARAMS + 1) {
            val (jtjClean, _) = jacobianBlocks(xs, ys, p, attenuationPrior)
            invertN(jtjClean)?.let { inv ->
                Array(N_PARAMS) { i -> DoubleArray(N_PARAMS) { j -> inv[i][j] * sigma * sigma } }
            }
        } else null
        val fgSigma = cov?.get(FG)?.get(FG)?.let { v -> if (v > 0.0) sqrt(v) else null }

        return Result(
            og = p[OG], fg = p[FG], alpha = p[ALPHA],
            mu1 = p[MU1], lambda1 = p[LAM1], mu2 = p[MU2], lambda2 = p[LAM2],
            rmsResidual = sqrt(finalRss / n),
            pointCount = n, fgSigma = fgSigma, covariance = cov
        )
    }

    /**
     * Evaluate the two-component model at time [t]. Phase contributions
     * are gated by their amplitudes so a vanishing phase (α at a bound)
     * doesn't introduce 0·∞ NaN.
     */
    private fun predictAt(
        og: Double, fg: Double, alpha: Double,
        mu1: Double, lam1: Double, mu2: Double, lam2: Double, t: Double
    ): Double {
        val a = og - fg
        if (a <= 0.0) return og
        val a1 = alpha * a
        val a2 = (1.0 - alpha) * a
        return og - phaseContribution(a1, mu1, lam1, t) - phaseContribution(a2, mu2, lam2, t)
    }

    private fun phaseContribution(aPhase: Double, mu: Double, lam: Double, t: Double): Double {
        if (aPhase <= 1e-9 || mu <= 0.0) return 0.0
        val arg = mu * E / aPhase * (lam - t) + 1.0
        return when {
            arg > 30.0 -> 0.0
            arg < -700.0 -> aPhase
            else -> aPhase * exp(-exp(arg))
        }
    }

    /** Stable u·v = exp(arg − exp(arg)). */
    private fun uvOf(arg: Double): Double = when {
        arg > 30.0 -> 0.0
        arg < -700.0 -> 0.0
        else -> exp(arg - exp(arg))
    }

    /**
     * Time at which the two-component curve reaches [target]. Solved by
     * bisection across [lambda1 - span, lambda2 + 4·span] — the model
     * isn't analytically invertible like the single Gompertz, but it's
     * monotonically decreasing in t so bisection always finds the root.
     */
    private fun inverseAt(
        og: Double, fg: Double, alpha: Double,
        mu1: Double, lam1: Double, mu2: Double, lam2: Double, target: Double
    ): Double? {
        if (og - fg <= 0.0 || target >= og || target <= fg) return null
        var tLo = lam1 - 50.0
        var tHi = lam2 + 200.0
        val yLo = predictAt(og, fg, alpha, mu1, lam1, mu2, lam2, tLo)
        val yHi = predictAt(og, fg, alpha, mu1, lam1, mu2, lam2, tHi)
        if (yLo < target || yHi > target) return null
        repeat(80) {
            val mid = 0.5 * (tLo + tHi)
            val yMid = predictAt(og, fg, alpha, mu1, lam1, mu2, lam2, mid)
            if (yMid > target) tLo = mid else tHi = mid
            if (tHi - tLo < 1e-3) return 0.5 * (tLo + tHi)
        }
        return 0.5 * (tLo + tHi)
    }

    // ---------------------------------------------------------------------
    //  Loss + Jacobian
    // ---------------------------------------------------------------------

    private fun dataRss(xs: DoubleArray, ys: DoubleArray, p: DoubleArray): Double {
        var s = 0.0
        for (i in xs.indices) {
            val r = ys[i] - predictAt(p[OG], p[FG], p[ALPHA], p[MU1], p[LAM1], p[MU2], p[LAM2], xs[i])
            s += r * r
        }
        return s
    }

    private fun totalRss(
        xs: DoubleArray, ys: DoubleArray, p: DoubleArray,
        prior: AttenuationFit.AttenuationPrior?
    ): Double {
        var s = dataRss(xs, ys, p)
        if (prior != null) {
            val og = p[OG]; val fg = p[FG]
            val denom = max(og - 1.000, 1e-9)
            val atten = (og - fg) / denom
            val rp = (prior.mean - atten) / prior.sigma
            s += rp * rp
        }
        return s
    }

    /**
     * Analytic Jacobian. Partials of SG(t) w.r.t. (OG, FG, α, μ₁, λ₁, μ₂, λ₂):
     *
     *     A_i        = α·A or (1−α)·A
     *     v_i        = exp(−exp(arg_i)),    arg_i = (μ_i·e/A_i)·(λ_i−t) + 1
     *     (uv)_i     = exp(arg_i − exp(arg_i))     [stable form of u_i·v_i]
     *     φ_i        = (uv)_i · μ_i·e·(λ_i−t) / A_i
     *
     *     ∂SG/∂OG   = 1 − α·(v₁ + φ₁) − (1−α)·(v₂ + φ₂)
     *     ∂SG/∂FG   =     α·(v₁ + φ₁) + (1−α)·(v₂ + φ₂)
     *     ∂SG/∂α   = A·[(v₂ + φ₂) − (v₁ + φ₁)]
     *     ∂SG/∂μ_i = (uv)_i · e · (λ_i − t)
     *     ∂SG/∂λ_i = (uv)_i · μ_i · e
     *
     * Prior row mirrors the single-component fitter — partials of the
     * attenuation pseudo-residual w.r.t. OG and FG, zeros elsewhere.
     */
    private fun jacobianBlocks(
        xs: DoubleArray, ys: DoubleArray, p: DoubleArray,
        prior: AttenuationFit.AttenuationPrior?
    ): Pair<Array<DoubleArray>, DoubleArray> {
        val og = p[OG]; val fg = p[FG]; val alpha = p[ALPHA]
        val mu1 = p[MU1]; val lam1 = p[LAM1]; val mu2 = p[MU2]; val lam2 = p[LAM2]
        val a = og - fg
        val jtj = Array(N_PARAMS) { DoubleArray(N_PARAMS) }
        val jtr = DoubleArray(N_PARAMS)
        if (a <= 0.0) {
            for (i in 0 until N_PARAMS) jtj[i][i] = 1.0
            return jtj to jtr
        }
        val a1 = alpha * a
        val a2 = (1.0 - alpha) * a

        for (i in xs.indices) {
            val t = xs[i]
            // Phase 1
            val (v1, uv1, j_mu1, j_lam1, vplus1) = phasePartials(a1, mu1, lam1, t)
            // Phase 2
            val (v2, uv2, j_mu2, j_lam2, vplus2) = phasePartials(a2, mu2, lam2, t)
            val pred = og - a1 * v1 - a2 * v2
            val r = ys[i] - pred

            val jOg    = 1.0 - alpha * vplus1 - (1.0 - alpha) * vplus2
            val jFg    = alpha * vplus1 + (1.0 - alpha) * vplus2
            val jAlpha = a * (vplus2 - vplus1)

            val js = doubleArrayOf(jOg, jFg, jAlpha, j_mu1, j_lam1, j_mu2, j_lam2)
            for (aIdx in 0 until N_PARAMS) {
                jtr[aIdx] += js[aIdx] * r
                for (bIdx in 0 until N_PARAMS) jtj[aIdx][bIdx] += js[aIdx] * js[bIdx]
            }
            // Suppress unused-variable warnings for the v's themselves —
            // they're used inside phasePartials for vplus; the destructure
            // keeps the formula readable in case we ever inspect them.
            @Suppress("UNUSED_VARIABLE") val _v1u = uv1
            @Suppress("UNUSED_VARIABLE") val _v2u = uv2
        }
        if (prior != null) {
            // Augmented row: treat atten = (OG−FG)/(OG−1) as a pseudo-
            // observation against prior.mean, scaled by prior.sigma so it
            // weighs in commensurately with one data point. Partials:
            //   ∂atten/∂OG  = (FG − 1) / (OG − 1)²
            //   ∂atten/∂FG  = −1 / (OG − 1)
            // The Jacobian row is ∂(atten/σ)/∂p. The sign of ∂atten/∂FG is
            // negative (raising FG lowers attenuation) — the FG partial
            // needs that sign, otherwise the LM step pushes FG the wrong
            // way and rejects every iteration once the prior contribution
            // out-weighs the data (which happens here with 7 free params).
            val denom = max(og - 1.000, 1e-9)
            val atten = (og - fg) / denom
            val r = (prior.mean - atten) / prior.sigma
            val dOg = ((fg - 1.0) / (denom * denom)) / prior.sigma
            val dFg = (-1.0 / denom) / prior.sigma
            val js = doubleArrayOf(dOg, dFg, 0.0, 0.0, 0.0, 0.0, 0.0)
            for (aIdx in 0 until N_PARAMS) {
                jtr[aIdx] += js[aIdx] * r
                for (bIdx in 0 until N_PARAMS) jtj[aIdx][bIdx] += js[aIdx] * js[bIdx]
            }
        }
        return jtj to jtr
    }

    /**
     * Per-phase precomputed quantities: (v, uv, ∂SG/∂μ, ∂SG/∂λ, v+φ).
     * v+φ is what hits the OG/FG/α partials; the φ term arises from A_phase
     * sitting in the denominator of arg.
     */
    private data class PhasePartials(
        val v: Double, val uv: Double,
        val j_mu: Double, val j_lam: Double,
        val vPlus: Double
    )

    private fun phasePartials(aPhase: Double, mu: Double, lam: Double, t: Double): PhasePartials {
        if (aPhase <= 1e-9 || mu <= 0.0) {
            return PhasePartials(0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val dt = lam - t
        val arg = mu * E / aPhase * dt + 1.0
        val v = when {
            arg > 30.0 -> 0.0
            arg < -700.0 -> 1.0
            else -> exp(-exp(arg))
        }
        val uv = uvOf(arg)
        val phi = uv * mu * E * dt / aPhase
        val j_mu = uv * E * dt
        val j_lam = uv * mu * E
        return PhasePartials(v, uv, j_mu, j_lam, v + phi)
    }

    // ---------------------------------------------------------------------
    //  Bounds + linear algebra
    // ---------------------------------------------------------------------

    private fun applyBounds(
        p: DoubleArray, ogPrior: Double, xMin: Double, xMax: Double,
        attenuationPrior: AttenuationFit.AttenuationPrior?, externalFgFloor: Double?
    ) {
        // OG is the asymptote at t → −∞, not the observed maximum: a brew
        // with a noisy lag plateau can have observed-max sit a few mSG
        // above the model's true asymptote. The single-component fitter
        // narrowed the lower bound at -0.001 because its 4-parameter
        // family can't represent the lag distinctly; the two-component
        // fitter benefits from a wider downward window so phase 1 can
        // place its asymptote correctly without being clamped to the
        // observed lag SG. Same +0.005 ceiling — OG can't reasonably be
        // higher than observed max.
        p[OG] = p[OG].coerceIn(ogPrior - 0.005, ogPrior + 0.005)
        val priorFloor = max(0.990, 1.000 + 0.10 * (p[OG] - 1.000))
        val fgFloor = if (externalFgFloor != null) max(priorFloor, externalFgFloor) else priorFloor
        val fgCeiling = min(p[OG] - 0.001, 1.000 + 0.50 * (p[OG] - 1.000))
        p[FG] = p[FG].coerceIn(fgFloor, max(fgFloor, fgCeiling))
        // α: phase-split fraction. Hard-bounded inside (0, 1); the
        // 0.05 / 0.95 floors are wide enough that a real biphasic ferment
        // sits well inside them and narrow enough that landing at one
        // means the data didn't actually need the second phase — the model
        // selector should have caught that.
        p[ALPHA] = p[ALPHA].coerceIn(0.05, 0.95)
        // μ floors: same as single-component (1e-5 .. 0.05 SG/h peak).
        p[MU1] = p[MU1].coerceIn(1e-5, 0.05)
        p[MU2] = p[MU2].coerceIn(1e-5, 0.05)
        // λ bounds segregate the phases by time. Phase 1's inflection
        // belongs in the first 70 % of the captured span (a real biphasic
        // brew never has the easy-sugars phase peaking near the tail);
        // phase 2's inflection in the last 90 %. Wide enough that the LM
        // can swap them when warranted (still order-canonicalised post-fit),
        // tight enough to keep both phases inside the data the model is
        // claiming to explain.
        val span = xMax - xMin
        p[LAM1] = p[LAM1].coerceIn(xMin - 0.1 * span, xMin + 0.7 * span)
        p[LAM2] = p[LAM2].coerceIn(xMin + 0.1 * span, xMax + 0.2 * span)
        @Suppress("UNUSED_PARAMETER") attenuationPrior
    }

    /** Helper: a 5-tuple for unpacking the initial-guess block. */
    private data class Quintuple<A, B, C, D, E>(
        val first: A, val second: B, val third: C, val fourth: D, val fifth: E
    )

    /**
     * N×N Gaussian elimination with partial pivoting. Returns null on a
     * singular matrix. Same algorithm as the 4×4 variant in
     * [AttenuationFit] but dimensioned for the 7-parameter Jacobian.
     */
    private fun solveN(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = a.size
        val m = Array(n) { DoubleArray(n + 1) }
        for (i in 0 until n) {
            for (j in 0 until n) m[i][j] = a[i][j]
            m[i][n] = b[i]
        }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-18) return null
            if (pivot != col) { val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp }
            val pv = m[col][col]
            for (j in col..n) m[col][j] /= pv
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (j in col..n) m[r][j] -= f * m[col][j]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }

    /** N×N inverse via Gauss-Jordan with partial pivoting. */
    private fun invertN(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = Array(n) { i -> DoubleArray(2 * n).also {
            for (j in 0 until n) it[j] = a[i][j]
            it[n + i] = 1.0
        } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-18) return null
            if (pivot != col) { val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp }
            val pv = m[col][col]
            for (j in 0 until 2 * n) m[col][j] /= pv
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (j in 0 until 2 * n) m[r][j] -= f * m[col][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> m[i][n + j] } }
    }

    /** N×N Cholesky for SPD matrices. */
    private fun choleskyN(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (kk in 0 until j) s -= l[i][kk] * l[j][kk]
                if (i == j) {
                    if (s <= 0.0) return null
                    l[i][i] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        return l
    }

    private fun boxMuller(rng: Random): Double {
        val u1 = max(rng.nextDouble(), 1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
