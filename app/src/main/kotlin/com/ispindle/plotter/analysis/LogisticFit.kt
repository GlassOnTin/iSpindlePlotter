package com.ispindle.plotter.analysis

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 4-parameter logistic model
 *
 *     SG(t) = FG + (OG - FG) / (1 + exp(k * (t - tMid)))
 *
 * fits the classic fermentation profile — flat lag phase, S-curve descent
 * through the active phase, asymptotic approach to FG. Parameters:
 *
 * | name  | meaning                                              |
 * |-------|------------------------------------------------------|
 * | og    | starting gravity (asymptote at t → -∞)               |
 * | fg    | final gravity (asymptote at t → +∞)                  |
 * | k     | rate constant; larger k = sharper descent            |
 * | tMid  | inflection time, where SG = (og + fg) / 2            |
 *
 * Fit by Levenberg-Marquardt with a Bayesian MAP twist: a Gaussian prior
 * on attenuation (`AttenuationPrior`) is included as an augmented
 * residual, so when the data are uninformative about FG (mid-active
 * phase, no asymptote yet) the LM is pulled toward typical attenuation
 * rather than snug-fitting FG against the recent data tail.
 *
 * The converged solution carries a Laplace approximation of the
 * posterior: `θ ~ N(θ_MAP, Σ)` with `Σ = (J^T J)^-1 · σ²`. Use
 * `Result.sample(rng)` and `Result.predictiveBand(...)` to draw credible
 * intervals on the model curve and on time-to-target.
 */
