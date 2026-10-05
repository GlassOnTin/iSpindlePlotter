package com.ispindle.plotter.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spunding (pressure-fermentation) readiness gates.
 *
 * The gates must stay in brewer terms: a 10-psi step once the ferment is
 * clearly going ("strong"), a 15-psi step near terminal gravity. Threshold
 * functions are pinned exactly; the readiness mapping is pinned per state,
 * including the phases that qualify by stage rather than by numbers.
 */
class SpundingTest {

    /** This week's live brew: OG 1.1137, Gompertz FG 1.0283 (85.4 mSG drop). */
    private val ogBrew = 1.1137
    private val fgBrew = 1.0283

    // ── threshold functions ──────────────────────────────────────────────

    @Test fun `psi10 threshold on this week's brew`() {
        assertEquals(1.10089, Spunding.psi10Sg(ogBrew, fgBrew), 0.0005)
    }

    @Test fun `psi15 threshold sits at 90 percent attenuation`() {
        assertEquals(1.03684, Spunding.psi15Sg(ogBrew, fgBrew), 0.0005)
    }

    @Test fun `small beer engages the absolute floor on the 10 psi gate`() {
        // 15 % of a 30-mSG drop is only 4.5 mSG; the floor raises it to 5,
        // so the gate reads 1.035 rather than 1.0355.
        assertEquals(1.035, Spunding.psi10Sg(1.040, 1.010), 1e-9)
    }

    @Test fun `psi15 stays proportional on the small beer`() {
        assertEquals(1.013, Spunding.psi15Sg(1.040, 1.010), 0.0005)
    }

    @Test fun `psi15 sits below psi10 whenever the drop is wide enough to gate on`() {
        // Reachable domain: fitted FGs are ≥ 50 %-attenuation-plausible,
        // the prior implies 25 %. For OGs with drops above the floor the
        // 15-psi target must always be the later (lower-SG) crossing.
        for (og in doubleArrayOf(1.030, 1.040, 1.050, 1.072, 1.1137)) {
            for (frac in doubleArrayOf(0.90, 0.75, 0.50, 0.30)) {
                val fg = og - frac * (og - 1.000)
                assertTrue(
                    "og $og fg $fg: psi15 ${Spunding.psi15Sg(og, fg)} must be below psi10 ${Spunding.psi10Sg(og, fg)}",
                    Spunding.psi15Sg(og, fg) < Spunding.psi10Sg(og, fg)
                )
            }
        }
    }

    // ── readiness mapping (numbers path) ─────────────────────────────────

    @Test fun `0 percent attenuated holds with the brew's 10 psi target`() {
        val r = Spunding.evaluate(activeState(ogBrew, ogBrew, fgBrew)) as? Spunding.Readiness.Hold
        assertTrue("expected Hold, got $r", r != null)
        assertEquals(1.10089, r!!.psi10Sg, 0.0005)
    }

    @Test fun `84 percent attenuated is 10 psi ready with the raise target`() {
        val r = Spunding.evaluate(activeState(ogBrew, 1.0420, fgBrew)) as? Spunding.Readiness.TenPsi
        assertTrue("expected TenPsi, got $r", r != null)
        assertEquals(1.03684, r!!.psi15Sg, 0.0005)
    }

    @Test fun `95 percent attenuated is 15 psi ready`() {
        val current = ogBrew - 0.95 * (ogBrew - 1.000)
        assertEquals(Spunding.Readiness.FifteenPsi, Spunding.evaluate(activeState(ogBrew, current, fgBrew)))
    }

    @Test fun `both gates are inclusive at their boundary`() {
        assertEquals(
            Spunding.Readiness.FifteenPsi,
            Spunding.evaluate(activeState(ogBrew, Spunding.psi15Sg(ogBrew, fgBrew), fgBrew))
        )
        assertEquals(
            Spunding.Readiness.TenPsi(Spunding.psi15Sg(ogBrew, fgBrew)),
            Spunding.evaluate(activeState(ogBrew, Spunding.psi10Sg(ogBrew, fgBrew), fgBrew))
        )
    }

    @Test fun `lag maps to hold using the attenuation prior`() {
        val r = Spunding.evaluate(Fermentation.State.Lag(og = 1.050, current = 1.049, durationHours = 3.0))
        val expected = Spunding.psi10Sg(1.050, Fermentation.attenuationPriorFg(1.050))
        assertEquals(Spunding.Readiness.Hold(expected), r!!)
    }

    @Test fun `prior-source activity still evaluates`() {
        // OG 1.050 at 1.020 with the 75 % prior FG: 10-psi gate crossed,
        // 15-psi gate (1.01625) not yet.
        val r = Spunding.evaluate(
            activeState(1.050, 1.020, Fermentation.attenuationPriorFg(1.050), source = Fermentation.PredictionSource.Default)
        ) as? Spunding.Readiness.TenPsi
        assertTrue("expected TenPsi, got $r", r != null)
        assertEquals(1.01625, r!!.psi15Sg, 0.0005)
    }

    @Test fun `weak wort clamps the hold target up to the 15 psi milestone`() {
        // OG 1.006 / prior 1.0015: the floored 10-psi target (1.0010) sits
        // below the 15-psi target (1.00195) — gate order would invert and
        // the printed SG could never be crossed. Expect the clamp.
        val og = 1.006
        val r = Spunding.evaluate(
            activeState(og, 1.0055, Fermentation.attenuationPriorFg(og), source = Fermentation.PredictionSource.Default)
        ) as? Spunding.Readiness.Hold
        assertTrue("expected Hold, got $r", r != null)
        assertEquals(
            Spunding.psi15Sg(og, Fermentation.attenuationPriorFg(og)),
            r!!.psi10Sg, 0.0
        )
    }

