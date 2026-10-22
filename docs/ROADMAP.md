# Roadmap — shop v1

Goal: a print-on-demand shop that takes an upload, quotes it, sells it and lets the owner
print and ship. One server, one developer, no infrastructure that the shop does not need.
Decisions behind the scope are in [ADR 0003](adr/0003-synchronous-quotes-and-local-storage.md).

Each phase lists **acceptance criteria written as a command to run and a result to check**.
A phase is done when every box can be ticked by someone else on a clean checkout. Security,
testing and data protection stay inside the phase that creates the risk.

Legend: ☐ open · ☑ done

| Phase | Theme | State |
|---|---|---|
| 0 | Architecture and feasibility | ☑ |
| 1 | Build hygiene and runnable baseline | ☑ |
| 2 | Quote path | ☑ with three open hardening items |
| 3 | Orders | ☑ |
| 3b | Multi-item orders | ☑ |
| 3c | Customer accounts | ☑ (e2e check on the server open) |
| 4 | Payment | ☑ (test mode verified; first live order pending) |
| 5 | Notifications, legal and data protection | ☑ |
| 6 | Admin | ☑ |
| 7 | Deployment | ☑ |
| 8 | Printer integration | deferred |
| 9 | Agentic access (MCP) | ☐ planned |

This replaces the earlier eight-phase plan (object storage, pre-signed uploads, asynchronous
jobs, RabbitMQ). What that plan called phases 3, 4, 5 and 7 was built as the synchronous
quote path below; the rest is deferred by decision, see the end of this document.

---

## Phase 0 — Architecture and feasibility ☑

Unchanged from the original plan: architecture overview, ADR 0001 and 0002, the headless
slicing feasibility note, build and boot state recorded by running them.

## Phase 1 — Build hygiene and runnable baseline ☑

- ☑ `./mvnw -B clean verify` succeeds on a clean checkout (wrapper 3.3.4, Maven 3.9.9).
- ☑ Every credential in `application.yml` reads `${ENV_VAR:default}`; `.env.example` lists
  them; `git check-ignore .env` exits 0.
- ☑ `docker compose up -d postgres` then `./mvnw spring-boot:run` logs `Started`.
- ☑ Flyway owns the schema (`V1__baseline.sql`), Hibernate validates only.
- ☑ Tests run in CI: `./mvnw -B verify`, 68 tests, green.
- ☑ README describes the service as it is.

## Phase 2 — Quote path ☑ (hardening open)

Built ahead of the original phases 2 to 5 as a synchronous API; see ADR 0003.

- ☑ `POST /api/quotes` with an STL or 3MF returns print time, material, plates, dimensions,
  an itemised price and a lead time; `GET /api/quotes/options` lists filaments, colours,
  qualities and limits.
- ☑ Machine, process and filament profiles are passed explicitly and flattened; a test asserts
  the leaf overrides the parent and `curr_bed_type` is set.
- ☑ Success is the exit code plus `result.json#return_code`; G-code paths are discovered as
  `plate_*.gcode`; mass is length × density; all pinned by tests against a fake CLI.
- ☑ One copy is sliced per model, filament and quality; every quantity is derived from that
  slice by arithmetic: material linear, print time per copy plus a fixed overhead per plate,
  plates from the footprint on the bed. A quantity change is a cache hit, the limit is 100,
  and the figures stay within 2 % of the slicer's own batch for the Benchy (2026-10-21,
  ADR 0006, `QuotePricingServiceTest`). Replaces the batch slice of the whole quantity
  (`--clone-objects`, 3MF exported to STL), which cost a full slice per quantity and capped
  it at 20.
- ☑ Material by factor (2026-10-21, ADR 0007): one slice per model and quality with the PLA
  profile; PETG and PCTG scale its print time by 1.38 and 1.48 and its filament length by
  0.97 (`quote.material.*`), measured on the production server with the Benchy at all three
  qualities (feasibility note, section 6). A filament change is a cache hit like quantity
  and colour, and the figures stay within 3.3 % of the slicer's own PETG and PCTG slices
  (`QuotePricingServiceTest`, `QuoteServiceTest`). Only the quality is still a real slice.
