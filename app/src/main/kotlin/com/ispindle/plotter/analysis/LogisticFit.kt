package com.ispindle.plotter.analysis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
 * Solved by a small Levenberg-Marquardt (~80 lines), bounded on every
 * parameter so a noisy or partial dataset can't push us into nonsense
 * (e.g. FG below 0.99 or above OG, k ≤ 0).
 */
object LogisticFit {

    data class Result(
        val og: Double,
        val fg: Double,
        val k: Double,
        val tMid: Double,
        val rmsResidual: Double,
        val pointCount: Int
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
    }

    /** Fit logistic to [xs] (hours from start) / [ys] (SG). Null on failure. */
    fun fit(xs: DoubleArray, ys: DoubleArray): Result? {
        require(xs.size == ys.size)
        val n = xs.size
        if (n < 6) return null
        val xSpan = xs.last() - xs.first()
        val yMin = ys.min()
        val yMax = ys.max()
        if (yMax - yMin < 1e-4 || xSpan <= 0.0) return null

        // Initial guess. OG = top of observed; FG ≈ 75% attenuation prior so we
        // converge from the right side of the parameter space; tMid in the
        // middle of the window; k = 1/(span/4) so the S-curve fills the data.
        val ogInit = yMax
        val fgInit = max(0.990, 1.000 + 0.25 * (ogInit - 1.000))
        val tMidInit = xs.first() + 0.5 * xSpan
        val kInit = 4.0 / xSpan

        var p = doubleArrayOf(ogInit, fgInit, kInit, tMidInit)
        var lambda = 1e-3
        val maxIter = 80
        var prevRss = rss(xs, ys, p)

        for (iter in 0 until maxIter) {
            val (jtj, jtr) = jacobianBlocks(xs, ys, p)
            // Levenberg-Marquardt damping: (J^T J + λ·diag(J^T J)) δ = J^T r
            for (i in 0..3) jtj[i][i] *= (1.0 + lambda)
            val delta = solve4x4(jtj, jtr) ?: break

            val candidate = DoubleArray(4) { p[it] + delta[it] }
            applyBounds(candidate, ogInit)
            val newRss = rss(xs, ys, candidate)

            if (newRss < prevRss) {
                p = candidate
                lambda = max(lambda * 0.5, 1e-9)
                if (abs(prevRss - newRss) < 1e-14) break
                prevRss = newRss
            } else {
                lambda = min(lambda * 4.0, 1e9)
                if (lambda > 1e8) break
            }
        }

        val finalRss = rss(xs, ys, p)
        if (!finalRss.isFinite()) return null
        val og = p[0]; val fg = p[1]; val k = p[2]; val tMid = p[3]
        if (k <= 0.0 || og - fg <= 0.0) return null
        return Result(og, fg, k, tMid, sqrt(finalRss / n), n)
    }

    /** SG = FG + (OG-FG) / (1 + exp(k(t - tMid))). */
    private fun model(p: DoubleArray, t: Double): Double {
        val og = p[0]; val fg = p[1]; val k = p[2]; val tMid = p[3]
        return fg + (og - fg) / (1.0 + exp(k * (t - tMid)))
    }

    private fun rss(xs: DoubleArray, ys: DoubleArray, p: DoubleArray): Double {
        var s = 0.0
        for (i in xs.indices) {
            val r = ys[i] - model(p, xs[i])
            s += r * r
        }
        return s
    }

    /**
     * Returns (J^T J, J^T r). For each sample, the partials are
     *   df/dOG   = 1 / (1 + e^(k(t-tMid))) = sigmoid(-k(t-tMid))
     *   df/dFG   = 1 - df/dOG
     *   df/dk    = (OG - FG) * sig * (1-sig) * (-(t - tMid))
     *   df/dtMid = (OG - FG) * sig * (1-sig) * k
     */
    private fun jacobianBlocks(
        xs: DoubleArray, ys: DoubleArray, p: DoubleArray
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
                for (b in 0..3) {
                    jtj[a][b] += js[a] * js[b]
                }
            }
        }
        return jtj to jtr
    }

    private fun applyBounds(p: DoubleArray, ogPrior: Double) {
        // OG can drift up if the user backfills earlier data, but never
        // unrealistically high. Keep it within the observed bound + 0.5%.
        p[0] = p[0].coerceIn(ogPrior - 0.001, ogPrior + 0.005)
        // FG floored at the 90 %-attenuation prior so a sparse dataset that
        // doesn't see the asymptote can't pull FG into nonsense (e.g. 0.985
        // for an OG-1.06 wort, which would imply 108 % attenuation). 90 %
        // is at the upper edge of what real yeast achieve in normal beer
        // and stronger styles; it's still wrong for distillers' yeast or
        // heavily-amyloglucosidase'd ferments, but those are the exception.
        val fgFloor = max(0.990, 1.000 + 0.10 * (p[0] - 1.000))
        p[1] = p[1].coerceIn(fgFloor, p[0] - 0.001)
        // k > 0 keeps the S-curve oriented as a decay. Cap at 100/hour to
        // catch numerical blow-ups without rejecting fast ferments.
        p[2] = p[2].coerceIn(1e-4, 100.0)
        // tMid free-floating but clipped to a sane span.
        p[3] = p[3].coerceIn(-1000.0, 1e6)
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
}
