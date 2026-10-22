# Architecture — CAD Slicer Engine

Status: shop v1, quote path, orders with several items, payment, mails and admin built. This
document describes what runs today and what the roadmap adds next. A component is only tagged **[implemented]** when it is exercised by tests
or by the local stack.

Legend: **[implemented]** working today · **[partial]** code exists but incomplete · **[planned]** not written yet

---

## 1. Business context

A print-on-demand shop for FDM parts, run by one person on a Bambu Lab A1 (more printers
later). A visitor uploads a model, gets a price derived from a real slice, chooses filament,
colour, quality and quantity, and orders. The owner reviews the order, prints it and ships it.
A back-office for the catalogue and the printers comes after the shop sells.

Prices come from a real slice, not a bounding box, because bounding-box pricing is wrong by
large factors for hollow, thin-walled or support-heavy geometry.

## 2. Deployment shape

One Spring Boot application, one PostgreSQL, the Bambu Studio CLI inside the backend image,
and the Angular app rendered by its own Node server, all under Docker Compose on one server.
Decisions: [ADR 0002](adr/0002-modular-monolith-and-background-worker.md) (monolith),
[ADR 0003](adr/0003-synchronous-quotes-and-local-storage.md) (synchronous quotes, local files).

```
                     one server (Docker Compose)
  +------------------------------------------------------------------+
  |  Caddy / reverse proxy  [planned]                                |
  |     |  /api/*                    |  /*                           |
  |     v                            v                               |
  |  cad-slicer-engine  <------>  pod-frontend (Angular SSR)         |
  |  [implemented]                [implemented]                      |
  |     |                 |                                          |
  |     v                 v                                          |
  |  PostgreSQL        Bambu Studio CLI (child process)              |
  |  [implemented:     [implemented]                                 |
  |   orders, items]                                                 |
  |                                                                  |
  |  order files volume [implemented, ADR 0003]                      |
  +------------------------------------------------------------------+
```

Locally the same stack runs from `docker-compose.local.yml` in the frontend repository, which
builds the backend image with the CLI and mounts both source trees.

## 3. Quote path — implemented

1. The upload page sends the model as multipart to `POST /api/quotes` with filament, colour,
   quality and quantity. The dev server proxies `/api`; in production the reverse proxy does.
2. `QuoteService` stages the file under a fixed name in a private temp directory and hashes
   it. A cache hit by hash and quality returns immediately and costs nothing; filament,
   colour and quantity are not part of the key.
3. Otherwise the client's rate limit is charged and a slicer slot is taken
   (`QuoteRateLimiter`, `SlicerCapacityGate`).
4. `SlicerProfileResolver` flattens the machine, process and filament profiles bundled with
   Bambu Studio, because the CLI ignores their `inherits` chain, and sets the bed type. The
   quote always slices with the PLA filament profile, whatever the customer chose
   ([ADR 0007](adr/0007-material-by-factor.md)).
5. `SlicerExecutionService` runs the CLI as an argument list (never a shell) for a single
   copy of the model, enforces the timeout, and parses `result.json` plus the G-code trailer
   of every plate. After a successful slice it runs `--info` once on the same input to learn
   whether the mesh is manifold (see section 5).
6. `QuotePricingService` first scales that PLA slice for the chosen filament by its measured
   time and length factors (`quote.material.*`: PETG 1.38 and 0.97, PCTG 1.48 and 0.97,
   [ADR 0007](adr/0007-material-by-factor.md)), then derives the quantity from it
   ([ADR 0006](adr/0006-quantity-by-arithmetic.md)): material scales linearly; print time is
   the slice minus a fixed per-plate overhead (`quote.plate-overhead-seconds`, 300 s) per
   copy, plus that overhead once per plate; the plates follow from how many copies of the
   footprint fit the bed (`quote.plate.*`). It derives mass from filament length and density,
   prices machine time and material for the batch, divides per unit, rounds to CHF 0.05 and
   adds a lead time. A footprint larger than the plate is a `422`.
7. The temp directory is deleted. Failures are RFC 9457 problem details; `429` and `503` carry
   `Retry-After`.

Measured on a Benchy with the A1 profiles: 33, 37 and 56 minutes print time for 0.24, 0.20 and
0.12 mm layers, about 12 g each. Batch slices on the production server (2026-10-21, table in
the feasibility note) showed the per-copy time flat from four copies on and about 300 s fixed
per plate, which is the overhead the arithmetic uses; it lands within 2 % of the slicer's own
batch at ten and twenty copies. PETG and PCTG slices of the same Benchy (2026-10-21, second
table in the feasibility note) take 1.38 and 1.48 times the PLA time at every quality and
extrude 0.97 of its length; those are the material factors, and they land within 3.3 % of the
slicer's own PETG and PCTG slices. Only the quality is still a real slice.

Since the upload-once change, `POST /api/models` keeps an upload for an hour under its
SHA-256 (`UploadedModelStore`) and `POST /api/quotes` accepts that `modelId` instead of the
file; a quote for a combination already sliced is a cache hit.

