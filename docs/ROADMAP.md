# Roadmap

Eight phases, in dependency order. Each phase lists **acceptance criteria written as a command
to run and a result to check** — not aspirations. A phase is done when every box can be ticked
by someone else on a clean checkout.

Security, testing and data-protection work is *inside* the phase that creates the risk, never
deferred to a later "hardening phase".

Legend: ☐ not started · ☑ done

| Phase | Theme | Depends on |
|---|---|---|
| 0 | Architecture and feasibility | — |
| 1 | Build hygiene and runnable baseline | 0 |
| 2 | Object storage | 1 |
| 3 | Secure upload API | 2 |
| 4 | Angular upload UI | 3 |
| 5 | Slicing | 3 |
| 6 | RabbitMQ | 5 |
| 7 | Quotation | 5 |
| 8 | Deployment | 7 |

---

## Phase 0 — Architecture and feasibility ☑

Decide the shape of the system and prove the one component that could invalidate the whole
plan (headless slicing) before building anything on top of it.

**Acceptance criteria**

- ☑ `docs/architecture.md` describes the upload-to-quotation workflow and marks every
  component implemented / partial / planned.
- ☑ `docs/adr/0001-object-storage.md` records the S3-compatible storage decision.
- ☑ `docs/adr/0002-modular-monolith-and-background-worker.md` records the modular-monolith
  plus background-worker decision, including why RabbitMQ and Kubernetes are deferred.
- ☑ `docs/feasibility/bambu-studio-cli-headless.md` contains commands that reproduce a
  headless slice, with versions, and separates verified from unresolved claims.
- ☑ Build state established by running it: `mvn -B -o clean verify` → `BUILD SUCCESS`,
  **0 tests**, CycloneDX skipped offline.
- ☑ Boot state established by running it: application fails against the Compose stack with
  `FATAL: password authentication failed for user "sliceruser"`.
- ☑ No application feature or infrastructure added.

**Outcome:** slicing is feasible (no `xvfb` needed, `result.json` is parseable). Two blockers
found that must be cleared before storage: the AIStor licensing wall and the SDK mismatch.
See `docs/architecture.md` §6.

---

## Phase 1 — Build hygiene and runnable baseline ☑

The project built but could not start, and nothing was tested. Fix the floor before building
on it. No features.

**Acceptance criteria**

- ☑ `./mvnw -B clean verify` succeeds on a clean checkout with no system Maven installed
  (wrapper 3.3.4, `only-script`, Maven 3.9.9).
- ☑ `grep -rn "password\|secret-key\|access-key" src/main/resources/application.yml` shows no
  literal credential — every one reads `${ENV_VAR:default-for-local-dev}`.
- ☑ `.env.example` is committed listing every required variable; `.env` is in `.gitignore`.
  `git check-ignore .env` exits 0.
- ☑ Every credential in `application.yml` resolves against `docker-compose.yml`:
  `docker compose up -d postgres` then `./mvnw spring-boot:run` logs
  `Started CadSlicerEngineApplication` and exits 0 on shutdown.
- ☑ The application starts **without** RabbitMQ running: the topology and the consumer are
  gated behind `app.messaging.enabled` (default `false`); AMQP processing itself is Phase 6.
- ☑ Flyway replaces `ddl-auto`: `grep -n "ddl-auto" src/main/resources/application.yml` shows
  `validate`, and `src/main/resources/db/migration/V1__baseline.sql` exists.
- ☑ At least one real test exists and runs: `./mvnw -B test` reports `Tests run: 68, Failures: 0`
  (unit and `@WebMvcTest` slices; a Testcontainers context test is deferred until the first
  application table arrives with orders).
- ☑ `README.md` no longer claims unimplemented capabilities; the Spring Boot badge matches
  `pom.xml`.
- ☑ CI runs `./mvnw -B verify` on push and its run is green.

**Outcome:** a clean checkout builds, tests and boots with Postgres alone. Note that the quote
path (Phases 3 to 5 and part of 7) was built ahead of Phases 1 and 2 as a synchronous API with
in-process guards; see `README.md` for what exists and the phase notes below for deviations.