- ☑ Calibration from real orders (2026-10-21, ADR 0008): every slice the quote path performs
  is recorded as a `slice_calibrations` row (source `quote`: PLA, one copy, the quality, plates,
  time, length, mass, warnings, manifold, slicer version, duration) through the quote module's
  `SliceRecorder` port, a failure never fails the quote; nothing is re-sliced on its own. The
  owner queues order items and upload-store models by hand for the full matrix, PLA / PETG /
  PCTG x coarse / medium / high as single copies with the real filament profiles plus PLA
  medium as real batches of 4 and 10 (`--clone-objects`, kept only for this), worked through
  one capacity-gate slot at a time, only on `run-now`: nothing is scheduled, ever (the nightly
  pass and its `calibration.enabled` / `cron` / `max-models-per-night` settings were removed
  the same day, ADR 0008 addendum). `V8__slice_calibrations.sql`;
  `GET /api/admin/calibration/report` gives per-model time and length ratios, per-copy ratios,
  plate overheads, the quantity arithmetic's error as statistics over models and row counts per
  source, `rows.csv` the raw rows, `rows` the newest raw rows as JSON (`SliceCalibrationRow`,
  filter by source, limit capped at 1000), `queue` the state (`QuoteServiceTest`,
  `QuoteSliceRecorderTest`, `CalibrationRunnerTest`, `CalibrationReportServiceTest`,
  `CalibrationQueueTest`, `CalibrationJobTest`, `CalibrationAdminControllerTest`,
  `SlicerExecutionServiceTest`). The admin page for the report is built in pod-frontend;
  applying a new factor stays a hand edit of `application.yml`.
- ☑ Guards: 50 MB cap, 120 s timeout with a non-blocking drain, two-slot semaphore (`503`),
  10/min and 100/h per client (`429`), cache by model hash, scratch directory removed. A
  12-request burst from one address yields `200`, `503` and `429` as designed.
- ☑ Colour catalogue per filament, validated and echoed. A colour may carry its own material
  price (silk and Bambu Basic cost more than matte PolyTerra); the catalogue fills in the
  filament's price otherwise and the cache keeps slices, so a colour change re-prices without
  slicing (2026-10-21, `FilamentCatalogTest`, `QuoteServiceTest`).
- ☑ Frontend: upload with 3D preview and swatches, debounced requests, readable `429`/`503`
  messages, 17 tests, production build within budgets.
- ☑ The Bambu Studio AppImage is verified by SHA-256 in both Dockerfiles (production image in
  this repository, development image in pod-frontend); a wrong checksum fails the build.
- ☐ A test tagged `slicer` slices the Benchy with the real CLI and asserts layer height 0.2,
  print time between 30 and 45 minutes and mass between 10 and 14 g. It does not run in
  plain `./mvnw verify`. The test exists (`RealSlicerBenchyTest`, 2026-10-21): surefire
  excludes the tag by default, `./mvnw -B test -Pslicer` runs it, and it skips itself where
  the CLI or the Benchy is missing. In the development stack:
  `docker compose -f docker-compose.local.yml exec backend ./mvnw -B test -Pslicer`
  (pod-frontend repository). Ticked once it has passed there.
- ☑ `scripts/measure-slice.sh` slices a model N times with the backend's own flattened
  profiles and CLI arguments, for quantity 1 and 4, and prints wall-clock, CPU time and peak
  RSS per run as a table (GNU time, with a `/proc` fallback). The command and the table
  layout are in the feasibility note, section 6 (2026-10-21).
- ☐ Wall-clock, peak RSS and CPU for a realistic model (≥ 10 MB STL with supports) are
  measured and recorded in the feasibility note, and the timeout is set from that number.
  The script above exists; the measurement on the production host and the numbers in the
  note are still open.
- ☑ Decision recorded and applied (2026-10-19): the shop prints with automatic tree supports,
  so the quote profile has `enable_support=1`, `support_type=tree(auto)`
  (`slicer.enable-support`); models without overhangs get none and keep their price.

- ☑ Upload once, quote by id (2026-10-20): `POST /api/models` keeps the model for an hour under
  its content hash, `POST /api/quotes` accepts `modelId` instead of the file, so switching
  filament or quality costs a slice but no upload. Expired ids get 404 `model-expired` and the
  client re-uploads (`UploadedModelStoreTest`, `QuoteServiceTest`, `QuoteControllerTest`).

## Phase 3 — Orders ☑

The first personal data and the first application table.

