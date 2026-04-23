package com.ispindle.plotter.calibration

import com.ispindle.plotter.data.Device

/**
 * Low-degree polynomial in a single variable. Coefficients are stored
 * lowest-order first: coeffs[0] + coeffs[1]*x + coeffs[2]*x^2 + ...
 */
data class Polynomial(private val coeffs: DoubleArray) {

    val degree: Int get() = coeffs.size - 1

    fun coeff(i: Int): Double = if (i < coeffs.size) coeffs[i] else 0.0

    fun eval(x: Double): Double {
        var result = 0.0
        var xp = 1.0
        for (c in coeffs) {
            result += c * xp
            xp *= x
        }
        return result
    }

    fun format(): String = coeffs.withIndex().joinToString(" + ") { (i, c) ->
        when (i) {
            0 -> "%.6g".format(c)
            1 -> "%.6g·x".format(c)
            else -> "%.6g·x^%d".format(c, i)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Polynomial && coeffs.contentEquals(other.coeffs)

    override fun hashCode(): Int = coeffs.contentHashCode()

    companion object {
        fun fromDevice(d: Device): Polynomial {
            val n = d.calDegree.coerceIn(0, 3)
            val arr = DoubleArray(n + 1)
            if (n >= 0) arr[0] = d.calA
            if (n >= 1) arr[1] = d.calB
            if (n >= 2) arr[2] = d.calC
            if (n >= 3) arr[3] = d.calD
            return Polynomial(arr)
        }
    }
}
