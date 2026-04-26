package com.ispindle.plotter.analysis

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Two predictive fitters used by the Graph screen.
 *
 * Both take their independent variable in **hours from the first sample**,
 * not raw epoch ms — keeps the exponential's `exp(-k·t)` numerically tame
 * regardless of how much absolute time has passed.
 */
object Fits {

    // ---- Linear: y = a + b·x --------------------------------------------------

    data class Linear(
        val intercept: Double,
        val slope: Double,
        val rmsResidual: Double,
        val pointCount: Int,
        /** 1σ on the slope from σ_residual / √Σ(x-x̄)². Null when n ≤ 2. */
        val slopeSigma: Double? = null
    ) {
        fun predict(x: Double): Double = intercept + slope * x

        /** Returns x where y == [target], or null if slope is zero. */
        fun timeToReach(target: Double): Double? {
            if (slope == 0.0) return null
            return (target - intercept) / slope
        }
    }

    /**
     * Ordinary least squares. Returns null if there are fewer than two
     * points, or all `xs` are equal (degenerate horizontal regression).
     */
    fun fitLinear(xs: DoubleArray, ys: DoubleArray): Linear? {
        require(xs.size == ys.size)
        val n = xs.size
        if (n < 2) return null
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (i in 0 until n) {
            sumX += xs[i]
            sumY += ys[i]
            sumXX += xs[i] * xs[i]
            sumXY += xs[i] * ys[i]
        }
        val meanX = sumX / n
        val meanY = sumY / n
        val ssX = sumXX - n * meanX * meanX
        if (ssX <= 0.0) return null
        val slope = (sumXY - n * meanX * meanY) / ssX
        val intercept = meanY - slope * meanX
        var rss = 0.0
        for (i in 0 until n) {
            val pred = intercept + slope * xs[i]
            val r = ys[i] - pred
            rss += r * r
        }
        val rmsResidual = sqrt(rss / n)
        // Residual std at n-2 dof; slope σ from textbook OLS variance.
        val slopeSigma = if (n > 2) sqrt(rss / (n - 2) / ssX) else null
        return Linear(intercept, slope, rmsResidual, n, slopeSigma)
    }

    // ---- Exponential decay: y = c + a·exp(-k·x) -------------------------------

    data class Exponential(
        val asymptote: Double,
        val initialOffset: Double,
        val rateConstant: Double,
        val rmsResidual: Double,
        val pointCount: Int
    ) {
        fun predict(x: Double): Double = asymptote + initialOffset * exp(-rateConstant * x)

        /**
         * Time at which the curve reaches [target]. Null if [target] is on
         * the wrong side of the asymptote, or if the fit is flat (k == 0).
         */
        fun timeToReach(target: Double): Double? {
            if (initialOffset == 0.0 || rateConstant <= 0.0) return null
            val ratio = (target - asymptote) / initialOffset
            if (ratio <= 0.0) return null
            return -ln(ratio) / rateConstant
        }
    }

    /**
     * Fits a monotonically decreasing exponential `y = c + a·exp(-k·x)`
     * (k > 0, a > 0, c < min(y)) by golden-section search over the
     * asymptote `c`. At each candidate `c`, `k` and `a` come from a
     * closed-form linear regression on `log(y - c)` against `x`. The
     * search picks the `c` that minimises original-space RSS.
     *
     * Robust because:
     *   - The 1-D search over `c` cannot diverge.
     *   - For every visited `c`, the linear regression on log-space is
     *     analytic and well-conditioned as long as (y - c) > 0.
     *   - We always sit `c < min(y) - ε` so the log domain is safe.
     *
     * Returns null when there are fewer than four points, the data has no
     * range to fit, or no asymptote yields a positive `k`.
     */
    fun fitExponentialDecay(xs: DoubleArray, ys: DoubleArray): Exponential? {
        require(xs.size == ys.size)
        val n = xs.size
        if (n < 4) return null
        val yMin = ys.min()
        val yMax = ys.max()
        val yRange = yMax - yMin
        if (yRange < 1e-12) return null

        // Search range for the asymptote: from "well below the data" up to
        // "just below the minimum" so log(y - c) stays defined.
        val cLow = yMin - 10.0 * yRange - 1e-3
        val cHigh = yMin - 1e-9
        val cBest = goldenSectionMin(cLow, cHigh, iterations = 80) { c ->
            rssAtAsymptote(xs, ys, c)
        }
        val finalRss = rssAtAsymptote(xs, ys, cBest)
        if (!finalRss.isFinite()) return null

        val logYs = DoubleArray(n) { ln(ys[it] - cBest) }
        val lin = fitLinear(xs, logYs) ?: return null
        val k = -lin.slope
        if (k <= 0.0) return null
        val a = exp(lin.intercept)
        return Exponential(
            asymptote = cBest,
            initialOffset = a,
            rateConstant = k,
            rmsResidual = sqrt(finalRss / n),
            pointCount = n
        )
    }

    private fun rssAtAsymptote(xs: DoubleArray, ys: DoubleArray, c: Double): Double {
        val n = xs.size
        val logYs = DoubleArray(n)
        for (i in 0 until n) {
            val arg = ys[i] - c
            if (arg <= 0.0) return Double.POSITIVE_INFINITY
            logYs[i] = ln(arg)
        }
        val lin = fitLinear(xs, logYs) ?: return Double.POSITIVE_INFINITY
        val k = -lin.slope
        if (k <= 0.0) return Double.POSITIVE_INFINITY
        val a = exp(lin.intercept)
        var rss = 0.0
        for (i in 0 until n) {
            val pred = c + a * exp(-k * xs[i])
            val r = ys[i] - pred
            rss += r * r
        }
        return rss
    }

    private inline fun goldenSectionMin(
        lo: Double,
        hi: Double,
        iterations: Int,
        f: (Double) -> Double
    ): Double {
        val phi = (sqrt(5.0) - 1.0) / 2.0
        var a = lo
        var b = hi
        var x1 = b - phi * (b - a)
        var x2 = a + phi * (b - a)
        var f1 = f(x1)
        var f2 = f(x2)
        repeat(iterations) {
            if (f1 < f2) {
                b = x2
                x2 = x1
                f2 = f1
                x1 = b - phi * (b - a)
                f1 = f(x1)
            } else {
                a = x1
                x1 = x2
                f1 = f2
                x2 = a + phi * (b - a)
                f2 = f(x2)
            }
        }
        return (a + b) / 2.0
    }
}
