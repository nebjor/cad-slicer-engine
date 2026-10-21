# ADR 0007 — Material by factor: slice the reference filament, scale the others

**Status:** Accepted
**Date:** 2026-10-21
**Builds on:** ADR 0006 (one slice per model, derive every quantity by arithmetic).

## Context

After ADR 0006 a quote was one slice per model, filament and quality. Quantity and colour
were free, but a filament change still cost a full slice and a rate-limit hit: a visitor who
compares PLA, PETG and PCTG at the three qualities starts the slicer nine times for one model,
twenty to forty seconds each on the production host.

What the filament changes in a slice was measured on the production server with the Benchy
(60 x 31 x 48 mm), one copy, A1 profiles with tree supports, on 2026-10-21:

| filament | coarse 0.24 | medium 0.20 | high 0.12 | filament length mm (c / m / h) |
|---|---|---|---|---|
| PLA | 2170 s | 2442 s | 3686 s | 4339 / 4169 / 4065 |
| PETG | 2934 s | 3341 s | 5263 s | 4207 / 4042 / 3941 |
| PCTG | 3218 s | 3570 s | 5547 s | 4207 / 4042 / 3941 |

Three things follow from the table.

1. **Print time scales by a near-constant factor per filament.** PETG takes 1.35 / 1.37 / 1.43
   times the PLA time at the three qualities (mean 1.38), PCTG 1.48 / 1.46 / 1.50 (mean
   1.48). The factor comes from the filament profile, not the model: lower speeds and
   accelerations, more cooling slowdown.
2. **Extruded length scales by exactly 0.97 at every quality**, and PETG and PCTG extrude the
   same length to the millimetre: both profiles share one flow setting. Mass then only differs
   by the density, which `FilamentType` already carries.
3. **Quality does not scale by a constant.** The ratio between the qualities depends on the
   model (its height sets the layer count, its overhangs the support volume), so the quality
   stays a real slice.

## Decision

1. **One slice per model and quality, with the reference filament.** `QuoteService` always
   slices with the PLA profile (`QuoteService.REFERENCE_FILAMENT`); the filament the customer
   chose never reaches the resolver or the CLI. `QuoteCache.key` is `hash|quality`. The
   warnings and the manifold check are captured on that one slice as before.
2. **The filament is two factors in `QuotePricingService.price`**, applied to the single-copy
   PLA slice `S` (seconds) and `L` (mm) before the quantity arithmetic of ADR 0006:

   ```
   S_f = S * timeFactor(f)            quote.material.time-factor:   PLA 1.0, PETG 1.38, PCTG 1.48
   L_f = L * lengthFactor(f)          quote.material.length-factor: PLA 1.0, PETG 0.97, PCTG 0.97
   gramsPerCopy = volume(L_f) * density(f)
   perCopy, plateOverhead, plates, totals = ADR 0006 on S_f
   ```

   The measured time ratios compare whole single-copy prints, overhead included, so the factor
   scales the whole slice; the plate overhead subtracted per plate stays the configured
   constant. (A PETG bed heats a little longer, which the factor absorbs; it is not worth a
   second constant.) `FilamentType` is the key type, like `material-price-chf-per-kg`.
3. **A filament without an entry is scaled by 1.0** and `QuotePricingService` logs a WARN for
   it at startup; a factor of zero or less refuses to start.
4. **The response does not change shape.** `filament` and `machineProfile` name what the
   customer chose; the per-unit and total figures are the scaled ones.

## Check against the measurement

Arithmetic from the PLA slice against what PETG and PCTG sliced themselves:

| filament, quality | arithmetic | measured | deviation | length mm (arithmetic / measured) |
|---|---|---|---|---|
| PETG coarse | 2170 x 1.38 = 2995 s | 2934 s | +2.1 % | 4209 / 4207 |
| PETG medium | 2442 x 1.38 = 3370 s | 3341 s | +0.9 % | 4044 / 4042 |
| PETG high | 3686 x 1.38 = 5087 s | 5263 s | -3.3 % | 3943 / 3941 |
| PCTG coarse | 2170 x 1.48 = 3212 s | 3218 s | -0.2 % | 4209 / 4207 |
| PCTG medium | 2442 x 1.48 = 3614 s | 3570 s | +1.2 % | 4044 / 4042 |
| PCTG high | 3686 x 1.48 = 5455 s | 5547 s | -1.7 % | 3943 / 3941 |

The PETG medium Benchy comes out at 12.4 g (12.35 before rounding, the same the measured 4042
mm give at 1.27 g/cm3), the PCTG high Benchy at 11.7 g. `QuotePricingServiceTest` pins the
PETG medium and PCTG high rows with a 3 % tolerance and the whole table with 3.5 %;
`QuoteServiceTest` pins that PLA, PETG and PCTG at one quality are one slicer run and that
another quality still slices.

## Alternatives considered

- **Keep one slice per filament and prefetch the other filaments in the background.**
  Rejected: nine slices per model instead of three, on a host with two slicer slots, to
  reproduce numbers a constant predicts within a few percent.
- **One time factor per filament and quality** (PETG 1.35 / 1.37 / 1.43). Rejected for now:
  nine numbers from one model, and nothing yet shows the spread is a property of the quality
  rather than of the Benchy. Three constants are easier to re-measure; revisit when more
  models have been compared.
- **Derive the factor from the profiles' speed settings.** Rejected: the CLI's time estimate
  folds in accelerations, cooling slowdowns and minimum layer times; measuring the ratio is
  cheaper and more honest than modelling it.

## Consequences

**Benefits:** a filament change is instant and free, like quantity and colour. Three slices
(one per quality) cover a model entirely; once the upload page prefetches the other two
qualities, every later change on the page is a cache hit. One cache entry and one rate-limit
hit per model and quality. The machine profile and the warnings are unchanged.

**Trade-offs:** the PETG and PCTG figures deviate from a real slice of that filament by a few
percent (-3.3 % to +2.1 % on the Benchy; the mean factor spreads the error over the
qualities). The factors are tied to the profiles they were measured with: a new filament
profile, a changed process profile or another printer needs a new measurement of all three
qualities (`scripts/measure-slice.sh -F PETG` against the PLA run of the same model; the
backend now flattens only the PLA filament profile, so the script has to be pointed at a
directory holding the other ones with `-p`). The PETG and PCTG profile names stay in
`FilamentType` for exactly that purpose.

**What is still a real slice:** the quality. **What the order path does:** nothing new; the
quote prices, and the profile the owner prints with is the owner's business.

## Revisit when

- A filament with a different flow joins the catalogue (another line width, TPU, a filled
  material): its length factor is not 0.97, and its time factor may not be constant over the
  qualities.
- The owner compares the quote with Bambu Studio's own estimate for PETG or PCTG and sees a
  deviation beyond a few percent on real customer models; a per-quality factor would then be
  the next step.
- A second printer model is in service; the factors, like the plate overhead of ADR 0006,
  then belong to the machine profile.