- ☑ `POST /api/orders` turns a quote into an order: the price is recomputed server-side from
  the uploaded model, the customer's expected total is checked (`409` with the current quote
  when it differs), and file hash, parameters, the full price breakdown and a quote expiry
  (14 days) are stored verbatim; `OrderPersistenceTest` round-trips them.
- ☑ The model file is stored under a server-generated UUID in the order-files volume;
  `LocalOrderFileStorageTest` asserts the key contains no part of the client filename and
  that foreign keys are refused.
- ☑ Guest checkout collects name, e-mail, shipping address; validation errors are problem
  details with an `errors` map, asserted by `OrderControllerTest`.
- ☑ `V2__orders.sql` creates the table; `OrderPersistenceTest` (Testcontainers, skipped
  without Docker) boots the context against Postgres, Hibernate validates the schema.
- ☑ Money in orders is `BigDecimal`; `OrderTotalsTest` pins CHF 0.05 rounding, flat shipping
  and the VAT switch (off while not registered, inclusive 8.1% when on).
- ☑ Order states: `NEW → PAID → IN_REVIEW → PRINTING → SHIPPED`, plus `CANCELLED`; illegal
  transitions are rejected (`OrderStatusTest`, `OrderPersistenceTest`).
- ☑ The checkout page submits the order and shows the order number; price changes and field
  errors are handled (`checkout.component.spec.ts`).

Decided with the owner on 2026-10-19: not VAT registered (switch in `orders.vat-enabled`),
flat shipping CHF 9.00, shipping to CH only, 14 days price validity, 90 days retention.

## Phase 3b — Multi-item orders ☑

One parcel and one payment for several parts; see [ADR 0004](adr/0004-multi-item-orders.md).
Built 2026-10-21, the frontend checkout against the new contract is the next step.

- ☑ `order_items` holds one row per model with its file, parameters, slice figures and line
  price; `V5__order_items.sql` moves every existing order into one item and drops the
  per-model columns from `orders`. `OrderPersistenceTest` (Testcontainers) round-trips a
  two-item order; the migration has not yet been run against a Postgres outside CI.
- ☑ `POST /api/orders` is JSON: customer, 1 to 10 items by `modelId` (from `POST /api/models`),
  expected items total. Every item is re-quoted through the stored-model path (a cache hit
  when the customer saw the quote), then copied into the order-files storage. An expired
  model answers 404 `model-expired` with `itemIndex`, a changed price 409 `price-changed`
  with `currentQuotes` and `totals`; neither stores anything (`OrderServiceTest`,
  `OrderControllerTest`).
- ☑ Setup fee per item, shipping once per order; `OrderTotalsTest` pins the sum across
  items. `OrderResponse` and `AdminOrderResponse` list the items; the OpenAPI contract test
  pins `OrderItemRequest`, `OrderItemResponse` and `AdminOrderItemResponse`.
- ☑ Stripe Checkout shows one line item per order item plus "Versand (Pauschale)"; a line
  that does not split into whole rappen per unit is charged once at its total, so the Stripe
  amount always equals the order total (`StripeCheckoutGatewayTest`).
- ☑ Confirmation and shipping mails list every item with its line total (`OrderMailsTest`);
  erasure deletes every item's file before the personal data (`OrderRetentionServiceTest`);
  the admin downloads a model per item at `/items/{position}/model`
  (`AdminOrderServiceTest`, `AdminOrderControllerTest`).
- ☑ Frontend: the cart (`/warenkorb`, IndexedDB-backed, hover mini-cart in the header) holds
  several models, the checkout sends `modelId`s, the success page and the admin detail list
  the items, `model-expired` re-uploads one file (pod-frontend `CartService`, `cart.component`).

## Phase 3c — Customer accounts ☑ (e2e check open)

Passwordless login by e-mail link, un-deferred 2026-10-21 because the shop sells and
returning customers retype their address; see [ADR 0005](adr/0005-passwordless-customer-accounts.md).

- ☑ `POST /api/account/login-links` mails a one-time link valid 15 minutes and answers 202
  whatever happened; 3 links per address per 15 minutes and 10 per client per hour, in
  memory (`LoginLinkServiceTest`, `LoginLinkRateLimiterTest`, `AccountMailsTest`).
- ☑ `POST /api/account/sessions` spends the token, creates the account on first use (address
  prefilled from the newest order under that e-mail) and sets `pod_session`: HttpOnly,
  Secure, SameSite=Lax, 30 days, extended once a day. Tokens and session secrets are stored
  as SHA-256 only (`AccountServiceTest`, `SessionCookiesTest`).
