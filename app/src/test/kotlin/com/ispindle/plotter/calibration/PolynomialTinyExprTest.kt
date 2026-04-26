package com.ispindle.plotter.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

class PolynomialTinyExprTest {

    @Test fun `quadratic formats with explicit signs`() {
        // The user's degree-2 fit from the calibration session.
        val poly = Polynomial(doubleArrayOf(0.956879, 0.00156396, 3.83518e-06))
        // Round-trip through the cubic parser to check tinyexpr-as-input
        // also produces the same coefficients.
        val rendered = poly.toTinyExpr()
        assertEquals(
            "0.956879 + 0.00156396*tilt + 3.83518e-06*tilt^2",
            rendered
        )
        val reparsed = CubicParser.parse(rendered)!!
        assertEquals(0.956879, reparsed[0], 1e-9)
        assertEquals(0.00156396, reparsed[1], 1e-9)
        assertEquals(3.83518e-06, reparsed[2], 1e-12)
    }

    @Test fun `negative middle coefficient produces minus separator not plus minus`() {
        val poly = Polynomial(doubleArrayOf(-0.0118, 0.0079, -8.7e-5, 2.31e-7))
        val rendered = poly.toTinyExpr()
        // Java's %g pads to 6 sig figs (unlike C printf), so trailing zeros
        // appear in the rendered string. Both formats parse back identically.
        assertEquals(
            "-0.0118000 + 0.00790000*tilt - 8.70000e-05*tilt^2 + 2.31000e-07*tilt^3",
            rendered
        )
        val reparsed = CubicParser.parse(rendered)!!
        assertEquals(-0.0118, reparsed[0], 1e-9)
        assertEquals(0.0079, reparsed[1], 1e-9)
        assertEquals(-8.7e-5, reparsed[2], 1e-12)
        assertEquals(2.31e-7, reparsed[3], 1e-12)
    }

    @Test fun `zero coefficients are omitted`() {
        val poly = Polynomial(doubleArrayOf(1.0, 0.0, 2.0))
        assertEquals("1.00000 + 2.00000*tilt^2", poly.toTinyExpr())
    }

    @Test fun `degenerate all-zero is the literal zero`() {
        val poly = Polynomial(DoubleArray(4))
        assertEquals("0", poly.toTinyExpr())
    }
}
