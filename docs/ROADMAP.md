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
| 3 | Orders | ☐ |
| 4 | Payment | ☐ |
| 5 | Notifications, legal and data protection | ☑ (SMTP account to configure on the server) |
| 6 | Admin | ☐ |
| 7 | Deployment | ☐ |
| 8 | Printer integration | deferred |

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
- ☑ The whole quantity is sliced as one arranged batch across plates; 3MF is exported to STL
  for cloning.
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
  plain `./mvnw verify`.
- ☐ Wall-clock, peak RSS and CPU for a realistic model (≥ 10 MB STL with supports) are
  measured and recorded in the feasibility note, and the timeout is set from that number.
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
- ☑ `StripeCheckoutGateway` (`payments.provider=stripe`): Checkout Session with the order
  total in rappen as one line item, order number as reference and metadata, customer e-mail,
  German locale, success and cancel URLs on the shop's success page (`StripeCheckoutGatewayTest`).
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

## Phase 6 — Admin ☑ (one item open)

The owner's view; a back-office for catalogue and printers grows from here.

- ☑ `/admin` is authenticated: HTTP Basic against `/api/admin/**` with `ADMIN_USERNAME` /
  `ADMIN_PASSWORD` from the environment, stateless, no browser challenge dialog; a blank
  password disables the admin API. `AdminOrderControllerTest` asserts `401` without and with
  wrong credentials and that public endpoints stay open.
- ☑ Order list (newest first, status filter) with state, customer, address, payment, price,
  and a download of the uploaded model named `<order>_<file>`. A sliced, printable 3MF is not
  produced: the CLI's `--export-3mf` needs a GL context and fails headless; the owner opens
  the model in Bambu Studio, which is the review step anyway.
- ☑ State transitions from the detail view along the state machine
  `PAID → IN_REVIEW → APPROVED → PRINTING → SHIPPED`; shipping starts the retention clock
  (`AdminOrderServiceTest`); illegal moves answer `409`. Review and approval are separate
  states so that reviewer and printer roles can later see only their part of the queue.
- ☐ Slicer warnings from `result.json` and the manifold check from `--info` are shown next to
  the order so the owner knows which models to inspect.
- ☑ Rate-limit reset is a button in the admin UI behind the login; the token endpoint is gone.

Verified in Chromium on 2026-10-19: rejected login, login, list, detail, download, filter.

## Phase 7 — Deployment ☐ (files ready, first deployment pending)

Everything lives in `pod-frontend/deploy`: `docker-compose.prod.yml`, `Caddyfile`,
`.env.example`, `backup.sh` and a README with the deployment sequence and the runbook. The
production backend image is this repository's `Dockerfile`.

- ☑ A documented sequence on a clean server brings the stack up from the repositories plus a
  `.env`: Caddy with HTTPS, backend image with the checksum-verified slicer, frontend SSR,
  Postgres, order-files volume (`deploy/README.md`).
- ☐ `curl -I http://<host>` redirects to HTTPS; an SSL checker reports no error.
- ☐ No published host ports except 80 and 443; `nmap` from outside confirms.
- ☐ The production host is in the frontend's `security.allowedHosts`; `rate-limit.trust-forwarded-headers`
  is on and the proxy overwrites `X-Forwarded-For`.
- ☑ Resource limits per container in the compose file; a runaway slice cannot take down the host.
- ☐ Nightly backups of the database and the order-files volume, **and a restore performed
  into a scratch environment and verified.**
- ☑ The runbook (`pod-frontend/deploy/README.md`) covers deploy, update, roll back, restore
  from backup, rotate a credential, reset a rate limit.
- ☐ No secret in the image or the repository: `git log -p | grep -iE "password|secret"` finds
  no live credential.

## Phase 8 — Printer integration (deferred)

Depends on a vendor whose firmware rules changed in 2025; the shop must not.

1. Read-only printer status in the admin over LAN MQTT.
2. One-click send of a prepared 3MF to a printer in state "ready".
3. A queue that assigns orders to ready printers, with an explicit "plate cleared" step,
   because the printer cannot detect an empty plate.

---

## Deferred by decision, not oversight

- **Object storage (S3/MinIO)** — local volume behind a storage interface; ADR 0003.
- **Asynchronous quotes, job table, polling UI** — synchronous path with guards; ADR 0003.
- **RabbitMQ** — removed; a Postgres table and a scheduler cover background work when it
  appears; ADR 0002 addendum.
- **Kubernetes / KEDA** — out of scope; ADR 0002.
- **Customer accounts** — guest checkout first.
- **Manual modelling as a line item** — after orders exist.
- **Multi-printer profiles** — the A1 is the only target until the shop sells.