- ☑ `GET/PUT /api/account`, `GET /api/account/orders` (every non-erased order under the
  account's e-mail, case-insensitive, or its id, newest first), `DELETE /api/account/sessions`,
  `DELETE /api/account`; 401 `not-logged-in` without a live session, 415 for anything but
  JSON as the CSRF guard (`AccountControllerTest`).
- ☑ `V7__customer_accounts.sql`: `accounts`, `login_tokens`, `account_sessions`,
  `orders.account_id` with `ON DELETE SET NULL`; `OrderPersistenceTest` (Testcontainers)
  covers the query and the cascade.
- ☑ A logged-in checkout links the order to the account while keeping the checkout e-mail
  (`OrderServiceTest`, `OrderControllerTest`); the admin list shows `accountEmail`
  (`AdminOrderServiceTest`); the confirmation mail links the order status page
  (`OrderMailsTest`); the nightly sweep purges expired tokens and sessions
  (`OrderRetentionJobTest`, `AccountRetentionSweepTest`).
- ☑ Optional password (ADR 0005 addendum, 2026-10-22): `PUT`/`DELETE /api/account/password`
  set or remove a bcrypt hash (`V9__account_password.sql`), `POST /api/account/sessions/password`
  opens the same cookie session, every failure is 401 `invalid-credentials`, 5 attempts per
  address per 15 minutes and 20 per client per hour; the registration mail carries only the
  password link with the one token (ADR 0005 note 2026-10-22; `AccountServiceTest`, `AccountControllerTest`,
  `PasswordLoginRateLimiterTest`, `AccountMailsTest`).
- ☑ Frontend: `/konto/anmelden` signs in with password or registers by mail link, the link
  lands on `/konto/passwort` and continues to the cart or the landing page, `/konto` shows
  account, address form and order list, the checkout prefills from the account, the header
  menu shows the login state (pod-frontend `account/*`, 255 tests).
- ☐ Verified end to end on the server with the SMTP account: link received, login, order
  listed, logout.

## Phase 4 — Payment ☑

Stripe Checkout (hosted payment page, cards and TWINT in CHF); the backend never sees card
data. Decided 2026-10-19 after weighing zahls.ch: Stripe for its developer tooling and test
mode; the owner's Stripe account activation is pending. Until then a **mock gateway** behind
the same `PaymentGateway` interface plays the provider's part so the shop flow is complete.

- ☑ Placing an order starts a payment session and sends the customer to the gateway's page;
  the session id and page URL are stored on the order (`V3__payments.sql`).
- ☑ The success page proves nothing by itself: it reads the order from the backend and waits
  for the confirmation to mark it `PAID` (`order-success.component.spec.ts`).
- ☑ Confirmation is idempotent and refuses unknown sessions (`PaymentServiceTest`).
- ☑ Mock provider (`payments.provider=mock`, the default): shop-internal page `/pay/mock`,
  confirmation via `POST /api/payments/mock/{session}/complete`, endpoint absent with a real
  provider. Verified in Chromium: order, mock payment, success page, `PAID` in the database.
- ☑ `StripeCheckoutGateway` (`payments.provider=stripe`): Checkout Session with one line
  item per order item plus the flat shipping, amounts in rappen, order number as reference
  and metadata, customer e-mail, German locale, success and cancel URLs on the shop's success
  page (`StripeCheckoutGatewayTest`).
- ☑ The order becomes `PAID` only through the `checkout.session.completed` (or
  `async_payment_succeeded`) webhook with the signature verified against the endpoint secret;
  a tampered body or a foreign secret gets `400` and no state change, an unpaid session is
  acknowledged and left alone (`StripeWebhookControllerTest`).
- ☑ Secret key and webhook secret come from the environment (`STRIPE_SECRET_KEY`,
  `STRIPE_WEBHOOK_SECRET`); with `payments.provider=stripe` the application refuses to start
  without them. `grep -rn "sk_live\|sk_test\|whsec" src/main/resources` shows no literal key.
- ☑ An expired session or a failed asynchronous payment (`checkout.session.expired`,
  `checkout.session.async_payment_failed`) cancels the order and erases its file and personal
  data at once; a paid order is left alone. The nightly retention job does the same for any
  order still unpaid, and erases cancelled ones, after the quote validity, so a missed webhook
  cannot leave personal data behind (`OrderRetentionServiceTest`).
- ☑ Manual end-to-end test on 3dburg.ch in Stripe test mode, 2026-10-20: order placed, paid on
  Stripe's page with the test card, success page switched to paid through the webhook. The
  deployment steps are in pod-frontend `deploy/README.md` ("Switch payments to Stripe").

## Phase 5 — Notifications, legal and data protection ☑

- ☑ Order confirmation e-mail to the customer and a notification to the owner on `PAID`, plus a
  shipping notice on `SHIPPED`, sent after commit through the SMTP account from the
  environment (`MAIL_ENABLED`, `SPRING_MAIL_*`); `OrderMailsTest` pins the content,
  `MailOrderNotifierTest` the addressing with a fake sender. Off, the texts go to the log.
- ☑ `docs/data-protection.md` states for each personal-data field why it is held, the lawful
  basis, the retention period and the deletion path (Swiss FADP).
- ☑ Model files and personal data are erased 90 days after shipping by a nightly job; the
  order's numbers stay as the business record. `OrderRetentionServiceTest` moves a
  controllable clock past the deadline and asserts file and data are gone.
- ☑ A deletion request is one operation, `POST /api/admin/orders/{number}/erase`, idempotent
  and covered by `OrderRetentionServiceTest`.
- ☑ Imprint, terms and privacy pages exist in the frontend (`/impressum`, `/agb`,
  `/datenschutz`), the checkout requires accepting the terms, shipping is the configured flat
  rate. VAT is shown as "none" until the business registers (`orders.vat-enabled`).

## Phase 6 — Admin ☑

The owner's view; a back-office for catalogue and printers grows from here.

- ☑ `/admin` is authenticated: HTTP Basic against `/api/admin/**` with `ADMIN_USERNAME` /
  `ADMIN_PASSWORD` from the environment, stateless, no browser challenge dialog; a blank
  password disables the admin API. `AdminOrderControllerTest` asserts `401` without and with
  wrong credentials and that public endpoints stay open.
- ☑ Order list (newest first, status filter) with state, customer, address, payment, price,
  the items, and a download per item named `<order>_<position>_<file>`. A sliced, printable 3MF is not
  produced: the CLI's `--export-3mf` needs a GL context and fails headless; the owner opens
  the model in Bambu Studio, which is the review step anyway.
- ☑ State transitions from the detail view along the state machine
  `PAID → IN_REVIEW → APPROVED → PRINTING → SHIPPED`; shipping starts the retention clock
  (`AdminOrderServiceTest`); illegal moves answer `409`. Review and approval are separate
  states so that reviewer and printer roles can later see only their part of the queue.
- ☑ Slicer warnings from `result.json` and the manifold check from `--info` are shown next to
  the order so the owner knows which models to inspect (2026-10-21, API side). Every slice
  that misses the cache runs `--info` once with its own 20 s timeout; the quote carries
  `warnings` and `manifold`, each order item stores them (`V6__slicer_warnings.sql`), and
  the admin order carries `needsInspection`. `SlicerExecutionServiceTest`
  (`collectsWarningsFromResultJsonTheConsoleAndTheManifoldCheck`,
  `aFailingManifoldCheckLeavesTheQuoteIntact`, `aHangingManifoldCheckIsKilledAfterItsOwnShortTimeout`),
  `QuoteServiceTest.slicerWarningsAndTheManifoldCheckTravelWithTheCachedSlice`,
  `OrderServiceTest.storesTheSlicerWarningsAndTheManifoldCheckOnEachItem`,
  `AdminOrderServiceTest.flagsOrdersWithSlicerWarningsOrANonManifoldModelForInspection`.
  The admin UI marks the row and the upload page says "nicht wasserdicht" in the frontend
  repository; the exact warning texts of the real CLI are still to be seen on the production
  host, the parser takes any `warning*` field of `result.json` and console lines that start
  with "warning".
- ☑ Rate-limit reset is a button in the admin UI behind the login; the token endpoint is gone.

Verified in Chromium on 2026-10-19: rejected login, login, list, detail, download, filter.

## Phase 7 — Deployment ☑ (live on 3dburg.ch since 2026-10-20)

Everything lives in `pod-frontend/deploy`: `docker-compose.prod.yml`, `Caddyfile`,
`.env.example`, `backup.sh` and a README with the deployment sequence and the runbook. The
production backend image is this repository's `Dockerfile`.

- ☑ A documented sequence on a clean server brings the stack up from the repositories plus a
  `.env`: Caddy with HTTPS, backend image with the checksum-verified slicer, frontend SSR,
  Postgres, order-files volume (`deploy/README.md`).
- ☑ `curl -I http://3dburg.ch` answers 308 to HTTPS, `www` 301 to the bare host, Let's Encrypt
  certificates for `3dburg.ch` and `admin.3dburg.ch` (2026-10-20/21).
- ☑ From outside only 22, 80 and 443 answer; 4000, 5432, 8080 and 2019 are filtered
  (connect scan 2026-10-21).
- ☑ `3dburg.ch`, `www` and `admin.3dburg.ch` are in the frontend's `security.allowedHosts`;
  `rate-limit.trust-forwarded-headers` is on and Caddy overwrites `X-Forwarded-For`.
- ☑ Resource limits per container in the compose file; a runaway slice cannot take down the host.
- ☑ Nightly backups of the database and the order-files volume (cron 02:30), and a restore
  rehearsed on the production server on 2026-10-20: schema wiped, dump and volume restored,
  order count, migration count and file count identical afterwards (procedure in the runbook).
- ☑ The runbook (`pod-frontend/deploy/README.md`) covers deploy, update, roll back, restore
  from backup, rotate a credential, reset a rate limit.
- ☑ No secret in the image or the repository: a scan of both histories for key prefixes and
  password literals (2026-10-21) finds only the local development stack's Postgres password
  and documentation; no `.env` was ever committed.
- ☑ Server hardening 2026-10-21: key-only login for a sudo user, root and password login off,
  fail2ban, unattended security upgrades, kernel updated and rebooted (runbook section
  "Server access and hardening").

## Phase 8 — Printer integration (deferred)

Depends on a vendor whose firmware rules changed in 2025; the shop must not.

1. Read-only printer status in the admin over LAN MQTT.
2. One-click send of a prepared 3MF to a printer in state "ready".
3. A queue that assigns orders to ready printers, with an explicit "plate cleared" step,
   because the printer cannot detect an empty plate.

---

## Phase 9 — Agentic access (MCP)

Stripe's agentic-commerce guidance (2026) expects shops to be reachable by AI agents: an
agent quotes and orders for a customer the way the browser does today. The shop already
has the ingredients: an upload endpoint, a quote per `modelId`, a cart-shaped order and a
hosted Stripe Checkout. What is missing is a machine-facing contract on top.

Decision needed first: an **MCP server** (tools `upload_model`, `quote`, `create_order`
returning the Checkout URL) inside `cad-slicer-engine`, or a documented **plain HTTP flow**
over the existing OpenAPI with an agent-readable description in `llms.txt`. The MCP server
is the smaller addition to the API but a new process to run; the HTTP flow needs no
infrastructure but relies on the agent reading OpenAPI.

Acceptance criteria:

- ☐ An ADR records the choice (MCP server vs. OpenAPI-only) and the authentication model
  for agents (anonymous quotes, orders only with a customer e-mail and the Stripe Checkout
  completing payment; no card data ever passes through the agent).
- ☐ A quote and an order can be created end to end without the frontend: `upload`, `quote`,
  `order` and the returned Checkout URL work from a script (`scripts/agent-order.sh`).
- ☐ Rate limits and the file caps apply to the agent path exactly as to the browser
  (`UploadRateLimiterTest`, contract test for the agent endpoints).
- ☐ `llms.txt` in pod-frontend names the entry point and the steps; the sitemap stays
  unchanged.
- ☐ Stripe's Agentic Commerce Protocol checkout (where available for CHF merchants) is
  evaluated once the above works; nothing in the order model should need to change for it.

---

## Deferred by decision, not oversight

- **Object storage (S3/MinIO)** — local volume behind a storage interface; ADR 0003.
- **Asynchronous quotes, job table, polling UI** — synchronous path with guards; ADR 0003.
- **RabbitMQ** — removed; a Postgres table and a scheduler cover background work when it
  appears; ADR 0002 addendum.
- **Kubernetes / KEDA** — out of scope; ADR 0002.
- **Manual modelling as a line item** — after orders exist.
- **Multi-printer profiles** — the A1 is the only target until the shop sells.
