package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the yeast-settling / clarification detector.
 *
 * The real positive fixture (`ferment_capture_settling.csv`) is a 4-day
 * capture where the SG steps down ≈ 2.6 mSG at constant temperature (FG
 * 1.0110 with yeast in suspension → 1.0084 clarified) over ~hours 67–84,
 * before a cold crash at ~h 92. Synthetic cases pin the gates deterministically.
 */
class SettlingDetectorTest {

    @Test fun `detects a constant-temperature settle step`() {
        val (h, sg, t) = syntheticSettle()
        val ev = SettlingDetector.detect(h, sg, t, og = 1.050, coldCrashOnsetH = null)
        assertNotNull("settle step should be detected", ev)
        assertEquals("fgWithYeast", 1.0110, ev!!.fgWithYeast, 0.0006)
        assertEquals("clarifiedSg", 1.0085, ev.clarifiedSg, 0.0006)
        assertTrue("dropSg ${ev.dropSg} in 0.0020..0.0035", ev.dropSg in 0.0020..0.0035)
    }

    @Test fun `no step on a plain ferment that just reaches FG and holds`() {
        val (h, sg, t) = syntheticFlat()
        assertNull(
            "a flat FG plateau is not a settle step",
            SettlingDetector.detect(h, sg, t, og = 1.050, coldCrashOnsetH = null)
        )
    }

    @Test fun `real capture - settling detected and labelled Clarifying before the crash`() {
        val (h, sg, t) = loadCaptureWithTemp("ferment_capture_settling.csv")
        val timeline = Fermentation.buildTimeline(h, sg, temps = t)!!
        val ev = timeline.settling
        assertNotNull("settling must be detected on the real capture", ev)
        assertTrue("fgWithYeast ${ev!!.fgWithYeast} ≈ 1.011", ev.fgWithYeast in 1.0100..1.0125)
        assertTrue("clarifiedSg ${ev.clarifiedSg} ≈ 1.0084", ev.clarifiedSg in 1.0075..1.0095)
        assertTrue("dropSg ${ev.dropSg} in 0.0020..0.0035", ev.dropSg in 0.0020..0.0035)

        // The capture cold-crashes at the end; the settle precedes it.
        assertNotNull("cold crash should be detected too", timeline.coldCrashOnsetH)
        assertTrue("settle ends before the crash", ev.endH <= timeline.coldCrashOnsetH!!)

        val midH = (ev.startH + ev.endH) / 2.0
        assertEquals(
            "phase mid-settle should be Clarifying",
            Fermentation.Phase.Clarifying, timeline.phaseAt(midH)
        )
        assertTrue(
            "stateAt mid-settle should be Clarifying",
            Fermentation.stateAt(timeline, h, sg, midH) is Fermentation.State.Clarifying
        )
    }

    @Test fun `a still-fermenting capture has no settle`() {
        // A mid-ferment, still-descending brew: no FG plateau yet, so nothing
        // to step down from.
        val (h, sg, t) = loadCaptureWithTemp("ferment_brew2_late.csv")
        val timeline = Fermentation.buildTimeline(h, sg, temps = t)!!
        assertNull("still descending — not a settle", timeline.settling)
    }

    @Test fun `another cold-crashed capture also shows the settle`() {
        // The phenomenon recurs across ferments (the user's observation): this
        // older capture also clarifies a couple of mSG before its crash.
        val (h, sg, t) = loadCaptureWithTemp("ferment_capture_long_cold.csv")
        val ev = Fermentation.buildTimeline(h, sg, temps = t)!!.settling
        assertNotNull("settle expected on this late ferment too", ev)
        assertTrue("dropSg ${ev!!.dropSg} is a modest few mSG", ev.dropSg in 0.0015..0.0035)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** lag → active descent → plateau A (1.011) → gentle drift → plateau B
     *  (1.0085), all at a constant 19.7 °C. 30-min samples. */
    private fun syntheticSettle(): Triple<DoubleArray, DoubleArray, DoubleArray> =
        series { hr ->
            when {
                hr < 6.0 -> 1.0500
                hr < 30.0 -> 1.0500 - (hr - 6.0) / 24.0 * (1.0500 - 1.0110)
                hr < 50.0 -> 1.0110
                hr < 62.0 -> 1.0110 - (hr - 50.0) / 12.0 * (1.0110 - 1.0085)
                else -> 1.0085
            }
        }

    /** Same descent, but it just reaches FG (1.011) and holds — no settle. */
    private fun syntheticFlat(): Triple<DoubleArray, DoubleArray, DoubleArray> =
        series { hr ->
            when {
                hr < 6.0 -> 1.0500
                hr < 30.0 -> 1.0500 - (hr - 6.0) / 24.0 * (1.0500 - 1.0110)
                else -> 1.0110
            }
        }

    /** 80 h of 30-min samples at constant temperature, SG from [shape], with a
     *  tiny deterministic ripple so the median paths are exercised. */
    private fun series(shape: (Double) -> Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = 161
        val h = DoubleArray(n) { it * 0.5 }
        val sg = DoubleArray(n) { shape(h[it]) + if (it % 2 == 0) 0.0001 else -0.0001 }
        val t = DoubleArray(n) { 19.7 + if (it % 3 == 0) 0.05 else -0.05 }
        return Triple(h, sg, t)
    }

    private fun loadCaptureWithTemp(name: String): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val tcIdx = header.indexOf("temperature_c")
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        val ts = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split(',')
            xs += parts[tIdx].toDouble()
            ys += parts[sgIdx].toDouble()
            ts += parts[tcIdx].toDouble()
        }
        val t0 = xs.first()
        val hours = DoubleArray(xs.size) { (xs[it] - t0) / 3_600_000.0 }
        return Triple(hours, ys.toDoubleArray(), ts.toDoubleArray())
    }
}
