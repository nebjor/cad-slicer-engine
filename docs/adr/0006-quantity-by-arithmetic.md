# ADR 0006 — Slice one copy, derive every quantity by arithmetic

**Status:** Accepted; the filament dimension of decision 1 is replaced by
[ADR 0007](0007-material-by-factor.md) (addendum below)
**Date:** 2026-10-21
**Builds on:** ADR 0003 (synchronous quotes), ADR 0004 (one slice per order item).

## Context

A quote sliced the whole quantity as one batch: `--clone-objects N --arrange 1`, the CLI
spread the copies over as many plates as needed, and the totals came straight from the
slicer. That is the most faithful number available, and it was measured on the production
server with the Benchy (60 x 31 x 48 mm, PLA, 0.20 mm) on 2026-10-21:

| copies | request time | plates | print time total | per copy | grams per copy |
|---|---|---|---|---|---|
| 1 | 9 s | 1 | 2438 s | 2438 | 12.4 |
| 4 | 23 s | 1 | 8605 s | 2151 | 12.3 |
| 10 | 38 s | 1 | 21213 s | 2121 | 12.3 |
| 20 | 42 s | 2 | 42532 s | 2127 | 12.3 |

Three things follow from the table.

1. **Every quantity was its own slice and its own cache entry.** Changing the quantity on
   the upload page cost a full slice, 20 to 40 seconds for a mid-sized model, and a hit on
   the rate limit. The cap of 20 copies existed only to bound that cost.
2. **Material is linear.** 12.3 to 12.4 g per copy at every quantity.
3. **Print time is linear plus a fixed part per plate.** Per copy it is flat at about
   2121 to 2151 s from four copies on; the single copy carries about 300 s more
   (2438 - 2127 = 311 s): bed and nozzle heating, the purge line, the first layer. The second
   plate at twenty copies adds that overhead once more, which is why the per-copy figure
   rises slightly again from ten to twenty copies.

The batch slice also forced a detour for 3MF input, which the CLI refuses to clone: every
multi-copy 3MF quote exported the objects to STL first, a second CLI run.

## Decision

1. **One slice per model, filament and quality.** `QuoteService` always slices a single
   copy; `--clone-objects` and `--arrange` are gone from the invocation, and so is the STL
   export. `QuoteCache.key` is `hash|filament|quality`: a colour or quantity change is a
   cache hit and is answered in milliseconds.
2. **The quantity is arithmetic in `QuotePricingService`**, from the single-copy slice `S`
   (seconds) and the model's footprint `w x d` (mm):

   ```
   perCopy       = min(S, max(max(60, 0.25 * S), S - plateOverhead))
   plateOverhead = S - perCopy                       (the configured 300 s unless the floor applied)
   fitAlong(bed, part) = floor((bed - 2 * margin + gap) / (part + gap))
   copiesPerPlate = max(fitAlong(W, w) * fitAlong(D, d), fitAlong(W, d) * fitAlong(D, w))
   plates        = ceil(quantity / copiesPerPlate)
   totalSeconds  = quantity * perCopy + plates * plateOverhead
   totalGrams    = quantity * gramsPerCopy
   ```

   Costs, the unit price, the lead time and the rounding are unchanged and run on the totals.
   The properties are `quote.plate-overhead-seconds` (300) and `quote.plate.width-mm`,
   `depth-mm` (256, the A1 bed), `margin-mm` (10, bed edge kept free) and `gap-mm` (15, the
   arrange spacing). The floor keeps a copy at no less than a minute or a quarter of its own
   slice, so a tiny model is never priced at zero or negative time; the `min(S, ...)` bound
   keeps a quantity of one at exactly the slice's own time. A model whose footprint does not
   fit the plate once is rejected with the same 422 the slicer's own failures produce.
3. **The limit rises to 100.** The quantity no longer reaches the slicer, so the cap only
   bounds the order size. `OrderItemRequest` already validated 1 to 100; the options
   endpoint reports the new value.

## Check against the measurement

With the production Benchy slice (2438 s, 12.4 g) the arithmetic gives 15 copies per plate
(3 x 5 at 60 x 31 mm in a 236 mm usable square with 15 mm gaps), so:

| copies | plates | arithmetic | measured | deviation |
|---|---|---|---|---|
| 1 | 1 | 2438 s | 2438 s | 0 |
| 10 | 1 | 10 x 2138 + 300 = 21680 s | 21213 s | +2.2 % |
| 20 | 2 | 20 x 2138 + 2 x 300 = 43360 s | 42532 s | +1.9 % |

Two plates for twenty copies matches what the slicer's own arrange did. The pricing tests
pin these numbers.

## Alternatives considered

- **Keep the batch slice, cache per quantity.** Rejected: the cost is in the slice, not the
  cache, and the first request for every quantity still pays it. The frontend's quantity
  field would stay slow and the cap at 20.
- **Slice a full plate once and interpolate.** Rejected: it costs the full-plate slice for
  every model, including the many quoted once at quantity one, to save a two-percent
  deviation on the few quoted in volume.
- **Ask the slicer's arrange for the plate count only (`--arrange` without `--slice`).**
  Rejected: a second CLI run per quantity, which is what this decision removes.

## Consequences

**Benefits:** a quantity change is instant and free; one cache entry and one rate-limit hit
per model, filament and quality instead of one per quantity; the limit is 100; the 3MF
export path and its tests are gone; the slicer invocation is the simplest it can be.

**Trade-offs:** the batch time deviates from a real batch slice by a few percent (above, on
the safe side for the owner). What remains approximate is the plate packing: the footprint
is packed as a grid of the bounding box in the better of two orientations, which is what the
arrange does for compact parts; L-shaped, round or nested parts may pack tighter or looser in
Bambu Studio, and a part taller than the bed is not caught here (the slicer still reports it).
The overhead is a constant from one measurement on one printer; a second printer model or a
different start G-code needs its own figure.

## Revisit when

- Several printers with different beds or start routines are in service; the overhead and
  the plate then belong to the machine profile.
- The owner regularly sees a plate count on an order that differs from what Bambu Studio
  arranges; a real arrange call for quantities above one plate would then be worth its cost.

## Addendum 2026-10-21 — ADR 0007

The filament left the slice the same day: [ADR 0007](0007-material-by-factor.md) slices every
quote with the PLA profile and scales the single-copy time and length for PETG and PCTG by
measured factors in the same pricing step, before the arithmetic above. The cache key is
`hash|quality`; one slice per model and quality remains, the overhead, the plate packing and
the quantity arithmetic are unchanged.