## 3a. Order path — implemented

An order holds one to ten items; each item is one model with its own quote, file and line
price ([ADR 0004](adr/0004-multi-item-orders.md)).

1. The checkout sends JSON to `POST /api/orders`: the customer, the items as
   `{modelId, fileName, filament, color, quality, quantity}` and the total it showed.
2. `OrderService` re-quotes every item through `QuoteService.quoteStoredModel` (a cache hit
   when the customer saw that quote) and computes `ItemTotals` per item (setup fee once per
   item plus unit price times quantity) and `OrderTotals` for the order (sum of lines plus
   flat shipping, VAT carved out when enabled). An unknown or expired model answers 404
   `model-expired` with the item's index, an items total other than the one the customer
   saw 409 `price-changed` with the fresh quotes and totals; in both cases nothing has been
   stored yet.
3. Each model is then copied from the upload store into the `OrderFileStorage` under a UUID
   key, the `Order` with its `OrderItem`s is created, the `PaymentGateway` starts a session
   (mock or Stripe Checkout with one line item per item plus shipping) and the order is saved.
   If anything fails after the copy, the files are removed again.
4. The provider's confirmation (`PaymentService`) marks the order `PAID` and publishes an
   event; after commit the notifier mails the customer and the owner, listing every item.
5. The owner moves the order along `PAID → IN_REVIEW → APPROVED → PRINTING → SHIPPED` and
   downloads each item's model; shipping starts the retention clock, after which
   `OrderRetentionService` deletes every item's file and the personal data.

## 3b. Customer accounts — implemented

Optional and passwordless ([ADR 0005](adr/0005-passwordless-customer-accounts.md)): a
one-time link by mail (`LoginLinkService`, through the `notification` module's `Mailer`)
opens a session kept in the HttpOnly `pod_session` cookie (`AccountSessionResolver`,
`SessionCookies`). The account lists every order under its e-mail or its id; a logged-in
checkout links the order through the order module's `CurrentAccount` port, and the nightly
retention run purges expired tokens and sessions through its `RetentionSweep` port. A
customer may add an optional password (`PasswordHasher`, bcrypt through the shared
`PasswordEncoder`; `PasswordLoginRateLimiter` against guessing) as a second way in; the
mail link stays the default and the recovery path, so there is no reset flow.

## 3c. Slice calibration — implemented

The pricing constants of ADR 0006 and 0007 are checked against real models
([ADR 0008](adr/0008-calibration-from-real-orders.md)) without re-slicing anything on the
shop's own initiative. Every slice the quote path performs (a cache miss: PLA, one copy, one
quality) is handed to the quote module's `SliceRecorder` port; `QuoteSliceRecorder` writes it
as a row with source `quote` into `slice_calibrations`, a failure is a warning and never a
failed quote. The full matrix runs only on request: the owner queues an order item or an
upload-store model (`CalibrationQueue`) and starts a pass with `run-now`; `CalibrationJob`
then hands everything waiting to `CalibrationRunner` in the background. Nothing is
scheduled. The runner slices each model eleven times, one capacity-gate slot at a
time: PLA, PETG and PCTG at the three qualities as single copies with the real filament
profiles, plus PLA medium as real batches of 4 and 10 (`SlicerExecutionService.executeBatchSlice`,
`--clone-objects N --arrange 1`). `CalibrationReportService` turns the rows into per-model
time and length ratios to the PLA slice, per-copy ratios and plate overheads of the batches,
and the deviation of the quote's own quantity formula from the measured batch, as statistics
over models, with row counts per source (`GET /api/admin/calibration/report`, `rows.csv`,
`rows` as JSON, `queue`).

## 4. Component responsibilities

