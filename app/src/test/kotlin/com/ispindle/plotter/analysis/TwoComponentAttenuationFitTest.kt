package com.ispindle.plotter.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.exp
import kotlin.random.Random

/**
 * Two-component fitter regression coverage.
 *
 * The prototype run (`/tmp/twogompertz.py` against the same brew2_late
 * capture) lands at RMS ≈ 1.38 mSG with FG ≈ 1.0162 and α ≈ 0.74. The
 * Kotlin port should converge to the same neighbourhood; small numerical
 * differences from a different LM step / bounds policy are absorbed by
 * loose bounds on each assertion.
 */
class TwoComponentAttenuationFitTest {

    @Test fun `synthetic biphasic ferment is fit with sub-millisG residual`() {
        // Two-phase ferment: phase 1 from 1.060 to 1.030 over h=0..30,
        // phase 2 from 1.030 to 1.012 over h=40..120. Both Gompertz-shaped
        // with their own (μ, λ). Sums perfectly to the model, so a clean
        // fit should land RMS at the noise floor.
        val rng = Random(0xB1FA51C)
        val og = 1.060; val fg = 1.012; val alpha = 0.625
        val drop = og - fg; val a1 = alpha * drop; val a2 = (1.0 - alpha) * drop
        val mu1 = 0.0028; val lam1 = 14.0; val mu2 = 0.00045; val lam2 = 65.0
        val n = 400
        val hours = DoubleArray(n) { it * (140.0 / (n - 1)) }
        val sgs = DoubleArray(n) { i ->
            val t = hours[i]
            val arg1 = mu1 * E / a1 * (lam1 - t) + 1.0
            val arg2 = mu2 * E / a2 * (lam2 - t) + 1.0
            og - a1 * exp(-exp(arg1.coerceIn(-700.0, 30.0))) -
                a2 * exp(-exp(arg2.coerceIn(-700.0, 30.0))) +
                rng.nextDouble() * 0.0008 - 0.0004
        }
        // No attenuation prior: this synthetic happens to sit at 80 %
        // attenuation, the default prior (μ=0.75, σ=0.10) is well within
        // 1σ and would soft-bias FG up by ~3 mSG. Disabling the prior
        // tests the fitter's mathematical recovery; the brew2 test below
        // checks behaviour under the realistic prior.
        val fit = TwoComponentAttenuationFit.fit(hours, sgs, attenuationPrior = null)
        assertNotNull("expected a fit on a clean synthetic biphasic series", fit)
        fit!!
        assertTrue("RMS ${fit.rmsResidual} should be at noise floor (< 0.5 mSG)",
            fit.rmsResidual < 0.0005)
        assertTrue("OG ${fit.og} should be near 1.060", fit.og in 1.058..1.062)
        assertTrue("FG ${fit.fg} should be near 1.012", fit.fg in 1.010..1.014)
        assertTrue("α ${fit.alpha} should match the synthetic 0.625",
            fit.alpha in 0.55..0.70)
        assertTrue("λ₁ ${fit.lambda1} should be earlier than λ₂ ${fit.lambda2}",
            fit.lambda1 < fit.lambda2)
    }

    @Test fun `brew2 capture residual halves vs the single Gompertz`() {
        val (h, sg) = loadHoursSg("ferment_brew2_late.csv")
        val single = AttenuationFit.fit(h, sg)
        val plats = PlateauDetector.detect(h, sg)
        val midPause = plats.firstOrNull { it.kind == Plateau.Kind.Mid }
        val hint = midPause?.let {
            TwoComponentAttenuationFit.BiphasicHint(it.startH, it.endH, it.sg)
        }
        val two = TwoComponentAttenuationFit.fit(h, sg, hint)
        assertNotNull("single fit", single); assertNotNull("two-component fit", two)
        single!!; two!!
        // The single fit dragged FG up to ~1.023 unconstrained (prototype
        // confirms 1.0230); the two-component fit lands at the observed
        // 1.016 without help. Both numbers are reported by the model — the
        // Bayesian prior contribution doesn't change them post-hoc.
        assertTrue(
            "single Gompertz RMS ${single.rmsResidual} should be high (~2.7 mSG)",
            single.rmsResidual > 0.0020
        )
        assertTrue(
            "two-component RMS ${two.rmsResidual} should halve it (< 1.7 mSG)",
            two.rmsResidual < 0.0017
        )
        assertTrue(
            "two-component RMS ${two.rmsResidual} should be < 0.7 × single ${single.rmsResidual}",
            two.rmsResidual < 0.7 * single.rmsResidual
        )
        assertTrue(
            "two-component FG ${two.fg} should land near the observed 1.0162 " +
                "(single Gompertz can't — it gets ~1.023 unconstrained)",
            two.fg in 1.014..1.019
        )
        assertTrue(
            "α ${two.alpha} should reflect ~70 % of drop in phase 1",
            two.alpha in 0.60..0.85
        )
        assertTrue("λ₁ ${two.lambda1} earlier than λ₂ ${two.lambda2}",
            two.lambda1 < two.lambda2)
        // Phase 1 should peak earlier and steeper than phase 2 — the
        // characteristic glucose/maltose vs maltotriose split.
        assertTrue("phase 1 should be faster (μ₁ ${two.mu1} > μ₂ ${two.mu2})",
            two.mu1 > two.mu2)
    }

