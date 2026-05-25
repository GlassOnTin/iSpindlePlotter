package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCleanTest {

    @Test fun `leading SG spike is rejected and robust OG ignores it`() {
        // 40 readings flat at 1.0770, first one is a 1.1070 drop-in spike.
        val hours = DoubleArray(40) { it * 0.1 }
        val sgs = DoubleArray(40) { if (it == 0) 1.1070 else 1.0770 + 0.0003 * kotlin.math.sin(it.toDouble()) }
        val keep = SeriesClean.keptIndices(hours, sgs)
        assertTrue("index 0 (spike) must be dropped", 0 !in keep.toList())
        assertTrue("robust OG ${SeriesClean.robustOg(hours, sgs)} should sit near the 1.077 plateau",
            SeriesClean.robustOg(hours, sgs) in 1.0765..1.0780)
        // Raw max would be the artefact.
        assertEquals(1.1070, sgs.max(), 1e-9)
    }

    @Test fun `clean series is left untouched`() {
        // Smooth descent, no artefacts — every point should survive.
        val hours = DoubleArray(60) { it * 0.5 }
        val sgs = DoubleArray(60) { 1.050 - 0.0005 * it }   // 1.050 -> ~1.0205
        val keep = SeriesClean.keptIndices(hours, sgs)
        assertEquals("no point should be rejected on clean data", sgs.size, keep.size)
        assertEquals(sgs.max(), SeriesClean.robustOg(hours, sgs), 1e-9)
    }

    @Test fun `spikeFreeRange clips a lone spike but keeps a genuine trend`() {
        // 40-point descent 1.060 -> ~1.0405 with one 1.200 spike mid-run.
        val sgs = DoubleArray(40) { 1.060 - 0.0005 * it }
        sgs[20] = 1.200
        val (lo, hi) = SeriesClean.spikeFreeRange(sgs)!!
        // The spike is excluded; the high end is the true OG near 1.060.
        assertEquals("max should ignore the 1.200 spike", 1.060, hi, 1e-6)
        // The low end is the genuine trend tail, not clipped as an outlier.
        assertEquals("min should keep the real descent tail", sgs.last(), lo, 1e-6)
        // The spike really is the raw max it would otherwise blow the axis to.
        assertEquals(1.200, sgs.max(), 1e-9)
    }

    @Test fun `spikeFreeRange returns null for too-few points`() {
        assertEquals(null, SeriesClean.spikeFreeRange(doubleArrayOf(1.05, 1.04, 1.03)))
    }

    @Test fun `temperature-gated trim drops the leading unequilibrated run`() {
        // Flat SG (so MAD rejects nothing); first 8 readings are warm
        // (sensor still equilibrating), the rest settled at 24 C.
        val n = 60
        val hours = DoubleArray(n) { it / 60.0 }            // 1 min cadence, 1 h
        val sgs = DoubleArray(n) { 1.050 }
        val temps = DoubleArray(n) { if (it < 8) 28.0 else 24.0 }
        val keep = SeriesClean.keptIndices(hours, sgs, temps)
        assertEquals("first kept index should be the first settled reading", 8, keep.first())
        assertEquals("everything from index 8 on is kept", n - 8, keep.size)
        // Without temps the same series keeps everything (SG is clean).
        assertEquals(n, SeriesClean.keptIndices(hours, sgs).size)
    }
}
