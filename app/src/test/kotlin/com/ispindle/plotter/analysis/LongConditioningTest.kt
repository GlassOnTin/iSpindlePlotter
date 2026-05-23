package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests on a 9-day capture (`ferment_capture_long_cold.csv`)
 * spanning lag → active → conditioning → cold-crash → 4 days at 4 °C →
 * a 72 h logging gap → 28 h more cold conditioning.
 *
 * The earlier behaviour was that, as the cold tail grew, the modified-
 * Gompertz LM was fed both warm fermentation data *and* cold-crash data
 * and converged to a degenerate fit — FG snapped to the cold-affected
 * SG (~1.008), λ went negative, and the resulting RMS was ~7 mSG (vs.
 * the warm-only fit that holds FG ≈ 1.0135 at < 1.5 mSG RMS).
 *
 * The fix trims the LM input at the detected cold-crash onset so the
 * fit always represents the *fermentation* curve (OG → FG), not the
 * subsequent thermal artifact in the SG channel.
 */
class LongConditioningTest {

    @Test fun `cold-crash onset is detected on the long capture`() {
        val (hours, sgs, temps) = loadCaptureWithTemp("ferment_capture_long_cold.csv")
        val timeline = Fermentation.buildTimeline(hours, sgs, temps = temps)!!
        // Crash starts somewhere around h 80–90 (temp drops from ~21 °C
        // to ~5 °C between h 81 and h 105).
        val onset = timeline.coldCrashOnsetH
        assertNotNull("cold-crash onset must be detected", onset)
        assertTrue("onset $onset should land in the descent zone (h 78–95)", onset!! in 78.0..95.0)
        assertEquals(
            "phase at end-of-data should be ColdCrash",
            Fermentation.Phase.ColdCrash, timeline.phaseAt(timeline.lastH)
        )
    }

    @Test fun `Gompertz fit on the long capture stays anchored to the warm-fermentation curve`() {
        val (hours, sgs, temps) = loadCaptureWithTemp("ferment_capture_long_cold.csv")
        val timeline = Fermentation.buildTimeline(hours, sgs, temps = temps)!!
        val fit = timeline.gompertz
        assertNotNull("Gompertz fit must not be null", fit)
        fit!!
        assertTrue(
            "OG ${fit.og} should be within 0.002 of the observed max ~1.0524",
            abs(fit.og - sgs.max()) < 0.002
        )
        // The warm-only fit lands FG ≈ 1.0135 (≈ 75 % attenuation).
        // Pre-fix, the full-data fit collapsed FG to ~1.008 (the cold-
        // affected SG). The ±5 mSG window here distinguishes those two.
        assertTrue(
            "FG ${fit.fg} should sit near the warm-stable plateau ~1.0135 (66–80 % atten)",
            fit.fg in 1.012..1.018
        )
        val atten = (fit.og - fit.fg) / (fit.og - 1.000)
        assertTrue(
            "attenuation $atten should be in the 65–82 % band",
            atten in 0.65..0.82
        )
        assertTrue("lambda ${fit.lambda} should be positive (lag is in the past)", fit.lambda > 0.0)
        assertTrue(
            "lambda ${fit.lambda} should be early in the run (< 30 h)",
            fit.lambda < 30.0
        )
        // muMax peaks around 1–2 mSG/h on this brew. Pre-fix the full-data
        // collapse pushed it down to ~3.7e-4. Floor at 0.0008 here.
        assertTrue(
            "muMax ${fit.muMax} should be ≥ 0.0008 SG/h (real descent rate)",
            fit.muMax >= 0.0008
        )
        assertTrue(
            "RMS residual ${fit.rmsResidual} on the trimmed active span should be ≤ 2 mSG",
            fit.rmsResidual <= 0.002
        )
    }

