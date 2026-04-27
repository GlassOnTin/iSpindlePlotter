# Fermentation auto-model roadmap

The model that predicts FG, ETA, and the chart overlay lives in
`app/src/main/kotlin/com/ispindle/plotter/analysis/`. The fitter is a
4-parameter logistic
`SG(t) = FG + (OG − FG) / (1 + exp(k·(t − tMid)))`
solved by Levenberg-Marquardt. This is a sketch of where it is, where
it's going, and what each step actually buys us.

## v0 — bounded LM (shipped)

`LogisticFit.applyBounds` clamps FG to `[10%, 30%]` of `(OG − 1)` —
i.e. a hard `[70%, 90%]` window on attenuation. The LM minimises RSS
inside that box.

In Bayesian terms this is a uniform prior on attenuation over
`[70%, 90%]` with infinite weight at the edges. It works because mid-
fermentation, with no asymptote yet observed, FG is otherwise weakly
identified and the LM happily snug-fits FG against the data tail
("the asymptote is right where we are now") even when the lag plateau
clearly says more attenuation is coming.

**Limitations.**

- The hard cap misclassifies real `<70%` attenuation ferments — the LM
  is forced into a bad fit during descent, and the plateau gets read
  as Stuck rather than Complete.
- Predictive uncertainty is computed only on the FG marginal via the
  inverted Hessian (`Result.fgSigma`). Joint correlations between FG,
  k, and tMid — which dominate the spread on extrapolated SG and ETA
  — are not propagated.
- Chart band uses an ad-hoc linear taper from 0 at "now" to ±2σ_FG at
  "predicted finish", which is a rough proxy for posterior spread.

## v1 — Laplace approximation with soft attenuation prior

Replace the hard FG cap with a Gaussian prior on attenuation,
`atten ~ N(0.75, 0.10²)`, encoded as an augmented residual in the LM
(MAP in Bayesian terms). Keep wide hard bounds (`[10%, 50%]` on FG
drop = `[50%, 90%]` attenuation) only as sanity walls.

Then approximate the posterior with a Gaussian centred on the MAP
estimate, covariance `Σ = (J^T J)^-1 · σ²` from the LM's converged
Hessian. This is the **Laplace approximation**. Sample from it to
produce credible bands that respect joint correlations across all 4
parameters.

**Visible changes.**

- FG estimate is data-driven within a wider sanity range, with
  tightness inherited from the prior when data are uninformative.
- Chart overlay shows the 50% and 95% credible bands sampled from
  the posterior, replacing the linear ±2σ taper.
- ETA reported with a credible interval, not just a point.

**Non-changes.**

- Still a single point MAP under the hood; no MCMC. The Laplace
  approximation is exact only for Gaussian posteriors; for the
  logistic fit it's a local quadratic approximation that's pretty
  good when data are informative and breaks down when they're not.
- The Stuck / Complete / Lag classifiers in `Fermentation.kt` are
  unchanged — they read off rate-near-zero rather than the LM's FG.

## v1.1 — plateau detection

The 4-parameter logistic is structurally monotonic — one inflection,
one descent, smooth approach to FG. Real ferments do not always follow
that shape. Two non-monotonic features show up in the user's iSpindle
captures:

- A **lag plateau** at the start (already classified as `State.Lag`,
  but worth shading for chart legibility).
- A **diauxic shift** mid-ferment: yeast finishes the easy sugars
  (glucose, then maltose) and pauses for a few hours while it re-tunes
  enzyme expression to attack the harder ones (maltotriose). Captured
  in `ferment_capture_2026-04-27_35h.csv` at h 27–30 (SG ≈ 1.029).

The single logistic cannot reproduce the second one — it has one S-curve
to spend, and the prior keeps it on the gross trajectory rather than
the fine structure. Surfaced via `PlateauDetector.detect`: rolling-
window slope test, threshold at 0.3 mSG/h, classifies runs as
`Lag` / `Mid` / `Tail` based on their position relative to the data
boundaries. Shipped to `Fermentation.State.Active` /
`State.Slowing.plateaus`, shaded on the SG chart with a small label,
and called out in the estimate text as "paused at 1.0290 for 3.0 h".

**Non-changes.**

- The logistic still smooths through the plateau rather than respecting
  it. The trust gates (`notHuggingData`, attenuation ≥ 50 %) still pass
  on the user's data, so FG and ETA come out reasonable. Visually the
  fit is a slight under-bend across the plateau region.

**Limitations to flag.**

- The detector treats *any* non-descent slope as flat, so a foam crash
  or temperature spike that rises SG briefly will be lumped in with the
  surrounding plateau. In practice this is what we want — neither is
  fermentation activity — but it means the reported plateau SG is a
  mean, not necessarily where the ferment actually paused.
- The MIN_PLATEAU_HOURS = 2.5 h floor can mask a brief diauxic micro-
  pause. Loosening it admits noise blips (verified empirically on the
  35 h capture: a 2 h false positive at h 13–14 disappeared at 2.5 h).

## v2 — hierarchical priors across the user's history

Each completed ferment in the Room database becomes one observation
of the population's `(OG, attenuation, k_max, lag duration)` joint.
A new ferment's prior is the population posterior; a brewer who
mostly does ales will see attenuation pull toward 75% with a tight σ,
while a wild-fermenter will see a wider spread. Re-fit hyperparameters
once per N completed brews (or on app start, since N is small).

