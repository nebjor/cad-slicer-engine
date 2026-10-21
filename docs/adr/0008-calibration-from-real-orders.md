# ADR 0008 — Calibration from real orders

**Status:** Accepted
**Date:** 2026-10-21
**Builds on:** ADR 0006 (quantity by arithmetic), ADR 0007 (material by factor).

## Context

Since ADR 0006 and 0007 a quote is one slice per model and quality with the PLA profile;
every quantity and every other filament is arithmetic on top of it. The constants behind that
arithmetic, `quote.plate-overhead-seconds` (300) and `quote.material.time-factor` /
`length-factor` (PETG 1.38 / 0.97, PCTG 1.48 / 0.97), were measured on one model, the Benchy,
on one day. ADR 0007 itself says what is missing: nothing shows whether the spread between
the qualities (PETG 1.35 / 1.37 / 1.43) is a property of the quality or of the Benchy, and a
tall, support-heavy or flat customer part may scale differently. Both ADRs list "the owner
compares the quote with Bambu Studio on real customer models and sees a deviation beyond a
few percent" as the trigger to revisit.

The shop now has what it lacked then: real customer models arrive with every order, the
server is idle at night, and the slicer runs headless. Checking the constants by hand means
slicing each model eleven times in Bambu Studio and typing numbers into a spreadsheet, which
nobody does.

## Decision

1. **Every ordered model is calibrated at night.** When an order is placed, the order module
   tells its new `OrderPlacedListener` port; the `calibration` module implements it and
   queues every item of the order once per geometry (`calibration_queue`). The owner can also
   queue any order item or any model still in the upload store from the admin API (a model
   from the store is copied into `calibration.dir` so its expiry cannot lose it).
2. **The matrix per model is eleven slicer runs**: PLA, PETG and PCTG at coarse, medium and
   high as a single copy with the real filament profiles (`SlicerProfileResolver.resolve`),
   plus PLA medium as a real arranged batch of 4 and of 10 copies
   (`SlicerExecutionService.executeBatchSlice`, `--clone-objects N --arrange 1`, the
   invocation the quote path gave up in ADR 0006; the CLI does not clone 3MF input, so a 3MF
   gets the nine single cells only). Each run is one row in `slice_calibrations`: geometry
   statistics, the slice figures, plates, warnings count, manifold, the slicer's version from
   its `--help` banner and the wall-clock of the run. A failed cell is a warning and the model
   goes on; a model with no cell at all is marked failed with the last reason.
3. **Quotes keep priority.** The pass runs at `calibration.cron` (03:00, before the retention
   sweep at 03:30), works through `calibration.max-models-per-night` (5) queued models oldest
   first, and takes one slicer slot at a time through the `SlicerCapacityGate` with a long
   wait (ten minutes a cell), so a customer who quotes at night finds the second slot free and
   never waits behind the calibration. Two passes never overlap; `POST /api/admin/calibration/run-now`
   starts one in the background for testing. `calibration.enabled=false` stops both the
   intake and the nightly pass.
4. **The report does the arithmetic the ADRs did by hand.** `GET /api/admin/calibration/report`
   computes, per model from the latest row of each cell and then as n / mean / median / min /
   max over models:
   - `timeFactor[f][q]` = t(f, q, 1) / t(PLA, q, 1) and `lengthFactor` likewise on the extruded
     length, plus `all` pooled over the qualities;
   - `quantity[N].perCopyRatio` = (t(N) / N) / t(1) and `plateOverheadSeconds` =
     (N · t(1) − t(N)) / (N − 1) where the batch fit one plate;
   - `arithmeticError[N]` = the deviation of `QuotePricingService.batchPrintSeconds` (the
     formula the price uses, with the configured overhead and plate) from the measured batch,
     in percent, as signed mean, mean absolute and maximum absolute.
   The configured constants travel with the report; `rows.csv` gives every raw row for a
   spreadsheet. The report's `models` is the number of calibrated geometries, the list of them
   is `calibratedModels`.

## Turning the report into new factors

The constants stay hand-edited in `application.yml`; the report informs, it does not act.
When a dozen or more models are in:

1. Compare `timeFactor.PETG.all.median` and `timeFactor.PCTG.all.median` with the configured
   1.38 and 1.48. If the median differs by more than about 0.03, set the new median. If the
   per-quality medians (`coarse`, `medium`, `high`) spread by more than about 0.08 while each
   is tight across models, the factor is a property of the quality: that is the moment for
   per-quality factors (ADR 0007, alternatives).
2. `lengthFactor.*.all.median` should sit at 0.97; a drift means a changed flow setting in a
   filament profile and the slice should be re-measured after the next Bambu release anyway.
3. `quantity.4.plateOverheadSeconds.median` and `quantity.10.plateOverheadSeconds.median` are
   the direct readings for `quote.plate-overhead-seconds`; they agree with each other when the
   model is linear. `arithmeticError.*.meanPercent` says whether the quote is biased (positive
   is high, on the safe side) and `maxAbsPercent` how far a single model can be off.
4. Re-measure after every slicer or profile update: the `slicer_version` column separates
   the generations in `rows.csv`.

## Cost

Eleven slicer runs of twenty to forty seconds on the production host: four to eight minutes a
model, up to forty minutes a night for the default of five models. A two-plate batch of a
large part can take longer, which the 120 s timeout bounds. Disk: the rows are a few hundred
bytes each; files are not copied for order items.

## Privacy

A calibration row holds geometry statistics (bounding box, triangles, size), slice figures,
the model's file name as the customer called it, its hash and the order number. No name,
address or e-mail, no file. The queue entry references the order item's file by its storage
key only; when the order is erased the file goes with it and the entry is skipped with a note
on its next turn, so erasure is not delayed or undone by the calibration. A model queued by
hand from the upload store is copied into `calibration.dir`; those copies are the owner's own
test models or models the owner chose to keep, and are deleted by hand. The order number is
the business record's key and stays, as the order itself does (CO art. 958f).

## Alternatives considered

- **Measure by hand with `scripts/measure-slice.sh` when in doubt.** Rejected: it is what the
  ADRs assumed and it did not happen; the Benchy is still the only model measured.
- **Calibrate on the quote path**, slicing the matrix whenever a slot is idle after a quote.
  Rejected: an upload page that costs eleven slices per visitor competes with the next
  visitor's quote; the night is free.
- **Adjust the factors automatically from the medians.** Rejected: a price that changes
  overnight without anyone looking is not what a one-person shop wants, and a bad batch
  (a slicer update, a broken profile) would change prices before it is noticed.
- **A sample of orders instead of all.** Not needed at today's volume; `max-models-per-night`
  bounds the cost and the queue simply grows if orders outpace the nights.

## Consequences

**Benefits:** the pricing constants rest on every model the shop has printed, the report
shows when they drift and how far the quantity arithmetic is off, and a slicer or profile
update is caught the first night after it. The batch invocation of the CLI is kept alive and
tested without touching the quote path.

**Trade-offs:** eleven slices per order at night; a new module (`calibration`) with two
tables; `OrderService` gains a listener port. The report is only as good as the number of
calibrated models, and nothing stops the owner from reading three models as a trend.

## Revisit when

- The report shows per-quality time factors that are tight across models but apart from each
  other: move to factors per filament and quality.
- A second printer joins: rows then need a machine column and the constants move to the
  machine profile (ADR 0006, 0007).
- Order volume makes the queue grow faster than five models a night: raise the limit or
  sample.
