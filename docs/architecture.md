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
   it. A cache hit by hash and parameters returns immediately and costs nothing.
3. Otherwise the client's rate limit is charged and a slicer slot is taken
   (`QuoteRateLimiter`, `SlicerCapacityGate`).
4. `SlicerProfileResolver` flattens the machine, process and filament profiles bundled with
   Bambu Studio, because the CLI ignores their `inherits` chain, and sets the bed type.
5. `SlicerExecutionService` runs the CLI as an argument list (never a shell) with the whole
   quantity cloned and arranged across plates, enforces the timeout, and parses `result.json`
   plus the G-code trailer of every plate. A multi-copy 3MF is exported to STL first.
6. `QuotePricingService` derives mass from filament length and density, prices machine time
   and material for the batch, divides per unit, rounds to CHF 0.05 and adds a lead time.
7. The temp directory is deleted. Failures are RFC 9457 problem details; `429` and `503` carry
   `Retry-After`.

Measured on a Benchy with the A1 profiles: 33, 37 and 56 minutes print time for 0.24, 0.20 and
0.12 mm layers, about 12 g each; ten copies on one plate need 18% less machine time than ten
single prints.

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

## 4. Component responsibilities

| Component | Responsibility | State |
|---|---|---|
| `QuoteController`, `AdminController` | HTTP boundary, problem details, rate-limit key, admin reset | **[implemented]** |
| `QuoteService` | Staging, hashing, cache, rate limit, capacity gate, cleanup | **[implemented]** |
| `SlicerProfileResolver` | Flattened profiles per filament and quality, bed type | **[implemented]** |
| `SlicerExecutionService` | CLI invocation, timeout, multi-plate parsing, STL export | **[implemented]** |
| `QuotePricingService` | Pure pricing arithmetic | **[implemented]** |
| `FilamentCatalog` | Colours stocked per filament | **[implemented]** (configuration; back-office later) |
| `QuoteRateLimiter`, `SlicerCapacityGate`, `QuoteCache` | Guards | **[implemented]** |
| `OrderService`, `Order`, `OrderItem`, `OrderTotals` | Orders with items placed by model id, server-side price, file copy, payment start | **[implemented]** |
| `PaymentService`, `StripeCheckoutGateway`, `MockPaymentGateway` | Hosted payment, signed webhook, mock for local development | **[implemented]** |
| `OrderMails`, `MailOrderNotifier` | Confirmation, owner notification, shipping notice | **[implemented]** |
| `AdminOrderService`, `OrderRetentionService` | Owner queue, per-item download, state machine, erasure | **[implemented]** |
| Persistence | Flyway `V1` to `V5`: orders, payments, erasure, order items | **[implemented]** |
| Angular app | Landing, upload with 3D preview and colour swatches, checkout, success page, admin | **[implemented]**, multi-item checkout pending |

## 5. Slicing: verified and guarded

Headless slicing is verified in Linux containers without an X server
([feasibility note](feasibility/bambu-studio-cli-headless.md)). Guards around the native
binary: per-request scratch directory, hard timeout with a stdout drain that cannot block it,
argument-list invocation with a fixed staged filename, bounded concurrency, upload size cap,
quantity cap. Tests imitate the CLI with a shell script; a tagged test against the real CLI is
an open roadmap item.

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
6. Dimensions reported for a multi-part 3MF at quantity > 1 are those of the largest part.

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