**Security / data protection:** credentials leave source control permanently here.
**Testing:** the test harness (Testcontainers, surefire) is established here so later phases
have somewhere to put tests.

---

## Phase 2 — Object storage ☐

Make a swappable S3 boundary that actually works, on an image that is actually free.

**Acceptance criteria**

- ☐ `docker compose up -d minio` on a machine with no MinIO licence, then a bucket round-trip
  succeeds — logs must **not** contain `All S3 operations are denied`. `chainguard/minio` is the
  recommended image (verified AGPLv3; see ADR 0001 A2), with Garage or SeaweedFS as fallbacks.
- ☐ `docker compose config | grep image:` shows no `aistor` image.
- ☐ The Compose healthcheck fails when S3 is non-functional: it checks a bucket operation
  (e.g. `mc ready local` or an authenticated `ListBuckets`), not only `/minio/health/live`,
  which returns `200` on a licence-dead server.
- ☐ `./mvnw dependency:tree | grep -E "io.minio|okhttp-jvm"` returns nothing;
  `grep -c "software.amazon.awssdk" pom.xml` > 0; the orphan `<aws.sdk.version>` property is
  either used or removed.
- ☐ `grep -rln "io.minio" src/main/java` returns nothing.
- ☐ `grep -rln "software.amazon.awssdk" src/main/java` matches **only** the storage adapter
  package — no SDK type leaks into another module.
- ☐ An integration test against a Testcontainers S3 service puts an object, heads it (asserting
  the byte size), gets it back byte-identical, presigns a PUT, uploads through that URL with a
  plain HTTP client, and deletes it. `./mvnw -B verify` green.
- ☐ Object keys are server-generated UUIDs: a test asserts the stored key does not contain the
  client-supplied filename.
- ☐ The bucket is private: an unauthenticated `GET` of a known key returns 403/404, asserted by
  a test.

**Security:** no anonymous bucket policy; credentials from environment; unguessable keys.
**Data protection:** customer CAD files are their IP — private buckets are the baseline.

---

## Phase 3 — Secure upload API ☐

The internet-facing surface, and the main denial-of-cost risk: an attacker who can make the
server store or process unbounded data costs real money.

**Acceptance criteria**

- ☐ `POST /api/v1/uploads` with a valid declared name/size returns `201` with a pre-signed PUT
  URL and an upload id.
- ☐ `POST /api/v1/uploads` with a declared size over the cap returns `413` and creates no
  database row and no object. Asserted by a test.
- ☐ A client that declares a small size but uploads a large object is rejected at
  `POST /api/v1/uploads/{id}/complete` with `413`, because completion re-reads the **actual**
  size via `HEAD`. Asserted by a test.
- ☐ An unsupported extension (`.exe`, `.zip`) returns `415`.
- ☐ A file with an `.stl` extension whose content is not STL/3MF is rejected by content
  sniffing, not extension alone.
- ☐ A filename containing `../` or an absolute path never reaches the filesystem or the object
  key: a test asserts the stored key is a UUID.
- ☐ A pre-signed URL is unusable after its TTL: a test with a clock/short TTL gets 403.
- ☐ Exceeding the per-IP rate limit returns `429`.
- ☐ Error responses carry no stack trace and no internal path: a test asserts the 4xx/5xx body
  shape (RFC 7807 or equivalent).
- ☐ `POST /api/v1/quotes` returns `202` with a job URL in under 200 ms at p95 while a slice is
  in progress — the HTTP path never blocks on slicing.
- ☐ OpenAPI/Swagger describes every endpoint and its error codes.

**Security:** this is the phase where the security tests get written, not retrofitted.
**Testing:** `@WebMvcTest` for validation, full `@SpringBootTest` for the upload lifecycle.

---

## Phase 4 — Angular upload UI ☐

No frontend exists; this starts from zero.

**Acceptance criteria**

