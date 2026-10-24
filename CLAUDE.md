# cad-slicer-engine — working conventions

Spring Boot 4 / Java 21 backend of the 3DBurg print-on-demand shop. It uploads models,
slices them with the Bambu Studio CLI, prices quotes, takes orders, pays through Stripe,
runs customer accounts and the admin API. The frontend is the sibling repository
`pod-frontend`; `pod-frontend/deploy/` holds the production stack for both.

Scope and decisions live in `docs/ROADMAP.md`, `docs/architecture.md` and `docs/adr/`.
Read the ADR before changing what it decided; write a new ADR (next number, same template)
for a decision that changes pricing, data retention, storage, security or the API contract.

## Commands

- `./mvnw -B -q test` — all unit, web and Testcontainers tests (Docker needed for the
  latter); must be green before a PR. CI runs `./mvnw -B verify`.
- `./mvnw -B test -Pslicer` — the tests tagged `slicer` against the real CLI; they skip
  themselves where the CLI or the model is missing.
- `OpenApiContractTest` writes `target/openapi.json`; the frontend generates its types from
  it. A DTO change is a contract change: regenerate the frontend types and ship both together.
- `scripts/measure-slice.sh` — resource measurement of a slice (feasibility note section 6).

## Language

- Code, comments, commit messages, PR texts, docs, mails templates' code: English, no umlauts
  in identifiers. Customer-facing mail texts are German with real umlauts and `ss`.
- Names say what a thing is for; comments say why, never what the next line does.

## Architecture rules (enforced by `ArchitectureTest`, extend it when you add a package)

- Packages are features, never layers: `quote` (prices, quote API), `slicer`
  (CLI, profiles, capacity gate), `upload` (upload endpoint, model store), `catalog` (plain-Java
  shared vocabulary: `FilamentType`, `PrintQuality`, `ColorOption`, `BoundingBox`), `order`, `payment`, `account`, `cart`,
  `admin`, `notification`, `calibration`, `config`. `controller`/`service`/`model` packages
  fail `ArchitectureTest`. `quote` uses `slicer` and `upload`, never the reverse; `slicer` and `upload` know neither `quote` nor each other; `catalog` depends on nothing. Controllers
  depend on services, never the other way round. `cart` must not depend on `account`; `account` reaches
  `cart` through its service. Cross-package needs go through small ports (`CurrentAccount`,
  `OrderFileStorage`, `RetentionSweep`, `PaymentGateway`), not through repositories.
- Configuration is typed (`@ConfigurationProperties` records) and lives in `application.yml`
  with a comment per setting. No magic numbers in services.
- Money is `BigDecimal` rounded with `Chf` (five-rappen steps in quotes). Never `double` in
  the order path.
- Database changes are Flyway migrations `V<n>__<name>.sql`, forward only, with a backfill
  for existing rows when a column moves (see V10). A migration that changes money gets a
  Testcontainers test that migrates a fixture and checks totals.
- Files (uploads, order files, cart files) go through `UploadedModelStore` (hours) or
  `OrderFileStorage` (durable, on the volume, in the backup). No other file writes.
- Errors are RFC 7807 problem details with a stable `code` (`model-expired`, `price-changed`,
  `not-logged-in`, `cart-item-not-found`); the frontend switches on the code, not the text.
- Rate limits and size caps apply to every public endpoint, including new ones.

## Tests

- Unit tests imitate the CLI with a script, so no slicer or database is needed for the bulk.
- Test behaviour visible at the API or in the database, not call sequences. One test per
  rule in an ADR (the quantity arithmetic, the material factors, the fee per order).
- Keep `TestOrders` and the other fixtures the single place that builds domain objects.

## Git and review

- One feature or fix per branch from `main`, PR against `main`, no stacked PRs, no direct
  pushes. Commit messages explain why, in English, and end with the Co-Authored-By trailer
  when written by Claude.
- Before merging a PR written by a subagent, run `/code-review` on it. Check that migrations
  are reversible in effect (totals unchanged) and that the OpenAPI document still validates.
- Delete what a change makes obsolete (code, properties, tests, docs) in the same PR.
- Deploy with `pod-frontend/deploy/update.sh` on the server; it backs up before Flyway runs.

## Things that look like bugs but are decisions

- One slice per model and quality with the PLA profile; quantity and material are arithmetic
  (ADR 0006, 0007). The calibration tables only record real slices; nothing re-slices at night
  (ADR 0008).
- Setup fee once per order, CHF 3 (ADR 0009, 0011). Ordering needs an account: no guest checkout,
  no public order lookup; orders are read under `/api/account/orders` (ADR 0011).
- The server cart exists only for signed-in customers (the browser cart still works anonymously); files are copied, not referenced, and
  expire 30 days after the last save (ADR 0010).
- Slicer timeout 120 s and two concurrent slots were measured, not guessed (feasibility note §6).
