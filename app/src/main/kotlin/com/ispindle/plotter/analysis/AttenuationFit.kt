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
 * 4-parameter modified Gompertz attenuation model:
 *
 *     SG(t) = OG − A · exp(−exp((μ_max·e/A)·(λ−t) + 1))
 *     where  A = OG − FG,  e = 2.71828…
 *
 * The Gompertz form is asymmetric — long flat top at OG followed by a
 * sharp descent and a slow asymptotic tail toward FG — which matches
 * real beer fermentation more faithfully than a symmetric logistic.
 *
 * | name    | meaning                                                       |
 * |---------|---------------------------------------------------------------|
 * | og      | starting gravity (asymptote at t → -∞)                        |
 * | fg      | final gravity (asymptote at t → +∞)                           |
 * | muMax   | peak |dSG/dt| (SG units / hour). Tangent slope at the         |
 * |         | inflection.                                                   |
 * | lambda  | lag time (hours). x-intercept of that tangent — the time      |
 * |         | at which the linear extrapolation of the active descent       |
 * |         | crosses OG. Only ~6.6 % of the total drop A has happened by   |
 * |         | t = λ; the inflection sits at t* = λ + A/(μ_max·e).           |
 *
 * Reference:
 *
 *   Zwietering, M. H., Jongenburger, I., Rombouts, F. M., & van 't Riet, K.
 *   (1990). *Modeling of the bacterial growth curve.* Applied and
 *   Environmental Microbiology, 56(6), 1875–1881.
 *
 * Fit by Levenberg-Marquardt with a Bayesian MAP twist: a Gaussian prior
 * on attenuation `(OG−FG)/(OG−1)` (`AttenuationPrior`) is included as an
 * augmented residual, so when the data are uninformative about FG (mid-
 * active phase, no asymptote yet) the LM is pulled toward typical
 * attenuation rather than snug-fitting FG against the recent data tail.
 *
 * The converged solution carries a Laplace approximation of the
 * posterior: `θ ~ N(θ_MAP, Σ)` with `Σ = (J^T J)^-1 · σ²`. Use
 * `Result.sample(rng)` and `Result.predictiveBand(...)` to draw credible
 * intervals on the model curve and on time-to-target.
 */
object AttenuationFit {

    /**
     * Short citation string suitable for showing in the UI under the SG
     * chart so users can find the model in the literature.
     */
    const val ReferenceCitation: String =
        "Modified Gompertz · Zwietering et al. 1990 (Appl. Environ. Microbiol. 56:1875)"

    /**
     * Gaussian prior on attenuation `a = (OG - FG) / (OG - 1)`.
     *
     * Default `mean = 0.75, sigma = 0.10` covers the typical range of
     * mainstream beer (60–90 %) at 1.5σ. Pass `null` to fit() to disable
     * the prior entirely (pure data MLE).
     */
    data class AttenuationPrior(
        val mean: Double = 0.75,
        val sigma: Double = 0.10
    )

    val DefaultAttenuationPrior = AttenuationPrior()