- ☐ `npm ci && npm run build` succeeds in `frontend/` on a clean checkout.
- ☐ `npm test` runs at least one component test and passes.
- ☐ A user can drag-and-drop or pick an STL/3MF and see a real progress percentage during the
  direct-to-storage PUT.
- ☐ Network inspection confirms the file bytes go to the storage endpoint, **not** to the
  application origin.
- ☐ Oversized and wrong-type files are rejected client-side with a readable message *and* still
  rejected server-side if the client check is bypassed (the server is the authority).
- ☐ The quote view polls the job URL and renders `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED`
  with a human-readable failure reason.
- ☐ `grep -rn "access-key\|secret" frontend/src` returns nothing.
- ☐ CORS is restricted to configured origins: a request from an unlisted origin is refused,
  asserted by a backend test.

**Security:** no credentials in frontend code; CORS allowlist; server-side validation is never
assumed to be covered by client-side checks.

---

## Phase 5 — Slicing ☐

Untrusted geometry reaches a native binary. The highest-risk component in the system.

Builds on the verified findings in `docs/feasibility/bambu-studio-cli-headless.md`.

**Acceptance criteria**

- ☐ A slicer image builds and `docker run <image> --info model.stl` prints model info; the
  Bambu Studio release is pinned by version **and** verified by checksum.
- ☐ `grep -n "xvfb" src/main/resources/application.yml` returns nothing — verified unnecessary
  (finding V1).
- ☐ The G-code path is discovered from `--outputdir` contents (`plate_*.gcode`), not predicted
  from the input filename. A test asserts the returned path exists (fixes finding V2).
- ☐ Success is determined by process exit code plus `result.json`'s `return_code`, never by the
  absence of `error` in stderr — a successful slice logs GLFW/Wayland errors (finding O2). A
  test feeds known-noisy output and asserts success.
- ☐ Machine, process and filament profiles are always passed explicitly; a test asserts the
  generated G-code header contains `printer_model = Bambu Lab A1` (guards finding V5).
- ☐ `result.json` is parsed into a typed result carrying estimated seconds
  (`total_predication`), filament length, per-filament grams and bounding box.
- ☐ Mass is derived from filament length × material density, not taken from the slicer's gram
  field; a unit test pins the arithmetic for a known length (addresses finding O3).
- ☐ A deliberately malformed/truncated STL ends as `FAILED` with a stored reason, and the
  worker survives to process the next job.
- ☐ A pathological model that exceeds the timeout is killed: the job ends `FAILED`, the child
  process is gone (`pgrep` finds no orphan), and the scratch directory is deleted. The timeout
  fires even while the child is still producing output — fixing the current
  `SlicerExecutionService` drain, which reads stdout to EOF before calling `waitFor`.
- ☐ Each job uses its own scratch directory, removed on both success and failure — asserted by
  a test.
- ☐ No user-controlled string is interpolated into a shell: the command is built as an argument
  list and a test passes a filename containing `;`, `$(...)` and spaces without incident.
- ☐ Worker concurrency is bounded by configuration, and the API stays responsive while the
  worker saturates: `GET /api/v1/quotes/{id}` answers during a running slice.
- ☐ Measured and recorded in the feasibility doc: wall-clock, peak RSS and CPU for a
  realistic model (≥10 MB STL with supports) — closing unresolved question 1.

**Security:** process isolation, resource limits, no shell, input treated as hostile.
**Testing:** slicer-dependent tests tagged (`@Tag("slicer")`) so plain `mvn verify` does not
require the large image.

---

## Phase 6 — RabbitMQ ☐

Replace the database queue once a working pipeline exists. Transport change only — job states
and the HTTP contract do not move (ADR 0002).

**Acceptance criteria**

- ☐ Jobs flow through RabbitMQ end to end with no change to any API response shape; the Phase 3
  and 4 tests pass unmodified.
- ☐ A job published while no consumer runs is processed after the consumer starts (durable
  queue, persistent message).
