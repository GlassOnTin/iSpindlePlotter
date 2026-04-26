package com.ispindle.plotter.analysis

import kotlin.math.sqrt

/**
 * Per-point measurement σ for SG readings. Two contributions are combined
 * in quadrature, since they're physically independent:
 *
 *  - **σ_data**: short-time high-frequency noise estimated from the data
 *    itself, by sliding a short window over the series, locally detrending
 *    with a line, and pooling the residual variance. Inside a short window
 *    the underlying fermentation curve is locally linear, so what survives
 *    detrending is well-approximated by the measurement noise floor.
 *
 *  - **σ_cal**: residual of the calibration polynomial, derived from the
 *    stored R². R² = 1 - SS_res/SS_tot, so σ_cal = σ_y · √(1-R²) where
 *    σ_y is the SG-axis std at calibration time.
 *
 * Total σ used downstream is `sqrt(σ_data² + σ_cal²)`.
 */
object NoiseModel {

    /**
     * Short-time SG noise. Walks the series in non-overlapping windows of
     * [windowH] hours, fits a line through each, accumulates residuals,
     * and divides by total degrees of freedom.
     *
     * Returns null when no window has enough points (≥4) to detrend.
     */
    fun estimateDataNoise(
        hours: DoubleArray,
        sgs: DoubleArray,
        windowH: Double = 2.0
    ): Double? {
        require(hours.size == sgs.size)
        val n = hours.size
        if (n < 6) return null
        var sumSq = 0.0
        var dof = 0
        var i = 0
        while (i < n) {
            val tStart = hours[i]
            var j = i + 1
            while (j < n && hours[j] - tStart <= windowH) j++
            val k = j - i
            if (k >= 4) {
                val xs = DoubleArray(k) { hours[i + it] }
                val ys = DoubleArray(k) { sgs[i + it] }
                val lin = Fits.fitLinear(xs, ys)
                if (lin != null) {
                    for (idx in 0 until k) {
                        val r = ys[idx] - lin.predict(xs[idx])
                        sumSq += r * r
                    }
                    dof += (k - 2)
                }
            }
            i = if (j > i) j else i + 1
        }
        if (dof <= 0) return null
        return sqrt(sumSq / dof)
    }

    /**
     * σ implied by the calibration polynomial's R², in SG units. Assumes
     * the calibration spanned a representative SG range of [sgRange]; for
     * a typical 1.000-1.080 calibration that's 0.08, but a 1.000-1.040
     * calibration is 0.04. Uniform-distribution assumption gives the
     * standard deviation of y as range/√12.
     */
    fun fromCalibrationRSquared(rSq: Double, sgRange: Double = 0.05): Double {
        val unexplained = (1.0 - rSq).coerceAtLeast(0.0)
        return sgRange * sqrt(unexplained / 12.0)
    }

    /** Combines independent contributions in quadrature with a hard floor. */
    fun combine(dataNoise: Double?, calNoise: Double?): Double {
        val a = dataNoise ?: 0.0
        val b = calNoise ?: 0.0
        val s = sqrt(a * a + b * b)
        return s.coerceAtLeast(1e-5)
    }
}
