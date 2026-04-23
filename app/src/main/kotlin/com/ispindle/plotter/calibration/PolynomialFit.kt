package com.ispindle.plotter.calibration

import kotlin.math.pow

/**
 * Ordinary least squares polynomial regression. Builds the (d+1)×(d+1)
 * normal-equations matrix and solves it by Gaussian elimination with
 * partial pivoting. Suitable for tiny systems (degree ≤ 3, a handful of
 * points) — not for large or ill-conditioned fits.
 */
object PolynomialFit {

    data class Result(val polynomial: Polynomial, val rSquared: Double?)

    /**
     * Fit a polynomial of `degree` (1..3) through [xs]/[ys]. Returns null
     * if there are fewer data points than coefficients or the system is
     * singular (e.g. all xs identical for degree > 0).
     */
    fun fit(xs: DoubleArray, ys: DoubleArray, degree: Int): Result? {
        require(xs.size == ys.size)
        val n = xs.size
        val k = degree + 1
        if (n < k) return null

        // Normal equations:  (X^T X) b = X^T y
        // Accumulate powers so we only iterate once.
        val xPower = DoubleArray(2 * degree + 1)
        val rhs = DoubleArray(k)
        for (i in 0 until n) {
            var xp = 1.0
            val y = ys[i]
            for (p in 0..2 * degree) {
                xPower[p] += xp
                if (p < k) rhs[p] += y * xp
                xp *= xs[i]
            }
        }

        val m = Array(k) { i -> DoubleArray(k + 1) { j ->
            if (j < k) xPower[i + j] else rhs[i]
        } }

        val coeffs = solveGaussian(m) ?: return null
        val poly = Polynomial(coeffs)
        val r2 = rSquared(xs, ys, poly)
        return Result(poly, r2)
    }

    private fun solveGaussian(m: Array<DoubleArray>): DoubleArray? {
        val n = m.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) {
                if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[pivot][col])) pivot = r
            }
            if (kotlin.math.abs(m[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            }
            val pv = m[col][col]
            for (j in col..n) m[col][j] /= pv
            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col]
                if (factor == 0.0) continue
                for (j in col..n) m[r][j] -= factor * m[col][j]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }

    private fun rSquared(xs: DoubleArray, ys: DoubleArray, poly: Polynomial): Double? {
        val n = ys.size
        if (n < 2) return null
        val mean = ys.average()
        var ssTot = 0.0
        var ssRes = 0.0
        for (i in 0 until n) {
            val pred = poly.eval(xs[i])
            ssTot += (ys[i] - mean).pow(2)
            ssRes += (ys[i] - pred).pow(2)
        }
        if (ssTot == 0.0) return null
        return 1.0 - ssRes / ssTot
    }
}
