package com.ispindle.plotter.calibration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CubicParserTest {

    private val tol = 1e-12

    @Test fun `precalibrated cubic round-trips`() {
        val expr = "0.000000231*tilt^3 - 0.000087*tilt^2 + 0.0079*tilt - 0.0118"
        val coeffs = CubicParser.parse(expr)!!
        assertArrayEquals(
            doubleArrayOf(-0.0118, 0.0079, -0.000087, 0.000000231),
            coeffs, tol
        )
        assertEquals(3, CubicParser.degree(coeffs))
    }

    @Test fun `coefficient with scientific notation`() {
        val coeffs = CubicParser.parse("1.2e-3*tilt^2 + 0.5")!!
        assertArrayEquals(doubleArrayOf(0.5, 0.0, 0.0012, 0.0), coeffs, tol)
    }

    @Test fun `linear with negative leading sign`() {
        val coeffs = CubicParser.parse("-2.5*tilt + 100")!!
        assertArrayEquals(doubleArrayOf(100.0, -2.5, 0.0, 0.0), coeffs, tol)
        assertEquals(1, CubicParser.degree(coeffs))
    }

    @Test fun `tilt times tilt without caret`() {
        val coeffs = CubicParser.parse("3*tilt*tilt + 1")!!
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 3.0, 0.0), coeffs, tol)
    }

    @Test fun `whitespace is irrelevant`() {
        val coeffs = CubicParser.parse("  0.5  *  tilt  +  1.5  ")!!
        assertArrayEquals(doubleArrayOf(1.5, 0.5, 0.0, 0.0), coeffs, tol)
    }

    @Test fun `bare tilt term has implicit coefficient one`() {
        val coeffs = CubicParser.parse("tilt^2 - tilt")!!
        assertArrayEquals(doubleArrayOf(0.0, -1.0, 1.0, 0.0), coeffs, tol)
    }

    @Test fun `temp reference is rejected`() {
        assertNull(CubicParser.parse("0.01*tilt + 0.02*temp"))
    }

    @Test fun `function call is rejected`() {
        assertNull(CubicParser.parse("sin(tilt)"))
    }

    @Test fun `degree 4 is rejected`() {
        assertNull(CubicParser.parse("0.001*tilt^4"))
    }

    @Test fun `empty expression is null`() {
        assertNull(CubicParser.parse(""))
        assertNull(CubicParser.parse("   "))
    }

    @Test fun `all-zero polynomial is null`() {
        assertNull(CubicParser.parse("0*tilt + 0"))
    }
}