- ☐ A broker restart mid-queue loses no job: publish N, `docker compose restart rabbitmq`,
  assert all N eventually reach a terminal state.
- ☐ A message that fails repeatedly lands on a dead-letter queue after a bounded retry count
  instead of looping forever — asserted by a test with an always-failing handler.
- ☐ Redelivery of an already-processed job does not double-charge or duplicate work:
  consumption is idempotent, asserted by delivering the same job id twice.
- ☐ Queue depth and consumer count are observable (management UI or an Actuator/Prometheus
  metric).
- ☐ The broker is not reachable from the public internet (no host port binding in the
  production Compose file).

---

## Phase 7 — Quotation ☐

Turn measurements into a price. The first phase that handles personal data.

**Acceptance criteria**

- ☐ Pricing is a pure function with no I/O: unit tests pin the price for fixed inputs
  (material grams, machine seconds, material rate, machine rate, margin, setup fee).
- ☐ Money uses `BigDecimal` with explicit rounding; `grep -rn "double\|float" ` in the pricing
  module returns nothing. A test asserts rounding to CHF 0.05.
- ☐ Rates and margins are configuration, not literals in code; changing a rate changes the
  quote with no recompile.
- ☐ Manual modelling at CHF 40/hour is representable as a line item, and a test asserts a
  mixed quote (print + modelling hours) totals correctly.
- ☐ `GET /api/v1/quotes/{id}` returns an itemised breakdown — material cost, machine time cost,
  setup, margin, total, currency — not a single opaque number.
- ☐ A quote is immutable once issued and carries an expiry; re-reading it after a rate change
  returns the original price. Asserted by a test.
- ☐ VAT handling is explicit (inclusive/exclusive stated in the response), given commercial use
  in Switzerland.
- ☐ `docs/data-protection.md` exists and states, for each personal-data field: why it is held,
  the lawful basis, the retention period and the deletion path.
- ☐ Uploaded models and G-code are deleted automatically after the stated retention period; an
  integration test proves a job's objects are gone after retention expiry.
- ☐ A deletion request removes customer data and their stored models: one documented operation,
  covered by a test.

**Data protection:** retention, deletion and lawful basis are acceptance criteria here, not
documentation debt.

---

## Phase 8 — Deployment ☐

**Acceptance criteria**

- ☐ A documented sequence on a clean Hetzner server brings the stack up from the repository plus
  a `.env`, with no manual edits to tracked files.
- ☐ HTTPS with a valid certificate; `curl -I http://<host>` redirects to HTTPS; an SSL
  checker reports no error.
- ☐ `docker compose ps` in production shows **no** published host ports for Postgres, RabbitMQ
  or object storage; `nmap` from outside finds only 80/443.
- ☐ No secret is in the image or the repository: `git log -p | grep -iE "password|secret"` finds
  no live credential, and `docker history` shows no credential in a layer.
- ☐ A health endpoint reports dependency status, and it goes unhealthy when storage is
  genuinely unusable — not merely when the port stops answering (the AIStor lesson, ADR 0001 A2).
- ☐ Nightly backups of the database and buckets exist, **and a restore has been performed into
  a scratch environment and verified.** An untested backup is not a backup.
- ☐ Logs are retained with no personal data in them beyond what the data-protection doc allows.
- ☐ Resource limits are set per container so one runaway slice cannot take down the host.
- ☐ A documented rollback returns the previous version within minutes.
- ☐ `docs/runbook.md` covers: deploy, roll back, rotate a credential, drain the queue, restore
  from backup.

---

## Deferred by decision, not oversight

- **Kubernetes / KEDA autoscaling** — out of scope by constraint; one server (ADR 0002).
- **Hetzner Object Storage** — revisit when volume or availability justifies the cost
  (ADR 0001).
- **Automated module-boundary enforcement** (ArchUnit / Spring Modulith) — once the modules
  exist, Phase 5+.
- **Multi-printer support** — the A1 is the only target machine until the pipeline works for it.
- **Payment integration** — a quotation is not an order; out of scope until Phase 7 lands.
