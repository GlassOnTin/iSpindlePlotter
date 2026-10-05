package com.ispindle.plotter.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gesture math behind the SG chart's drag-to-mark gesture
 * (`dataFromPx`, `snapToNearestX`, `snapSpan`). The interactive gesture
 * itself needs Compose — not coverable in JVM tests — but the coordinate
 * inversions and the snap-on-commit logic are the load-bearing parts.
 */
class LineChartGestureMathTest {

    // dataFromPx: plot spanning px [0, 100] over data [0.0, 100.0]

    @Test fun `left edge maps to xMin`() {
        assertEquals(0.0, dataFromPx(px = 0f, plotLeftPx = 0f, plotWidthPx = 100f, xMin = 0.0, xMax = 100.0), 1e-9)
    }

    @Test fun `right edge maps to xMax`() {
        assertEquals(100.0, dataFromPx(px = 100f, plotLeftPx = 0f, plotWidthPx = 100f, xMin = 0.0, xMax = 100.0), 1e-9)
    }

    @Test fun `midpoint maps to the middle of the data range`() {
        assertEquals(50.0, dataFromPx(px = 50f, plotLeftPx = 0f, plotWidthPx = 100f, xMin = 0.0, xMax = 100.0), 1e-9)
    }

    @Test fun `touches outside the plot area are clamped to the plot edges`() {
        assertEquals(0.0, dataFromPx(px = -30f, plotLeftPx = 0f, plotWidthPx = 100f, xMin = 0.0, xMax = 100.0), 1e-9)
        assertEquals(100.0, dataFromPx(px = 150f, plotLeftPx = 0f, plotWidthPx = 100f, xMin = 0.0, xMax = 100.0), 1e-9)
    }

    @Test fun `mapping is monotone in px (drag direction preserved)`() {
        val xs = listOf(0f, 25f, 50f, 75f, 100f)
        val data = xs.map { dataFromPx(it, 0f, 100f, 0.0, 100.0) }
        assertTrue(data.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun `offset plot area maps relative to its left edge`() {
        // Real charts have a ~48dp left gutter: px measured inside [100, 200].
        assertEquals(50.0, dataFromPx(px = 150f, plotLeftPx = 100f, plotWidthPx = 100f, xMin = 0.0, xMax = 100.0), 1e-9)
    }

    // snapToNearestX

    @Test fun `snaps to the nearest plotted x`() {
        val xs = listOf(1000.0, 2000.0, 3000.0)
        assertEquals(2000.0, snapToNearestX(2100.0, xs), 1e-9)
        assertEquals(3000.0, snapToNearestX(2900.0, xs), 1e-9)
        assertEquals(1000.0, snapToNearestX(1300.0, xs), 1e-9)
    }

    @Test fun `tie breaks to the first minimum in list order`() {
        val xs = listOf(1000.0, 2000.0, 3000.0)
        assertEquals(1000.0, snapToNearestX(1500.0, xs), 1e-9)
    }

    @Test fun `no plotted points falls back to the raw target`() {
        assertEquals(1234.5, snapToNearestX(1234.5, emptyList()), 1e-9)
    }

    // snapSpan

    @Test fun `dragged back-to-front span is ordered lowest-first`() {
        val xs = listOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.0 to 4.0, snapSpan(5.0, 1.0, xs))
    }

    @Test fun `both ends snap to plot points`() {
        val xs = listOf(1000.0, 1010.0, 2000.0, 2010.0)
        assertEquals(1000.0 to 2010.0, snapSpan(1002.0, 2008.0, xs))
    }

    @Test fun `span collapsing to a single point is returned for the caller to reject`() {
        // A single plotted point: any drag inside it snaps both ends onto
        // it, producing a zero-width span the commit gate then rejects.
        val xs = listOf(1000.0)
        assertEquals(1000.0 to 1000.0, snapSpan(500.0, 900.0, xs))
    }
}