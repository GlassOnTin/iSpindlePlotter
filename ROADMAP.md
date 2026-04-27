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

## Out of scope

- Bayesian model averaging across logistic + Gompertz + Richards
  curves. The logistic fits the MTB iSpindel data well enough; adding
  alternative shape priors gives marginal gains on a phone app.
- Full No-U-Turn Sampler / HMC. The Laplace approximation in v1 is
  cheap and adequate; full MCMC adds dependencies and per-frame cost
  for arguably no user-visible improvement until v2 hyperparameter
  fitting motivates it.
