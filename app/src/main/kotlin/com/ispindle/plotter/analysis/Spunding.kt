package com.ispindle.plotter.analysis

import kotlin.math.max

/**
 * Spunding (pressure-fermentation) readiness, expressed as SG gates the
 * fermentation state card can print.
 *
 * At fermentation temperature, carbonating a beer to typical serving
 * carbonation inside a sealed vessel needs roughly 10–15 psi held through
 * the end of fermentation (≈0.5 volumes of CO₂ per 0.001 SG point of
 * extract fermenting, re-absorbed as it ferments out). But pressure works
 * against yeast: CO₂ in solution suppresses cell growth, so clamping the
 * vessel to pressure while the ferment is young slows attenuation and can
 * stall it entirely. The gates below encode the usual practice:
 *
 *  * **Hold** — ferment not yet strong. Leave the pressure relief open
 *    (or very low) until the drop is clearly under way.
 *  * **~10 psi** — once ~15 % of the OG→FG drop is done (with an absolute
 *    floor for small beers) the ferment is strong enough to tolerate ~10 psi.
 *  * **~15 psi** — bung up near terminal gravity (the last ~10 % of the
 *    drop), or whenever the state machine has moved past Active/Slowing
 *    into the near-FG phases. Held through a subsequent cold crash this
 *    leaves the beer at final carbonation: cold beer in a headspaced,
 *    CO₂-purged vessel re-absorbs CO₂, it does not lose it.
 *
 * Above ~18 psi there is no carbonation benefit — that region only stresses
 * the yeast, so no gate points there.
 *
 * Pure JVM (no Android imports) so the mapping can be tested directly by
 * constructing [Fermentation.State] values.
 */
object Spunding {

    /** ~10 psi becomes safe once this fraction of the OG→FG drop is done — "fermentation is strong". */
    private const val TEN_PSI_ATTENUATION = 0.15

    /** Absolute floor on the 10-psi drop for small beers: ~5 SG points, so weak worts don't gate at OG. */
    private const val TEN_PSI_MIN_DROP = 0.005

    /** ~15 psi is safe within the last ~10 % of the drop — "near terminal gravity". */
    private const val FIFTEEN_PSI_ATTENUATION = 0.90

    /** SG at which ~10 psi becomes safe: `og − max(0.15·(og−fg), 0.005)`. */
    fun psi10Sg(og: Double, fg: Double): Double =
        og - max(TEN_PSI_ATTENUATION * (og - fg), TEN_PSI_MIN_DROP)

    /** SG at which ~15 psi becomes safe: `og − 0.90·(og−fg)`. */
    fun psi15Sg(og: Double, fg: Double): Double =
        og - FIFTEEN_PSI_ATTENUATION * (og - fg)

    /** Spunding readiness carried by a state; payload is the SG target the UI prints. */
    sealed class Readiness {
        /** Not strong yet — hold off; early pressure suppresses yeast. Carries the ~10-psi target. */
        data class Hold(val psi10Sg: Double) : Readiness()

        /** Strong enough for ~10 psi. Carries the SG to raise to ~15 psi. */
        data class TenPsi(val psi15Sg: Double) : Readiness()

        /** At or near FG — bung up to ~15 psi. */
        data object FifteenPsi : Readiness()
    }

    /**
     * Spunding readiness for a fermentation state, or null when the line
     * should be hidden. The mapping is:
     *
     *  * `Insufficient` → null (no OG — nothing to gate on).
     *  * `Lag` → numbers path against the 75 % attenuation prior
     *    ([Fermentation.attenuationPriorFg]); provably always [Readiness.Hold],
     *    because lag-phase SG sits within the detector's small drift of OG,
     *    far above the 10-psi target.
     *  * `Active` / `Slowing` → numbers path against `predictedFg` (a fit or
     *    the prior — the estimate text already labels whichever it is).
     *  * `Conditioning` / `Clarifying` → [Readiness.FifteenPsi] by phase: SG
     *    has settled onto its asymptote, so ≥ 90 % attenuation holds against
     *    any plausible FG. Phase mapping (not a numbers path) avoids the
     *    drifting-`current` flicker of the Clarifying settling step.
     *  * `ColdCrash` → [Readiness.FifteenPsi] — the bung-up-before-crash case.
     *    Never key off `apparentSg`: the crash-time reading is a thermal/
     *    CO₂-density artifact; the pre-crash `fermentSg` is the FG.
     *  * `Stuck` → null. A stalled ferment gains nothing from pressure
     *    (pressure further depresses the stalled yeast population), and its
     *    `expectedFg` is the prior, not a fit — "near FG" is false in both
     *    mechanism and number. The stuck guidance already covers remediation.
     */
    fun evaluate(state: Fermentation.State): Readiness? = when (state) {
        is Fermentation.State.Insufficient -> null
        is Fermentation.State.Lag ->
            readinessFromNumbers(state.og, state.current, Fermentation.attenuationPriorFg(state.og))
        is Fermentation.State.Active ->
            readinessFromNumbers(state.og, state.current, state.predictedFg)
        is Fermentation.State.Slowing ->
            readinessFromNumbers(state.og, state.current, state.predictedFg)
        is Fermentation.State.Conditioning -> Readiness.FifteenPsi
        is Fermentation.State.Clarifying -> Readiness.FifteenPsi
        is Fermentation.State.ColdCrash -> Readiness.FifteenPsi
        is Fermentation.State.Stuck -> null
    }

    /**
     * Numbers-path decision. Gates compare on SG — the same numbers printed
     * as targets — not on an attenuation fraction, so for small beers the
     * absolute floor moves the gate exactly where the displayed target says.
     *
     * For very weak worts the floor can push the 10-psi target *below* the
     * 15-psi target (the gate order would invert, and the printed Hold
     * target would sit under the SG the 15-psi gate fires at). Clamping the
     * 10-psi target up to the 15-psi target in that band keeps the printed
     * milestone real; the readiness then walks Hold straight into
     * FifteenPsi, which is the honest behaviour for a near-water OG.
     */
    private fun readinessFromNumbers(og: Double, current: Double, fg: Double): Readiness? {
        if (og <= 1.000 || fg >= og) return null
        val target15 = psi15Sg(og, fg)
        val target10 = max(psi10Sg(og, fg), target15)
        return when {
            current <= target15 -> Readiness.FifteenPsi
            current <= target10 -> Readiness.TenPsi(target15)
            else -> Readiness.Hold(target10)
        }
    }
}