    @Test fun `degenerate one-phase data lets alpha approach a bound`() {
        // A truly unimodal synthetic — the two-component fit should still
        // converge, but the model selector (separate from this fitter)
        // will see the indistinguishable RMS and pick single. Here we just
        // confirm the fitter doesn't NaN or crash on this case.
        val n = 300
        val og = 1.050; val fg = 1.012; val mu = 0.0025; val lam = 10.0
        val drop = og - fg
        val hours = DoubleArray(n) { it * (80.0 / (n - 1)) }
        val rng = Random(0xC0FFEE)
        val sgs = DoubleArray(n) { i ->
            val t = hours[i]
            val arg = mu * E / drop * (lam - t) + 1.0
            og - drop * exp(-exp(arg.coerceIn(-700.0, 30.0))) +
                rng.nextDouble() * 0.0006 - 0.0003
        }
        val fit = TwoComponentAttenuationFit.fit(hours, sgs)
        assertNotNull("expected a converged fit even on unimodal data", fit)
        fit!!
        assertTrue("RMS ${fit.rmsResidual} should still be small (< 1 mSG)",
            fit.rmsResidual < 0.001)
        // One phase should dominate — either α near a bound, or the
        // dominant phase carrying the bulk of the drop. Don't require a
        // specific corner; just confirm convergence.
        assertTrue("OG ${fit.og} near 1.050", fit.og in 1.048..1.054)
    }

    @Test fun `predict and rateAt agree with finite differences on a clean fit`() {
        val n = 200
        val og = 1.062; val fg = 1.013; val alpha = 0.7
        val a1 = alpha * (og - fg); val a2 = (1.0 - alpha) * (og - fg)
        val mu1 = 0.003; val lam1 = 12.0; val mu2 = 0.0006; val lam2 = 55.0
        val hours = DoubleArray(n) { it * (130.0 / (n - 1)) }
        val sgs = DoubleArray(n) { i ->
            val t = hours[i]
            val arg1 = mu1 * E / a1 * (lam1 - t) + 1.0
            val arg2 = mu2 * E / a2 * (lam2 - t) + 1.0
            og - a1 * exp(-exp(arg1.coerceIn(-700.0, 30.0))) -
                a2 * exp(-exp(arg2.coerceIn(-700.0, 30.0)))
        }
        val fit = TwoComponentAttenuationFit.fit(hours, sgs)!!
        // Finite-difference dSG/dt vs analytic rateAt at a couple of times.
        for (t in doubleArrayOf(20.0, 40.0, 80.0)) {
            val dt = 1e-3
            val fd = (fit.predict(t + dt) - fit.predict(t - dt)) / (2.0 * dt)
            val analytic = fit.rateAt(t)
            assertTrue(
                "rateAt($t) = $analytic should match FD $fd within 1e-5",
                kotlin.math.abs(analytic - fd) < 1e-5
            )
        }
    }

    @Test fun `timeToReach inverts predict`() {
        val n = 150
        val og = 1.065; val fg = 1.013; val alpha = 0.7
        val a1 = alpha * (og - fg); val a2 = (1.0 - alpha) * (og - fg)
        val hours = DoubleArray(n) { it * (140.0 / (n - 1)) }
        val sgs = DoubleArray(n) { i ->
            val t = hours[i]
            val arg1 = 0.003 * E / a1 * (12.0 - t) + 1.0
            val arg2 = 0.0006 * E / a2 * (55.0 - t) + 1.0
            og - a1 * exp(-exp(arg1.coerceIn(-700.0, 30.0))) -
                a2 * exp(-exp(arg2.coerceIn(-700.0, 30.0)))
        }
        val fit = TwoComponentAttenuationFit.fit(hours, sgs)!!
        for (target in doubleArrayOf(1.050, 1.030, 1.018)) {
            val t = fit.timeToReach(target)
            assertNotNull("timeToReach($target) should resolve", t)
            t!!
            val sgAtT = fit.predict(t)
            assertTrue(
                "predict(timeToReach($target)) = $sgAtT should match $target within 1e-4",
                kotlin.math.abs(sgAtT - target) < 1e-4
            )
        }
    }

    private fun loadHoursSg(name: String): Pair<DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val ts = mutableListOf<Long>(); val sgs = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val p = line.split(',')
            ts += p[tIdx].toDouble().toLong(); sgs += p[sgIdx].toDouble()
        }
        val t0 = ts.first()
        val h = DoubleArray(ts.size) { (ts[it] - t0) / 3_600_000.0 }
        return h to sgs.toDoubleArray()
    }
}
