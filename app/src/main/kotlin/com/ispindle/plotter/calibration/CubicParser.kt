package com.ispindle.plotter.calibration

/**
 * Tries to read an iSpindel `POLYN` expression as a polynomial in `tilt`
 * up to degree 3 — the form precalibrated devices ship with, e.g.
 *
 *     0.000000231*tilt^3 - 0.000087*tilt^2 + 0.0079*tilt - 0.0118
 *
 * The firmware feeds this to tinyexpr, so an arbitrary expression is legal.
 * We only handle the cubic-or-lower polynomial subset; anything else
 * returns null and the caller should preserve the raw string verbatim.
 */
object CubicParser {

    /**
     * Returns coefficients [c0, c1, c2, c3] (lowest order first), or null
     * if the expression contains anything we don't recognise as a tilt
     * polynomial term — most importantly, `temp` references or function
     * calls (sin, exp, etc.).
     */
    fun parse(expression: String): DoubleArray? {
        val raw = expression.replace("\\s".toRegex(), "")
        if (raw.isEmpty()) return null

        // We only know how to read polynomials in `tilt`. Anything that
        // references `temp`, calls a function, or uses parentheses is
        // out of scope.
        if (raw.contains("temp", ignoreCase = true)) return null
        if (raw.contains('(') || raw.contains(')')) return null
        val letters = Regex("[A-Za-z]+").findAll(raw).map { it.value.lowercase() }.toList()
        if (letters.any { it != "tilt" && it != "e" }) return null

        // Normalise tilt*tilt sequences before splitting on + / -.
        val normalised = raw
            .replace("tilt*tilt*tilt", "tilt^3")
            .replace("tilt*tilt", "tilt^2")

        // Split into signed terms. A sign that's part of `1.2e-3` must NOT
        // become a split point; the lookbehind exempts e/E.
        val terms = splitOnSignedTerms(normalised) ?: return null
        val coeffs = DoubleArray(4)
        for (term in terms) {
            val parsed = parseTerm(term) ?: return null
            if (parsed.degree > 3) return null
            coeffs[parsed.degree] += parsed.coefficient
        }
        if (coeffs.all { it == 0.0 }) return null
        return coeffs
    }

    fun degree(coeffs: DoubleArray): Int {
        for (i in coeffs.indices.reversed()) {
            if (coeffs[i] != 0.0) return i
        }
        return 0
    }

    private data class Term(val coefficient: Double, val degree: Int)

    private fun splitOnSignedTerms(input: String): List<String>? {
        val terms = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if ((c == '+' || c == '-') && sb.isNotEmpty()) {
                val prev = sb.last()
                // Don't split on the sign of an exponent: 1.2e-3, 4E+10
                if (prev == 'e' || prev == 'E') {
                    sb.append(c)
                    i++
                    continue
                }
                terms += sb.toString()
                sb.clear()
            }
            sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) terms += sb.toString()
        return terms.takeIf { it.isNotEmpty() }
    }

    private fun parseTerm(rawTerm: String): Term? {
        if (rawTerm.isEmpty()) return null
        var sign = 1.0
        var s = rawTerm
        while (s.startsWith("+") || s.startsWith("-")) {
            if (s[0] == '-') sign = -sign
            s = s.substring(1)
        }
        if (s.isEmpty()) return null

        // Cases: pure constant ("0.5"), pure variable ("tilt", "tilt^2"),
        // coefficient × variable ("0.5*tilt^2", "0.5tilt^2").
        val tiltIndex = s.indexOf("tilt")
        if (tiltIndex < 0) {
            val v = s.toDoubleOrNull() ?: return null
            return Term(sign * v, 0)
        }
        val coeffPart = s.substring(0, tiltIndex).trimEnd('*')
        val varPart = s.substring(tiltIndex)
        val coeff = if (coeffPart.isEmpty()) 1.0 else coeffPart.toDoubleOrNull() ?: return null
        val degree = when (varPart) {
            "tilt" -> 1
            "tilt^1" -> 1
            "tilt^2" -> 2
            "tilt^3" -> 3
            else -> return null
        }
        return Term(sign * coeff, degree)
    }
}
