package com.ispindle.plotter.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LineChartTimeTicksTest {

    @Test fun `niceHourStep targets four ticks across typical spans`() {
        // Span / step ≥ 3 (i.e. ~ 4 ticks) for every span we'd plot.
        for (spanH in listOf(0.5, 1.0, 4.0, 8.0, 24.0, 35.0, 48.0, 96.0, 168.0, 720.0)) {
            val step = niceHourStep(spanH)
            val nTicks = spanH / step
            assertTrue(
                "span $spanH h with step $step h gives $nTicks ticks; expected 2-7",
                nTicks in 2.0..7.0
            )
        }
    }

    @Test fun `35h ferment gets 12h ticks at noon and midnight`() {
        // Run the calendar in a fixed zone so the test is deterministic.
        val zoneBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            // 04-26 02:05 → 04-27 13:30 (≈ 35.4 h).
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(2026, Calendar.APRIL, 26, 2, 5, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val xMin = cal.timeInMillis.toDouble()
            cal.set(2026, Calendar.APRIL, 27, 13, 30, 0)
            val xMax = cal.timeInMillis.toDouble()

            val (ticks, stepH) = clockAlignedTicks(xMin, xMax)
            assertEquals("step should be 12 h on a 35 h span", 12.0, stepH, 1e-9)
            assertEquals(
                "expected ticks at 04-26 12:00, 04-27 00:00, 04-27 12:00",
                3, ticks.size
            )

            val labels = ticks.map { timeAxisLabel(it.toLong(), stepH) }
            assertEquals("12:00", labels[0])
            assertEquals("04-27", labels[1])
            assertEquals("12:00", labels[2])
        } finally {
            TimeZone.setDefault(zoneBefore)
        }
    }

    @Test fun `7d window gets daily midnight ticks`() {
        val zoneBefore = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(2026, Calendar.APRIL, 20, 14, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val xMin = cal.timeInMillis.toDouble()
            cal.set(2026, Calendar.APRIL, 27, 14, 0, 0)
            val xMax = cal.timeInMillis.toDouble()

            val (ticks, stepH) = clockAlignedTicks(xMin, xMax)
            // 168 h / 4 ticks = 42 h → first nice ≥ 42 is 48 h.
            assertEquals(48.0, stepH, 1e-9)
            assertTrue("should produce 3-4 ticks; got ${ticks.size}", ticks.size in 3..4)
            // All labels at midnight should be `MM-dd` form.
            ticks.forEach { t ->
                val cal2 = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal2.timeInMillis = t.toLong()
                assertEquals(0, cal2[Calendar.HOUR_OF_DAY])
                assertEquals(0, cal2[Calendar.MINUTE])
                assertEquals("MM-dd label expected at midnight", 5, timeAxisLabel(t.toLong(), stepH).length)
            }
        } finally {
            TimeZone.setDefault(zoneBefore)
        }
    }

    @Test fun `tiny degenerate ranges return no ticks`() {
        assertEquals(0, clockAlignedTicks(0.0, 0.0).first.size)
        assertEquals(0, clockAlignedTicks(100.0, 50.0).first.size)
    }
}