| Component | Responsibility | State |
|---|---|---|
| `QuoteController`, `AdminController` | HTTP boundary, problem details, rate-limit key, admin reset | **[implemented]** |
| `QuoteService` | Staging, hashing, cache, rate limit, capacity gate, cleanup | **[implemented]** |
| `SlicerProfileResolver` | Flattened profiles per filament and quality, bed type; the quote uses the PLA profile | **[implemented]** |
| `SlicerExecutionService` | Single-copy CLI invocation, timeout, multi-plate parsing, warnings and manifold check; the arranged batch slice and the version probe for the calibration | **[implemented]** |
| `QuotePricingService` | Pure pricing arithmetic: material factors, quantity, costs, lead time | **[implemented]** |
| `FilamentCatalog` | Colours stocked per filament | **[implemented]** (configuration; back-office later) |
| `QuoteRateLimiter`, `SlicerCapacityGate`, `QuoteCache` | Guards; the cache is keyed by model hash and quality | **[implemented]** |
| `OrderService`, `Order`, `OrderItem`, `OrderTotals` | Orders with items placed by model id, server-side price, file copy, payment start | **[implemented]** |
| `PaymentService`, `StripeCheckoutGateway`, `MockPaymentGateway` | Hosted payment, signed webhook, mock for local development | **[implemented]** |
| `OrderMails`, `MailOrderNotifier` | Confirmation, owner notification, shipping notice | **[implemented]** |
| `AdminOrderService`, `OrderRetentionService` | Owner queue, per-item download, state machine, erasure | **[implemented]** |
| `AccountService`, `LoginLinkService`, `AccountSessionResolver` | Passwordless accounts: login links, cookie sessions, order list, deletion, optional bcrypt password as a second login (ADR 0005) | **[implemented]**, frontend pages pending |
| `QuoteSliceRecorder` | Records every quote slice through the quote module's `SliceRecorder` port (ADR 0008) | **[implemented]** |
| `CalibrationQueue`, `CalibrationRunner`, `CalibrationJob` | Manual queueing of order items and upload-store models, the eleven-slice matrix per queued model, a pass only on `run-now`, never scheduled (ADR 0008) | **[implemented]** |
| `CalibrationReportService`, `CalibrationAdminController` | Factor, overhead and arithmetic-error statistics over models; report, CSV, raw-row JSON and queue endpoints behind the admin login | **[implemented]**, admin page in pod-frontend |
| Persistence | Flyway `V1` to `V9`: orders, payments, erasure, order items, slicer warnings, customer accounts, slice calibrations and their queue, the optional account password | **[implemented]** |
| Angular app | Landing, upload with 3D preview and colour swatches, checkout, success page, admin | **[implemented]**, multi-item checkout pending |

## 5. Slicing: verified and guarded

Headless slicing is verified in Linux containers without an X server
([feasibility note](feasibility/bambu-studio-cli-headless.md)). Guards around the native
binary: per-request scratch directory, hard timeout with a stdout drain that cannot block it,
argument-list invocation with a fixed staged filename, bounded concurrency, upload size cap,
quantity cap. Tests imitate the CLI with a shell script; a tagged test against the real CLI is
an open roadmap item.

**Warnings and the manifold check.** A slice that misses the cache also collects what the
slicer had to say about the model, so the owner knows which models to open in Bambu Studio
before printing. Two sources feed the warnings: every field of `result.json` whose name
contains `warning` (wherever it sits, because the verified sample carried none and the
layout is not pinned) plus any console line that starts with `warning`; the manifold flag
comes from a second CLI call, `--info`, on the same inputs, which prints `manifold = yes|no`
per object with repair counters such as `open_edges`. That call writes into its own
`model-info` directory so its `result.json` cannot replace the slice's, has its own short
timeout (`slicer.info-timeout-seconds`, 20 s) and is best effort: a failure is logged and
leaves `manifold` null, never fails the quote, and cache hits never repeat it. Warnings are
deduplicated and bounded (ten entries of at most 200 characters), travel with the cached
slice, appear on the quote (`warnings`, `manifold`) and are copied onto each order item
(`slicer_warnings` as a JSON array in a text column, `manifold boolean null`; a text column
keeps the driver out of it and the value is never queried). The admin order derives
`needsInspection` from its items: any warning or `manifold == false`; an unchecked model
(null) does not raise the flag, so orders placed before the check do not all ask for review.

## 6. Known gaps

1. The slicer AppImage is pinned by version but not verified by checksum.
2. No test runs against the real CLI; a Bambu release that renames a profile would surface
   only at runtime.
3. Supports are off in the quote profile, so an overhanging model is priced and sliced but
   would not print well. Decision pending: enable automatic supports in the quote profile so
   the quote covers what is printed.
4. Prices use `double` with explicit rounding; orders and invoices should move money to
   `BigDecimal`.
5. In-memory rate limits and cache reset on restart and are per instance.
6. Copies per plate are a grid estimate of the bounding-box footprint in the better of two
   orientations; odd shapes may pack differently in Bambu Studio, and a model taller than the
   bed is caught by the slicer, not by the estimate (ADR 0006).
7. The material factors are three numbers measured on one model and one printer; a filament
   whose profile prints with another line width or flow needs its own measurement, and the
   factors have to be re-measured whenever a filament or process profile changes (ADR 0007).
   The calibration (ADR 0008) records every quote slice and measures the full matrix for
   models the owner queues, and reports the drift; applying a new factor is still a hand
   edit of `application.yml`.

## 7. Where security, testing and data protection enter

- **Quotes (built):** size cap, extension check, fixed staged filename, timeout, semaphore,
  per-client limit, cache, no stack traces in responses. Covered by unit and web-layer tests.
- **Orders (built):** personal data appears. Retention period, deletion path and lawful basis
  are in `docs/data-protection.md`; every item's file is keyed by UUID and deleted on
  schedule, and nothing is stored before all items are quoted and the price confirmed.
- **Payment:** the provider's hosted page handles card data; the backend only trusts signed
  webhooks.
- **Admin:** authenticated, never exposed without TLS.
- **Deployment:** TLS, no public database port, secrets from the environment, resource limits
  per container, backups with a verified restore.

Testing per phase: unit tests for pure logic, `@WebMvcTest` for the HTTP contract,
Testcontainers for persistence once tables exist, and slicer-dependent tests behind a tag.