object LogisticFit {

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
        val k: Double,
        val tMid: Double,
        val rmsResidual: Double,
        val pointCount: Int,
        /** 1σ uncertainty on FG from the LM covariance. Null when not computed. */
        val fgSigma: Double? = null,
        /**
         * 4×4 parameter covariance from the Laplace approximation, in
         * order [og, fg, k, tMid]. Used by [sample] / [predictiveBand] /
         * [etaQuantiles]. Null when n < 5 or the Hessian was singular.
         */
        val covariance: Array<DoubleArray>? = null
    ) {
        fun predict(t: Double): Double = fg + (og - fg) / (1.0 + exp(k * (t - tMid)))

        /** Time at which the curve reaches `target`. Null if unreachable. */
        fun timeToReach(target: Double): Double? {
            if (k <= 0.0) return null
            val span = og - fg
            if (span <= 0.0) return null
            val ratio = (og - target) / (target - fg)
            if (ratio <= 0.0 || !ratio.isFinite()) return null
            return tMid + ln(ratio) / k
        }

        /** Slope dSG/dt at time t. Useful for "current rate" displays. */
        fun rateAt(t: Double): Double {
            val s = 1.0 / (1.0 + exp(k * (t - tMid)))
            return -(og - fg) * k * s * (1.0 - s)
        }

        /**
         * Draw one parameter sample `[og, fg, k, tMid]` from the Laplace
         * approximation of the posterior. Returns the MAP point (no
         * spread) when [covariance] is null.
         */
        fun sample(rng: Random): DoubleArray {
            val cov = covariance
            val mean = doubleArrayOf(og, fg, k, tMid)
            if (cov == null) return mean
            val l = cholesky4(cov) ?: return mean
            val z = DoubleArray(4) { boxMuller(rng) }
            val out = mean.copyOf()
            for (i in 0..3) {
                for (j in 0..i) out[i] += l[i][j] * z[j]
            }
            // Sanity guard: a draw with k <= 0 or fg >= og degenerates the
            // logistic. Snap those to the MAP rather than emit nonsense.
            if (out[2] <= 0.0 || out[0] - out[1] <= 0.0) return mean
            return out
        }

        /**
         * Posterior credible band on the predicted SG curve at the given
         * times. Returns one DoubleArray per quantile, indexed by time.
         *
         * @param times sample grid (in the same units as `xs` to fit).
         * @param rng random source.
         * @param nSamples Monte Carlo samples. 256 gives smooth bands.
         * @param quantiles e.g. [0.025, 0.5, 0.975] for the median + 95 %
         *                  band. Order in result matches order in input.
         */
        fun predictiveBand(
            times: DoubleArray,
            rng: Random,
            nSamples: Int = 256,
            quantiles: DoubleArray = doubleArrayOf(0.025, 0.5, 0.975)
        ): Array<DoubleArray> {
            val cov = covariance
            // No covariance → no band. Return the MAP curve for every quantile.
            if (cov == null) {
                val mapCurve = DoubleArray(times.size) { predict(times[it]) }
                return Array(quantiles.size) { mapCurve.copyOf() }
            }
            val curves = Array(nSamples) {
                val s = sample(rng)
                DoubleArray(times.size) { i ->
                    s[1] + (s[0] - s[1]) / (1.0 + exp(s[2] * (times[i] - s[3])))
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

        /**
         * Posterior quantiles on the time-to-target. Samples that hit a
         * degenerate (k ≤ 0 or fg ≥ target) parameter draw are dropped;
         * with the AttenuationPrior in place this is rare but possible
         * for samples drawn far from the MAP.
         */
        fun etaQuantiles(
            target: Double,
            rng: Random,
            nSamples: Int = 256,
            quantiles: DoubleArray = doubleArrayOf(0.025, 0.5, 0.975)
        ): DoubleArray? {
            val cov = covariance ?: return null
            val draws = ArrayList<Double>(nSamples)
            repeat(nSamples) {
                val s = sample(rng)
                val sOg = s[0]; val sFg = s[1]; val sK = s[2]; val sTm = s[3]
                if (sK <= 0.0 || sOg - sFg <= 0.0) return@repeat
                val ratio = (sOg - target) / (target - sFg)
                if (ratio <= 0.0 || !ratio.isFinite()) return@repeat
                val t = sTm + ln(ratio) / sK
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
     * Fit logistic to [xs] (hours from start) / [ys] (SG).
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
        val tMidInit = xs.first() + 0.5 * xSpan
        val kInit = 4.0 / xSpan

        var p = doubleArrayOf(ogInit, fgInit, kInit, tMidInit)
        var lambda = 1e-3
        val maxIter = 100
        var prevRss = totalRss(xs, ys, p, attenuationPrior)

        for (iter in 0 until maxIter) {
            val (jtj, jtr) = jacobianBlocks(xs, ys, p, attenuationPrior)
            for (i in 0..3) jtj[i][i] *= (1.0 + lambda)
            val delta = solve4x4(jtj, jtr) ?: break

            val candidate = DoubleArray(4) { p[it] + delta[it] }
            applyBounds(candidate, ogInit)
            val newRss = totalRss(xs, ys, candidate, attenuationPrior)

            if (newRss < prevRss) {
                p = candidate
                lambda = max(lambda * 0.5, 1e-9)
                if (abs(prevRss - newRss) < 1e-15) break
                prevRss = newRss
            } else {
                lambda = min(lambda * 4.0, 1e9)
                if (lambda > 1e8) break
            }
        }

        val finalRss = dataRss(xs, ys, p)
        if (!finalRss.isFinite()) return null
        val og = p[0]; val fg = p[1]; val k = p[2]; val tMid = p[3]
        if (k <= 0.0 || og - fg <= 0.0) return null

        // Laplace covariance: rebuild J^T J at the converged solution
        // (data + prior contributions, no λ damping), invert, scale by
        // residual variance σ². With AttenuationPrior present, the
        // Hessian's [fg, fg] entry inherits the prior's curvature, so
        // posterior uncertainty automatically tightens when the prior
        // dominates and slackens when the data dominate.
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

        return Result(og, fg, k, tMid, sqrt(finalRss / n), n, fgSigma, cov)
    }

    /** SG = FG + (OG-FG) / (1 + exp(k(t - tMid))). */
    private fun model(p: DoubleArray, t: Double): Double {
        val og = p[0]; val fg = p[1]; val k = p[2]; val tMid = p[3]
        return fg + (og - fg) / (1.0 + exp(k * (t - tMid)))
    }

    /** Sum of squared residuals over the data only. */
    private fun dataRss(xs: DoubleArray, ys: DoubleArray, p: DoubleArray): Double {
        var s = 0.0
        for (i in xs.indices) {
            val r = ys[i] - model(p, xs[i])
            s += r * r
        }
        return s
    }

    /**
     * Augmented loss: data RSS + (prior residual)². The prior residual
     * is `(atten_mu - atten) / atten_sigma`, reflecting a Gaussian prior
     * on attenuation `a = (og - fg) / (og - 1)`. Treated as one extra
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
     * Returns (J^T J, J^T r). For each sample, the partials are
     *   df/dOG   = 1 / (1 + e^(k(t-tMid))) = sigmoid(-k(t-tMid))
     *   df/dFG   = 1 - df/dOG
     *   df/dk    = (OG - FG) * sig * (1-sig) * (-(t - tMid))
     *   df/dtMid = (OG - FG) * sig * (1-sig) * k
     *
     * When [prior] is non-null, the attenuation residual contributes an
     * extra row to the implicit Jacobian, with partials
     *   d(atten)/d(og) =  (1 - fg) / (og - 1)²
     *   d(atten)/d(fg) = -1 / (og - 1)
     *   d(atten)/d(k)  =  d(atten)/d(tMid) = 0
     */
    private fun jacobianBlocks(
        xs: DoubleArray, ys: DoubleArray, p: DoubleArray, prior: AttenuationPrior?
    ): Pair<Array<DoubleArray>, DoubleArray> {
        val og = p[0]; val fg = p[1]; val k = p[2]; val tMid = p[3]
        val span = og - fg
        val jtj = Array(4) { DoubleArray(4) }
        val jtr = DoubleArray(4)
        for (i in xs.indices) {
            val dt = xs[i] - tMid
            val sig = 1.0 / (1.0 + exp(k * dt))
            val sigD = sig * (1.0 - sig)
            val pred = fg + span * sig
            val r = ys[i] - pred
            val j0 = sig
            val j1 = 1.0 - sig
            val j2 = -span * sigD * dt
            val j3 = span * sigD * k
            val js = doubleArrayOf(j0, j1, j2, j3)
            for (a in 0..3) {
                jtr[a] += js[a] * r
                for (b in 0..3) jtj[a][b] += js[a] * js[b]
            }
        }
        if (prior != null) {
            val denom = max(og - 1.000, 1e-9)
            val atten = (og - fg) / denom
            val r = (prior.mean - atten) / prior.sigma
            // Sign convention matches data residuals (r = y_obs - model):
            // here y_obs = atten_mu, model = atten, so partials are -dAtten/dp.
            val dOg = -((1.0 - fg) / (denom * denom)) / prior.sigma
            val dFg = -(-1.0 / denom) / prior.sigma
            val js = doubleArrayOf(dOg, dFg, 0.0, 0.0)
            for (a in 0..3) {
                jtr[a] += js[a] * r
                for (b in 0..3) jtj[a][b] += js[a] * js[b]
            }
        }
        return jtj to jtr
    }

    private fun applyBounds(p: DoubleArray, ogPrior: Double) {
        // OG within ±0.005 of the observed maximum.
        p[0] = p[0].coerceIn(ogPrior - 0.001, ogPrior + 0.005)
        // Wide sanity walls only — the AttenuationPrior does the primary
        // regularising work. Floor at 90 %-attenuation max stops a sparse
        // dataset from imploding to FG ≪ 1 even if the prior somehow
        // doesn't bite. Ceiling at 50 %-attenuation min stops degenerate
        // collapse of FG → OG when the LM hasn't seen enough data to
        // constrain FG at all. Both are loose enough that on real ferments
        // the prior, not the bound, sets the answer.
        val fgFloor = max(0.990, 1.000 + 0.10 * (p[0] - 1.000))
        val fgCeiling = min(p[0] - 0.001, 1.000 + 0.50 * (p[0] - 1.000))
        p[1] = p[1].coerceIn(fgFloor, max(fgFloor, fgCeiling))
        p[2] = p[2].coerceIn(1e-4, 100.0)
        p[3] = p[3].coerceIn(-1000.0, 1e6)
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
     * Cholesky factor `L` such that `L · L^T = A`, for symmetric positive
     * definite 4×4 `A`. Used to draw multivariate Gaussian samples from
     * the Laplace posterior. Returns null if `A` is not positive definite
     * (covariance came out singular or signed).
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