    data class Result(
        val og: Double,
        val fg: Double,
        /** Peak |dSG/dt|, SG units / hour. Always positive. */
        val muMax: Double,
        /** Lag time in hours. */
        val lambda: Double,
        val rmsResidual: Double,
        val pointCount: Int,
        /** 1σ uncertainty on FG from the LM covariance. Null when not computed. */
        val fgSigma: Double? = null,
        /**
         * 4×4 parameter covariance from the Laplace approximation, in
         * order [og, fg, muMax, lambda]. Used by [sample] / [predictiveBand] /
         * [etaQuantiles]. Null when n < 5 or the Hessian was singular.
         */
        val covariance: Array<DoubleArray>? = null
    ) {
        fun predict(t: Double): Double = predictAt(og, fg, muMax, lambda, t)

        /** Time at which the curve reaches `target`. Null if unreachable. */
        fun timeToReach(target: Double): Double? =
            inverseAt(og, fg, muMax, lambda, target)

        /** Slope dSG/dt at time t. Negative during descent. */
        fun rateAt(t: Double): Double {
            val a = og - fg
            if (a <= 0.0 || muMax <= 0.0) return 0.0
            val arg = muMax * E / a * (lambda - t) + 1.0
            return -uvOf(arg) * muMax * E
        }

        /**
         * Draw one parameter sample `[og, fg, muMax, lambda]` from the
         * Laplace approximation. Returns the MAP (no spread) when
         * [covariance] is null.
         */
        fun sample(rng: Random): DoubleArray {
            val cov = covariance
            val mean = doubleArrayOf(og, fg, muMax, lambda)
            if (cov == null) return mean
            val l = cholesky4(cov) ?: return mean
            val z = DoubleArray(4) { boxMuller(rng) }
            val out = mean.copyOf()
            for (i in 0..3) {
                for (j in 0..i) out[i] += l[i][j] * z[j]
            }
            // muMax ≤ 0 or fg ≥ og collapses the model. Snap those to MAP
            // rather than emit nonsense.
            if (out[2] <= 0.0 || out[0] - out[1] <= 0.0) return mean
            return out
        }

        /**
         * Posterior credible band on the predicted SG curve at the given
         * times. Returns one DoubleArray per quantile, indexed by time.
         */
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
                DoubleArray(times.size) { i -> predictAt(s[0], s[1], s[2], s[3], times[i]) }
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

        /**
         * Posterior quantiles on the time-to-target. Samples that hit a
         * degenerate parameter draw are dropped.
         */
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
                val t = inverseAt(s[0], s[1], s[2], s[3], target) ?: return@repeat
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
     * Fit modified Gompertz to [xs] (hours from start) / [ys] (SG).
     *
     * @param measurementSigma per-point noise σ. Used to scale the
     *        Laplace covariance; without it, falls back to in-sample
     *        residual std at n - 4 dof.
     * @param attenuationPrior soft Gaussian prior on attenuation; pass
     *        null for a pure data MLE. Default = N(0.75, 0.10²).
     */
    fun fit(
        xs: DoubleArray,
        ys: DoubleArray,
        measurementSigma: Double? = null,
        attenuationPrior: AttenuationPrior? = DefaultAttenuationPrior
    ): Result? {
        require(xs.size == ys.size)
        val n = xs.size
        if (n < 6) return null
        val xSpan = xs.last() - xs.first()
        val yMin = ys.min()
        val yMax = ys.max()
        if (yMax - yMin < 1e-4 || xSpan <= 0.0) return null

        val ogInit = yMax
        val fgInit = max(0.990, 1.000 + 0.25 * (ogInit - 1.000))
        // Average descent rate across the window — peak is ~2× that.
        val avgRate = max((ogInit - ys.last()) / xSpan, 1e-5)
        val muMaxInit = avgRate * 2.0
        val lambdaInit = xs.first() + 0.05 * xSpan

        var p = doubleArrayOf(ogInit, fgInit, muMaxInit, lambdaInit)
        var damping = 1e-3
        val maxIter = 100
        var prevRss = totalRss(xs, ys, p, attenuationPrior)

        for (iter in 0 until maxIter) {
            val (jtj, jtr) = jacobianBlocks(xs, ys, p, attenuationPrior)
            for (i in 0..3) jtj[i][i] *= (1.0 + damping)
            val delta = solve4x4(jtj, jtr) ?: break

            val candidate = DoubleArray(4) { p[it] + delta[it] }
            applyBounds(candidate, ogInit, xs.first(), xs.last())
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

        val finalRss = dataRss(xs, ys, p)
        if (!finalRss.isFinite()) return null
        val og = p[0]; val fg = p[1]; val muMax = p[2]; val lambda = p[3]
        if (muMax <= 0.0 || og - fg <= 0.0) return null

        val sigma = measurementSigma ?: if (n > 4) sqrt(finalRss / (n - 4)) else null
        val cov: Array<DoubleArray>? = if (sigma != null && n >= 5) {
            val (jtjClean, _) = jacobianBlocks(xs, ys, p, attenuationPrior)
            val inv = invert4x4(jtjClean)
            inv?.let { mat ->
                Array(4) { i ->
                    DoubleArray(4) { j -> mat[i][j] * sigma * sigma }
                }
            }
        } else null
        val fgSigma = cov?.get(1)?.get(1)?.let { v -> if (v > 0.0) sqrt(v) else null }

        return Result(og, fg, muMax, lambda, sqrt(finalRss / n), n, fgSigma, cov)
    }

    /** Modified Gompertz curve evaluated at t. */
    private fun predictAt(
        og: Double, fg: Double, muMax: Double, lambda: Double, t: Double
    ): Double {
        val a = og - fg
        if (a <= 0.0) return og
        val arg = muMax * E / a * (lambda - t) + 1.0
        return when {
            arg > 30.0 -> og               // u → ∞, drop → 0, SG → OG
            arg < -700.0 -> fg             // u → 0, drop → A, SG → FG
            else -> og - a * exp(-exp(arg))
        }
    }

    /**
     * Numerically stable u·v = exp(arg)·exp(−exp(arg)) = exp(arg − exp(arg)).
     * Goes to 0 for both arg → +∞ and arg → -∞; computed via the stable
     * combined form to avoid 0·∞ NaN.
     */
    private fun uvOf(arg: Double): Double = when {
        arg > 30.0 -> 0.0
        arg < -700.0 -> 0.0
        else -> exp(arg - exp(arg))
    }

    private fun inverseAt(
        og: Double, fg: Double, muMax: Double, lambda: Double, target: Double
    ): Double? {
        val a = og - fg
        if (a <= 0.0 || muMax <= 0.0) return null
        if (target >= og || target <= fg) return null
        val ratio = a / (og - target)
        if (ratio <= 1.0) return null
        val inner = ln(ratio)
        if (inner <= 0.0 || !inner.isFinite()) return null
        val arg = ln(inner)
        if (!arg.isFinite()) return null
        return lambda + (1.0 - arg) * a / (muMax * E)
    }

    private fun dataRss(xs: DoubleArray, ys: DoubleArray, p: DoubleArray): Double {
        var s = 0.0
        for (i in xs.indices) {
            val r = ys[i] - predictAt(p[0], p[1], p[2], p[3], xs[i])
            s += r * r
        }
        return s
    }

    /**
     * Augmented loss: data RSS + (prior residual)². Treated as one extra
     * synthetic observation for LM purposes.
     */
    private fun totalRss(
        xs: DoubleArray, ys: DoubleArray, p: DoubleArray, prior: AttenuationPrior?
    ): Double {
        var s = dataRss(xs, ys, p)
        if (prior != null) {
            val og = p[0]; val fg = p[1]
            val denom = max(og - 1.000, 1e-9)
            val atten = (og - fg) / denom
            val rp = (prior.mean - atten) / prior.sigma
            s += rp * rp
        }
        return s
    }

    /**
     * Returns (J^T J, J^T r). Partials of the model
     *     y = OG − A·v          v = exp(−u),  u = exp(arg)
     *     arg = (μ·e/A)·(λ−t) + 1,   A = OG − FG
     * are
     *     ∂y/∂OG     = 1 − v − (uv)·μ·e·(λ−t)/A
     *     ∂y/∂FG     = v + (uv)·μ·e·(λ−t)/A
     *     ∂y/∂μ      = (uv)·e·(λ−t)
     *     ∂y/∂λ      = (uv)·μ·e
     *
     * When [prior] is non-null, the attenuation residual contributes an
     * extra row with partials d(atten)/d(og) and d(atten)/d(fg) only.
     */
    private fun jacobianBlocks(
        xs: DoubleArray, ys: DoubleArray, p: DoubleArray, prior: AttenuationPrior?
    ): Pair<Array<DoubleArray>, DoubleArray> {
        val og = p[0]; val fg = p[1]; val muMax = p[2]; val lambda = p[3]
        val a = og - fg
        val jtj = Array(4) { DoubleArray(4) }
        val jtr = DoubleArray(4)
        if (a <= 0.0) {
            for (i in 0..3) jtj[i][i] = 1.0
            return jtj to jtr
        }
        for (i in xs.indices) {
            val dt = lambda - xs[i]
            val arg = muMax * E / a * dt + 1.0
            val v = when {
                arg > 30.0 -> 0.0
                arg < -700.0 -> 1.0
                else -> exp(-exp(arg))
            }
            val uv = uvOf(arg)
            val pred = og - a * v
            val r = ys[i] - pred
            val term = uv * muMax * E * dt / a
            val j0 = 1.0 - v - term
            val j1 = v + term
            val j2 = uv * E * dt
            val j3 = uv * muMax * E
            val js = doubleArrayOf(j0, j1, j2, j3)
            for (aIdx in 0..3) {
                jtr[aIdx] += js[aIdx] * r
                for (bIdx in 0..3) jtj[aIdx][bIdx] += js[aIdx] * js[bIdx]
            }
        }
        if (prior != null) {
            val denom = max(og - 1.000, 1e-9)
            val atten = (og - fg) / denom
            val r = (prior.mean - atten) / prior.sigma
            val dOg = -((1.0 - fg) / (denom * denom)) / prior.sigma
            val dFg = -(-1.0 / denom) / prior.sigma
            val js = doubleArrayOf(dOg, dFg, 0.0, 0.0)
            for (aIdx in 0..3) {
                jtr[aIdx] += js[aIdx] * r
                for (bIdx in 0..3) jtj[aIdx][bIdx] += js[aIdx] * js[bIdx]
            }
        }
        return jtj to jtr
    }

    private fun applyBounds(p: DoubleArray, ogPrior: Double, xMin: Double, xMax: Double) {
        // OG within ±0.005 of the observed maximum.
        p[0] = p[0].coerceIn(ogPrior - 0.001, ogPrior + 0.005)
        // Wide FG sanity walls — the AttenuationPrior does the primary
        // regularising work. Floor at 90 %-attenuation max stops a sparse
        // dataset from imploding to FG ≪ 1; ceiling at 50 %-atten stops
        // FG → OG when the LM hasn't seen enough data to constrain FG.
        val fgFloor = max(0.990, 1.000 + 0.10 * (p[0] - 1.000))
        val fgCeiling = min(p[0] - 0.001, 1.000 + 0.50 * (p[0] - 1.000))
        p[1] = p[1].coerceIn(fgFloor, max(fgFloor, fgCeiling))
        // muMax: peak attenuation rate. Beer ferments at 0.0001-0.01 SG/h
        // peak, with extreme outliers up to ~0.02. Floor at 1e-5.
        p[2] = p[2].coerceIn(1e-5, 0.05)
        // lambda: lag time. Anchored to data window with modest slack
        // either side (data captured into ferment, or still in lag).
        val span = xMax - xMin
        p[3] = p[3].coerceIn(xMin - 0.5 * span, xMax + 0.5 * span)
    }

    /**
     * 4×4 inverse via Gauss-Jordan elimination with partial pivoting.
     * Used to read the parameter covariance entries off (J^T J)^-1.
     */
    private fun invert4x4(a: Array<DoubleArray>): Array<DoubleArray>? {
        val m = Array(4) { i -> DoubleArray(8).also {
            for (j in 0..3) it[j] = a[i][j]
            it[4 + i] = 1.0
        } }
        for (col in 0..3) {
            var pivot = col
            for (r in col + 1..3) {
                if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            }
            if (abs(m[pivot][col]) < 1e-18) return null
            if (pivot != col) { val t = m[col]; m[col] = m[pivot]; m[pivot] = t }
            val pv = m[col][col]
            for (j in 0..7) m[col][j] /= pv
            for (r in 0..3) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (j in 0..7) m[r][j] -= f * m[col][j]
            }
        }
        return Array(4) { i -> DoubleArray(4) { j -> m[i][4 + j] } }
    }

    /** 4×4 linear solve via Gaussian elimination with partial pivoting. */
    private fun solve4x4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(4) { DoubleArray(5) }
        for (i in 0..3) {
            for (j in 0..3) m[i][j] = a[i][j]
            m[i][4] = b[i]
        }
        for (col in 0..3) {
            var pivot = col
            for (r in col + 1..3) {
                if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            }
            if (abs(m[pivot][col]) < 1e-18) return null
            if (pivot != col) { val t = m[col]; m[col] = m[pivot]; m[pivot] = t }
            val pv = m[col][col]
            for (j in col..4) m[col][j] /= pv
            for (r in 0..3) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (j in col..4) m[r][j] -= f * m[col][j]
            }
        }
        return DoubleArray(4) { m[it][4] }
    }

    /**
     * Cholesky factor `L` such that `L · L^T = A` for SPD 4×4 `A`. Used
     * to draw multivariate Gaussian samples from the Laplace posterior.
     * Returns null if `A` is not positive definite.
     */
    private fun cholesky4(a: Array<DoubleArray>): Array<DoubleArray>? {
        val l = Array(4) { DoubleArray(4) }
        for (i in 0..3) {
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

    /** Box-Muller standard normal. */
    private fun boxMuller(rng: Random): Double {
        val u1 = max(rng.nextDouble(), 1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
