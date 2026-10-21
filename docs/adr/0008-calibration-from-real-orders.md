# ADR 0008 — Calibration from real orders

**Status:** Accepted (owner decision 2026-10-21: the dataset is gathered passively, the full
matrix runs only on request)
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

The shop now has what it lacked then: real customer models are sliced with every quote, and
the slicer runs headless. Checking the constants by hand means slicing each model eleven
times in Bambu Studio and typing numbers into a spreadsheet, which nobody does. What the
owner did not want is the shop re-slicing every order on its own at night: the slicer is the
host's scarce resource, and a dataset that grows without anyone asking for it is work nobody
chose.

## Decision

1. **Every slice the quote path performs is recorded, nothing is re-sliced.** `QuoteService`
   calls its new `SliceRecorder` port after every cache miss (a single copy, the PLA profile,
   any quality); the `calibration` module implements it and writes one row into
   `slice_calibrations` with source `quote`: the model's hash, upload name, size, bounding box
   and triangles, the quality, plates, print time, filament length and mass, warnings count,
   manifold, the slicer's version and the wall-clock of the run. Recording happens outside
   the slicer slot and any failure is a WARN, never a failed quote. Repeats are kept; the
   report takes the latest row per model and cell. The dataset therefore grows from the
   shop's own work at zero extra slicer cost, and by itself answers how long real models
   take at each quality.
2. **The full matrix runs only on request.** The owner queues an order item
   (`POST /api/admin/calibration/queue/order/{number}/{position}`) or a model still in the
   upload store (`POST /api/admin/calibration/queue/{modelId}`, the file is copied into
   `calibration.dir` so its expiry cannot lose it). Nothing is queued automatically: the
   order module has no hook into the calibration. A queued model is sliced eleven times: PLA,
   PETG and PCTG at coarse, medium and high as a single copy with the real filament profiles
   (`SlicerProfileResolver.resolve`), plus PLA medium as a real arranged batch of 4 and of
   10 copies (`SlicerExecutionService.executeBatchSlice`, `--clone-objects N --arrange 1`,
   the invocation the quote path gave up in ADR 0006; the CLI does not clone 3MF input, so a
   3MF gets the nine single cells only). Each run is a row with source `order` or `manual`;
   a failed cell is a warning and the model goes on, a model with no cell at all is marked
   failed with the last reason.
3. **The queue is worked through at night, and only when switched on.** `calibration.enabled`
   is **false by default**; when true, the pass at `calibration.cron` (03:00, before the
   retention sweep at 03:30) takes up to `calibration.max-models-per-night` (5) queued
   models, oldest first, one slicer slot at a time through the `SlicerCapacityGate` with a
   long wait (ten minutes a cell), so a customer who quotes at night finds the second slot
   free. `POST /api/admin/calibration/run-now` starts the same pass in the background
   regardless of the switch, for testing; two passes never overlap. Quote recording is not
   affected by the switch.
4. **The report does the arithmetic the ADRs did by hand.** `GET /api/admin/calibration/report`
   computes, per model from the latest row of each cell and then as n / mean / median / min /
   max over models:
   - `timeFactor[f][q]` = t(f, q, 1) / t(PLA, q, 1) and `lengthFactor` likewise on the extruded
     length, plus `all` pooled over the qualities (n = 0 until a manual matrix has run, since
     quote rows are PLA only);
   - `quantity[N].perCopyRatio` = (t(N) / N) / t(1) and `plateOverheadSeconds` =
     (N · t(1) − t(N)) / (N − 1) where the batch fit one plate;
   - `arithmeticError[N]` = the deviation of `QuotePricingService.batchPrintSeconds` (the
     formula the price uses, with the configured overhead and plate) from the measured batch,
     in percent, as signed mean, mean absolute and maximum absolute.
   The configured constants travel with the report, `sources` counts the rows per origin
   (`quote`, `order`, `manual`), `rows.csv` gives every raw row for a spreadsheet. The
   report's `models` is the number of calibrated geometries, the list of them is
   `calibratedModels`.

## Turning the report into new factors

The constants stay hand-edited in `application.yml`; the report informs, it does not act.
Once a dozen or more models have been through the manual matrix:

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

Recording a quote slice is one database row. The manual matrix is eleven slicer runs of
twenty to forty seconds on the production host: four to eight minutes a model, up to forty
minutes a night for the default of five models. A two-plate batch of a large part can take
longer, which the 120 s timeout bounds. Disk: rows are a few hundred bytes each; files are
not copied for order items.

## Privacy

A calibration row holds geometry statistics (bounding box, triangles, size), slice figures,
the model's file name as the customer called it, its hash and, for a queued order item, the
order number. No name, address or e-mail, no file, nothing that links a quote row to a
person. The queue entry references the order item's file by its storage key only; when the
order is erased the file goes with it and the entry is skipped with a note on its next turn,
so erasure is not delayed or undone by the calibration. A model queued by hand from the
upload store is copied into `calibration.dir`; those copies are the owner's own test models
or models the owner chose to keep, and are deleted by hand.

## Alternatives considered

- **Re-slice every ordered model at night automatically.** Built first, then rejected by the
  owner: eleven slicer runs per order that nobody asked for, on the host's scarce resource,
  for a dataset that the quote path already feeds for free in its PLA dimension. The matrix
  stays available on request.
- **Measure by hand with `scripts/measure-slice.sh` when in doubt.** Rejected: it is what the
  ADRs assumed and it did not happen; the Benchy is still the only model measured.
- **Calibrate on the quote path**, slicing the matrix whenever a slot is idle after a quote.
  Rejected: an upload page that costs eleven slices per visitor competes with the next
  visitor's quote.
- **Adjust the factors automatically from the medians.** Rejected: a price that changes
  overnight without anyone looking is not what a one-person shop wants, and a bad batch
  (a slicer update, a broken profile) would change prices before it is noticed.

## Consequences

**Benefits:** every real slice the shop performs is kept with its geometry, so print time
per quality over real models is known without any extra run; the material factors and the
quantity arithmetic can be checked on chosen models with one request; a slicer or profile
update shows in the `slicer_version` column the first quote after it. The batch invocation
of the CLI is kept alive and tested without touching the quote path.

**Trade-offs:** a new module (`calibration`) with two tables and a port in the quote module;
PETG and PCTG statistics stay empty until the owner queues models; the report is only as good
as the number of calibrated models, and nothing stops the owner from reading three models as
a trend.

## Revisit when

- The report shows per-quality time factors that are tight across models but apart from each
  other: move to factors per filament and quality.
- A second printer joins: rows then need a machine column and the constants move to the
  machine profile (ADR 0006, 0007).
- The owner finds queueing by hand too slow to build a dataset: an automatic intake of
  ordered models exists in this branch's history and can return behind the `enabled` switch.
