package com.ispindle.plotter.analysis

/**
 * Rolling-window smoothers for noisy time series. The chart calls
 * these when raw readings carry sensor blips that would dominate a
 * straight polyline (battery voltage is the canonical example —
 * iSpindle ADC noise can throw a single reading 30 mV off baseline).
 */
object Smoothing {

    /**
     * Centred rolling median. Robust to single-point spikes — a window
     * of `window` readings has to *all* shift before the smoothed
     * value moves, so an isolated outlier is rejected outright.
     *
     * Boundary handling: the window shrinks at the array edges
     * (asymmetric near the start/end) rather than padding, so the
     * smoothed series has the same length as the input.
     */
    fun rollingMedian(
        points: List<Pair<Double, Double>>,
        window: Int
    ): List<Pair<Double, Double>> {
        require(window >= 1) { "window must be ≥ 1, got $window" }
        if (points.size < 2 || window == 1) return points
        val half = window / 2
        return points.indices.map { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(points.size, i + half + 1)
            val ys = DoubleArray(hi - lo) { points[lo + it].second }
            ys.sort()
            points[i].first to ys[ys.size / 2]
        }
    }

    /**
     * Centred rolling arithmetic mean. Smooths stair-step / quantised
     * data into a continuous-looking curve. Sensitive to outliers — pair
     * with [rollingMedian] when the input has spikes.
     *
     * Same length-preserving boundary handling as [rollingMedian].
     */
    fun rollingMean(
        points: List<Pair<Double, Double>>,
        window: Int
    ): List<Pair<Double, Double>> {
        require(window >= 1) { "window must be ≥ 1, got $window" }
        if (points.size < 2 || window == 1) return points
        val half = window / 2
        return points.indices.map { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(points.size, i + half + 1)
            var sum = 0.0
            for (j in lo until hi) sum += points[j].second
            points[i].first to sum / (hi - lo)
        }
    }

    /**
     * Two-pass smoother: rolling median to drop spikes, then rolling
     * mean to smooth the staircase that ADC quantisation leaves behind.
     * Default windows tuned for ~60 s sample-interval iSpindle battery
     * data — adjust per call site if the noise profile is different.
     */
    fun robustSmooth(
        points: List<Pair<Double, Double>>,
        medianWindow: Int = 9,
        meanWindow: Int = 9
    ): List<Pair<Double, Double>> {
        return rollingMean(rollingMedian(points, medianWindow), meanWindow)
    }
}