    @Test fun `fit is dominated by active-phase residuals not by cold-tail readings`() {
        val (hours, sgs, temps) = loadCaptureWithTemp("ferment_capture_long_cold.csv")
        val timeline = Fermentation.buildTimeline(hours, sgs, temps = temps)!!
        val fit = timeline.gompertz!!
        // Sample the curve through the active descent (h 10–60). After
        // the fix the curve sits within ±3 mSG of the data here. Pre-fix
        // (FG dragged down to ~1.008, λ ≈ −58 h) the curve drifted up to
        // 10+ mSG away in the descent.
        val activeIdx = hours.indices.filter { hours[it] in 10.0..60.0 }
        val resids = activeIdx.map { sgs[it] - fit.predict(hours[it]) }
        val maxAbs = resids.maxOf { abs(it) }
        val mean = resids.average()
        assertTrue(
            "active-phase max |residual| $maxAbs should be ≤ 0.005 SG (current model lies on the data)",
            maxAbs <= 0.005
        )
        assertTrue(
            "active-phase mean residual $mean should be within ±0.0015 SG (no systematic bias)",
            abs(mean) < 0.0015
        )
    }

    @Test fun `predicted FG reflects the warm fermentation asymptote, not the cold-crashed SG`() {
        val (hours, sgs, temps) = loadCaptureWithTemp("ferment_capture_long_cold.csv")
        val timeline = Fermentation.buildTimeline(hours, sgs, temps = temps)!!
        // Warm-stable plateau before the crash sat at ~1.0125 — that's
        // where the brewer should read "FG", not the cold-affected
        // ~1.008 reading.
        assertEquals(
            "predictedFg should come from the (now warm-only) Gompertz fit",
            Fermentation.PredictionSource.Gompertz, timeline.source
        )
        assertTrue(
            "predictedFg ${timeline.predictedFg} should sit near 1.0135, not the cold-affected 1.008",
            timeline.predictedFg in 1.012..1.018
        )
    }

    /**
     * The chart's 7-day window slices off most of the warm fermentation
     * — only the last ~15 h of slowing/conditioning sits inside the
     * window before the cold-crash starts. Pre-fix, the detector's
     * median-rolling-temp warmRef was biased cold because the cold tail
     * dominates the window, so cold-crash detection failed and the LM
     * fit a phantom slow-descent through the cold drift, extrapolating
     * an "ETA 9.7 d" forward from a stable beer at 4 °C.
     */
    @Test fun `7-day-windowed slice still detects the cold-crash and stays stable`() {
        val (hours, sgs, temps) = loadCaptureWithTemp("ferment_capture_long_cold.csv")
        val cutoffH = hours.last() - 168.0
        val keep = hours.indices.filter { hours[it] >= cutoffH }
        val wH = DoubleArray(keep.size) { hours[keep[it]] }
        val wS = DoubleArray(keep.size) { sgs[keep[it]] }
        val wT = DoubleArray(keep.size) { temps[keep[it]] }

        val timeline = Fermentation.buildTimeline(wH, wS, temps = wT)!!
        val onset = timeline.coldCrashOnsetH
        assertNotNull("cold-crash onset must still be detected inside a 7d window", onset)
        assertTrue(
            "onset $onset should land in the early part of the windowed range",
            onset!! - wH.first() < 35.0
        )
        assertEquals(
            "phase at end-of-window should be ColdCrash",
            Fermentation.Phase.ColdCrash, timeline.phaseAt(timeline.lastH)
        )
        // The Gompertz fit must asymptote at-or-above fermentSg — not
        // plunge below the data. Pre-fix, the LM (with the default 75 %
        // attenuation prior) collapsed FG to ~1.003 because the windowed
        // OG was only 1.0145; with the fermentSg-anchored prior it now
        // asymptotes around fermentSg ≈ 1.0112.
        val fit = timeline.gompertz
        assertNotNull("windowed Gompertz fit must be present so the chart can draw it", fit)
        val ferm = timeline.fermentSg!!
        assertTrue(
            "windowed fit FG ${fit!!.fg} must be at or above fermentSg=$ferm (curve must not plunge below the data)",
            fit.fg >= ferm - 0.0005
        )
        // No plunging on the predict() either — sample the curve over the
        // full window and assert the minimum stays above (fermentSg − 1 mSG).
        val grid = DoubleArray(48) { wH.first() + (wH.last() - wH.first()) * it / 47.0 }
        val minPredicted = grid.minOf { fit.predict(it) }
        assertTrue(
            "predicted curve min $minPredicted must stay near fermentSg=$ferm (no plunge)",
            minPredicted >= ferm - 0.0010
        )
        assertTrue(
            "windowed predictedFg ${timeline.predictedFg} should sit at the fermentSg asymptote (~1.0112)",
            timeline.predictedFg in 1.0095..1.0140
        )
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
