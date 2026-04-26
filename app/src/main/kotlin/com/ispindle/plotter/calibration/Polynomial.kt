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

    /**
     * Renders the polynomial as a tinyexpr-compatible expression in `tilt`,
     * suitable for the iSpindel firmware's POLYN field. Negative
     * coefficients become `- term` rather than `+ -term` so the firmware's
     * tinyexpr parses cleanly. Locale-pinned to US so a German build of
     * Android doesn't insert decimal commas.
     */
    fun toTinyExpr(): String {
        if (degree < 0) return "0"
        val sb = StringBuilder()
        var emitted = false
        for (i in 0..degree) {
            val c = coeff(i)
            if (c == 0.0) continue
            val mag = String.format(java.util.Locale.US, "%.6g", kotlin.math.abs(c))
            val body = when (i) {
                0 -> mag
                1 -> "${mag}*tilt"
                else -> "${mag}*tilt^$i"
            }
            if (!emitted) {
                if (c < 0) sb.append("-")
                sb.append(body)
                emitted = true
            } else {
                sb.append(if (c < 0) " - " else " + ")
                sb.append(body)
            }
        }
        return if (!emitted) "0" else sb.toString()
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
