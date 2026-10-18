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
| 5 | Notifications, legal and data protection | ☐ |
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
- ☑ Colour catalogue per filament, validated and echoed.
- ☑ Frontend: upload with 3D preview and swatches, debounced requests, readable `429`/`503`
  messages, 17 tests, production build within budgets.
- ☐ The Bambu Studio AppImage is verified by SHA-256 in the Dockerfile; a wrong checksum
  fails the image build.
- ☐ A test tagged `slicer` slices the Benchy with the real CLI and asserts layer height 0.2,
  print time between 30 and 45 minutes and mass between 10 and 14 g. It does not run in
  plain `./mvnw verify`.
- ☐ Wall-clock, peak RSS and CPU for a realistic model (≥ 10 MB STL with supports) are
  measured and recorded in the feasibility note, and the timeout is set from that number.
- ☐ Decision recorded and applied: supports in the quote profile on or off, so the quote
  matches what is printed.

## Phase 3 — Orders ☐

The first personal data and the first application table.

- ☐ `POST /api/orders` turns a quote into an order: file hash, parameters, the full price
  breakdown and a quote expiry are stored immutably; re-reading the order after a rate change
  returns the original price. Asserted by a test.
- ☐ The model file is stored under a server-generated UUID in the order-files volume; a test
  asserts the key contains no part of the client filename.
- ☐ Guest checkout collects name, e-mail, shipping address; validation errors are problem
  details, asserted by a `@WebMvcTest`.
- ☐ `V2__orders.sql` creates the tables; a Testcontainers `@SpringBootTest` loads the context
  against Postgres and round-trips an order.
- ☐ Money in orders is `BigDecimal`; a test asserts CHF 0.05 rounding of a stored total.
- ☐ Order states: `NEW → PAID → IN_REVIEW → PRINTING → SHIPPED`, plus `CANCELLED`; illegal
  transitions are rejected by a test.
- ☐ The checkout page in the frontend submits the order and shows the order number.

## Phase 4 — Payment ☐

Hosted payment page of the provider (zahls.ch / Payrexx gateway); the backend never sees card
data.

- ☐ Placing an order creates a gateway with amount, currency, order reference and success,
  cancel and failure URLs, and redirects the customer to it.
- ☐ The order becomes `PAID` only through the provider's webhook; a test asserts that the
  success redirect alone does not change state.
- ☐ Webhook signatures are verified; a test with a tampered body gets `401` and no state
  change.
- ☐ Webhook delivery is idempotent: the same transaction delivered twice marks one payment.
- ☐ Provider instance name and API key come from the environment; `grep -rn "payrexx\|zahls"
  src/main/resources` shows no literal key.
- ☐ A manual end-to-end test with the provider's test mode is recorded in `docs/`.

## Phase 5 — Notifications, legal and data protection ☐

- ☐ Order confirmation e-mail to the customer and a notification to the owner on `PAID`, sent
  through an SMTP provider configured by environment; a test asserts content with a fake
  sender.
- ☐ `docs/data-protection.md` states for each personal-data field why it is held, the lawful
  basis, the retention period and the deletion path (Swiss FADP).
- ☐ Order files and quotes are deleted after the stated retention period by a scheduled job;
  a test with a controllable clock proves a job's files are gone after expiry.
- ☐ A deletion request removes a customer's data and files in one documented operation,
  covered by a test.
- ☐ Imprint, terms and privacy pages exist in the frontend; prices are shown including
  8.1% VAT with the VAT amount on the order; shipping is a configured flat rate.

## Phase 6 — Admin ☐

The owner's view; a back-office for catalogue and printers grows from here.

- ☐ `/admin` is authenticated (single owner login from environment credentials, later a
  proper identity) and refuses unauthenticated requests with `401`, asserted by a test.
- ☐ Order list with state, customer, price and a download of the model and the sliced
  output as a printable 3MF.
- ☐ State transitions (`IN_REVIEW → PRINTING → SHIPPED`) from the list, with the review step
  as the owner's printability check.
- ☐ Slicer warnings from `result.json` and the manifold check from `--info` are shown next to
  the order so the owner knows which models to inspect.
- ☐ Rate-limit reset is reachable from the admin UI (replaces the token endpoint).

## Phase 7 — Deployment ☐

- ☐ A documented sequence on a clean server brings the stack up from the repositories plus a
  `.env`: reverse proxy with HTTPS, backend image with the checksum-verified slicer,
  frontend SSR, Postgres, order-files volume.
- ☐ `curl -I http://<host>` redirects to HTTPS; an SSL checker reports no error.
- ☐ No published host ports except 80 and 443; `nmap` from outside confirms.
- ☐ The production host is in the frontend's `security.allowedHosts`; `rate-limit.trust-forwarded-headers`
  is on and the proxy overwrites `X-Forwarded-For`.
- ☐ Resource limits per container; a runaway slice cannot take down the host.
- ☐ Nightly backups of the database and the order-files volume, **and a restore performed
  into a scratch environment and verified.**
- ☐ `docs/runbook.md` covers deploy, roll back, rotate a credential, restore from backup,
  reset a rate limit.
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