**Cost.** Needs a "completed ferment" record + completion criterion,
fed by the v0 Stuck/Complete classifier. Likely needs ~5–10 finished
brews before the prior is informative; until then the global default
prior from v1 fills in.

**What it unlocks.** Personalised priors. A user who runs Belgian
saisons with 90% atten will get a fit that doesn't over-pull toward
75%; a user who does sweet stouts will get the opposite. Same
machinery handles k and lag duration too — useful for early-phase
ETA when only the lag is observed.

## v3 — robust likelihood and online inference

Two independent improvements that compose with v1/v2.

**Student-t likelihood.** Replace the Gaussian residual term with a
heavy-tailed Student-t (df ≈ 4–6). Outliers — e.g., the iSpindle gets
bumped, a temperature spike — currently get rejected by an ad-hoc 3σ
filter in `Fermentation.tailFit`. Student-t makes that principled:
the tail residual cost grows logarithmically rather than
quadratically, so individual bad readings stop dragging the fit. No
explicit outlier rejection step.

**State-space form / online filtering.** Treat SG(t) as a latent
process with the logistic as the deterministic mean and a Kalman or
particle filter advancing the state with each new reading. The
overlay updates incrementally instead of refitting from scratch every
time. Two practical wins:

- Cheaper updates per new sample (`O(1)` vs `O(n)` LM iterations).
- Natural regime-change detection: `lag → active → slowing → stuck`
  drops out of the filter's innovation residual rather than the
  current threshold cascade.

## Deferred — biphasic logistic model

The mid-plateau detector annotates the chart but doesn't change what the
LM fits. To actually model the diauxic shift, replace the single
logistic with a sum of two scaled sigmoids:

```
SG(t) = FG + (OG − FG) · [w · σ(k₁·(t − τ₁)) + (1 − w) · σ(k₂·(t − τ₂))]
        where σ(x) = 1 / (1 + exp(x))
```

Recovers single-stage behaviour at `w → 1` (or `τ₂ ≈ τ₁`). Adds three
parameters (`k₂, τ₂, w`) → 7 total. Identifiability is borderline on a
30–40 h capture; needs a Beta prior on `w` peaked at 1 so the model
prefers single-stage and only commits to biphasic when the data really
demands it. Initialisation comes from the v1.1 detector: anchor `τ₁` /
`τ₂` on the plateau midpoint when one is detected, otherwise start
single-logistic-equivalent.

**Why deferred.** Useful only when we have multiple captured ferments
showing the biphasic shape generalises across yeast strains and worts.
Going straight to a 7-parameter fit on one observation would over-fit
the shoulder of any single-stage ferment.

## v1.2 — fermentation segmentation and pre-filtering

The Room database for a device collects readings from every brew, plus
calibration sessions, plus periods where the iSpindle was sitting in
air or a propane bottle. v1 / v1.1 treated the whole window as one
continuous time series, which mis-frames the model and the chart when
readings span multiple ferments or non-fermenting interludes.

`FermentSegmenter.detect` (`analysis/FermentSegmenter.kt`) walks the
readings and cuts on:

- a time gap > 8 h between consecutive readings, OR
- a single-step SG rise > 5 mSG that *also* lands at a SG higher than
  anything seen in the last 6 h. The second clause is what
  distinguishes "fresh wort poured in" (sustained new high) from "brief
  noise excursion that recovered to baseline" (rise lands at a level
  the SG was already at minutes earlier).

Each candidate span is then promoted to a `FermentSegment` only if
duration ≥ 6 h, max−min SG ≥ 5 mSG, and the first-half median lies at
least drop/4 above the second-half median (real downward trend, not
a temperature-driven excursion that happens to span the drop floor).

Threaded into the Graph screen as a navigation row above the
TimeWindow chips: `◀ Ferment k/N · MM-dd → MM-dd · −X SG ▶ [All]`.
Selecting a ferment scopes the entire chart and the Bayesian analyser
to that segment's time range; tapping a TimeWindow chip falls back to
"All" (since chip + segment scoping would conflict). Default is the
most recent ferment, since that's the brew the user is actively
watching.

**Limitations.**

- Conservative criteria mean short ferments (< 6 h, e.g., a quick
  cider top-up) and low-attenuation ferments (< 5 mSG drop, e.g., kombucha 2F)
  will be missed and the user falls back to All.
- A new ferment with OG below the previous ferment's recent SG range
  and no time gap will fail the rise-confirmation check (the SG was
  already that high in the past 6 h). In practice batches are
  separated by hours of cleaning so the time-gap criterion catches
  this.
- Segment boundaries are heuristic — there's no user override for
  "this is one ferment, don't split here" or "split here". Manual
  trim-before still exists as the escape hatch.

## Out of scope

- Bayesian model averaging across logistic + Gompertz + Richards
  curves. The logistic fits the MTB iSpindel data well enough; adding
  alternative shape priors gives marginal gains on a phone app.
- Full No-U-Turn Sampler / HMC. The Laplace approximation in v1 is
  cheap and adequate; full MCMC adds dependencies and per-frame cost
  for arguably no user-visible improvement until v2 hyperparameter
  fitting motivates it.