    @Test fun `degenerate inputs hide the line`() {
        assertNull("OG at/below water has no meaningful gate", Spunding.evaluate(activeState(0.999, 0.999, 0.980)))
        assertNull("FG at/above OG is nonsense", Spunding.evaluate(activeState(1.020, 1.010, 1.030)))
    }

    // ── readiness mapping (phase authority) ──────────────────────────────

    @Test fun `conditioning is 15 psi ready regardless of its numbers`() {
        // The state itself guarantees SG settled onto the asymptote; the
        // raw numbers of this instance would read Hold, which must lose.
        assertEquals(
            Spunding.Readiness.FifteenPsi,
            Spunding.evaluate(Fermentation.State.Conditioning(og = 1.050, fg = 1.045))
        )
    }

    @Test fun `clarifying is 15 psi ready`() {
        val settling = SettlingEvent(startH = 60.0, endH = 66.0, fgWithYeast = 1.0110, clarifiedSg = 1.0085, dropSg = 0.0025)
        assertEquals(
            Spunding.Readiness.FifteenPsi,
            Spunding.evaluate(Fermentation.State.Clarifying(og = 1.050, current = 1.008, settling = settling))
        )
    }

    @Test fun `cold crash is 15 psi ready`() {
        assertEquals(
            Spunding.Readiness.FifteenPsi,
            Spunding.evaluate(
                Fermentation.State.ColdCrash(
                    og = 1.050, apparentSg = 1.0060, fermentSg = 1.0110,
                    temperatureC = 2.0, fermentTemperatureC = 19.5, durationH = 30.0
                )
            )
        )
    }

    // ── states that hide the line ────────────────────────────────────────

    @Test fun `stuck hides the line - a stalled ferment gains nothing from pressure`() {
        assertNull(
            Spunding.evaluate(
                Fermentation.State.Stuck(og = 1.050, current = 1.032, expectedFg = 1.0125, flatHours = 40.0)
            )
        )
    }

    @Test fun `insufficient hides the line`() {
        assertNull(Spunding.evaluate(Fermentation.State.Insufficient))
    }

    // ── the shared prior helper ──────────────────────────────────────────

    @Test fun `attenuation prior is the 75 percent formula with its floor`() {
        assertEquals(1.028425, Fermentation.attenuationPriorFg(1.1137), 1e-6)
        assertEquals(1.0025, Fermentation.attenuationPriorFg(1.010), 1e-9)
        assertEquals(0.998, Fermentation.attenuationPriorFg(0.990), 1e-9)
    }

    // ── real capture walk ────────────────────────────────────────────────

    /**
     * `ferment_capture_settling.csv` (4-day capture: lag → strong descent →
     * FG plateau → settle → cold crash). The readiness line must be present
     * throughout the ferment, must show Hold-or-10psi mid-descent, and must
     * be 15-psi-ready across the pre-crash plateau, the settle, and the
     * crash tail, with no crossing into null on the way.
     */
    @Test fun `real capture walks the readiness ladder`() {
        val (hours, sgs, temps) = loadCaptureWithTemp("ferment_capture_settling.csv")
        val timeline = Fermentation.buildTimeline(hours, sgs, temps = temps)!!
        val readinessAt = { t: Double ->
            Spunding.evaluate(Fermentation.stateAt(timeline, hours, sgs, t))
        }

        val lastH = timeline.lastH
        val crash = timeline.coldCrashOnsetH
        // Mid-descent, before the pre-crash plateau: either not-strong-yet
        // (Hold) or 10-psi-ready (TenPsi) — but 15-psi must not have fired,
        // because SG hasn't reached the near-FG region yet.
        val earlyT = (crash ?: 60.0) * 0.3
        val early = readinessAt(earlyT)!!
        assertTrue(
            "at h $earlyT expected Hold or TenPsi (got $early)",
            early is Spunding.Readiness.Hold || early is Spunding.Readiness.TenPsi
        )

        // From the first ~15 % crossing on, the line stays present through
        // to the end (never drops back to null).
        for (t in (earlyT + 1.0).toInt()..lastH.toInt()) {
            assertTrue("readiness must not disappear at h $t", readinessAt(t.toDouble()) != null)
        }

        // Pre-crash plateau (settling window and after): phase authority
        // means FifteenPsi, keyed off fermentSg — not the cold-affected SG.
        val settling = timeline.settling!!
        val midSettle = (settling.startH + settling.endH) / 2.0
        assertEquals(Spunding.Readiness.FifteenPsi, readinessAt(midSettle))
        assertEquals(Spunding.Readiness.FifteenPsi, readinessAt(lastH))
    }

    /** Loader per [SettlingDetectorTest] / [LongConditioningTest] fixture convention. */
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

    private fun activeState(
        og: Double,
        current: Double,
        fg: Double,
        source: Fermentation.PredictionSource = Fermentation.PredictionSource.Gompertz
    ) = Fermentation.State.Active(
        og = og,
        current = current,
        ratePerHour = -0.002,
        predictedFg = fg,
        etaToFinishHours = null,
        source = source
    )
}