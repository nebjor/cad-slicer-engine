# Architecture — CAD Slicer Engine

Status: Phase 0 (architecture and feasibility). This document describes the target design
and marks precisely what exists today. Nothing below should be read as a statement that a
component is implemented unless it is tagged **[implemented]**.

Legend: **[implemented]** working today · **[partial]** code exists but is incomplete or unwired · **[planned]** not written yet

---

## 1. Business context

A commercial FDM/FFF printing-on-demand service plus manual 3D modelling at CHF 40/hour.
A visitor uploads a model, receives an automated price for printing it, and can request
modelling work. The price must be derived from a real slice — material consumption and
machine time — not from a bounding-box estimate, because bounding-box pricing is wrong by
large factors for hollow, thin-walled or support-heavy geometry.

The first target machine is a **Bambu Lab A1, 0.4 mm nozzle**, which is why Bambu Studio is
the slicing engine (see `docs/feasibility/bambu-studio-cli-headless.md`).

## 2. Deployment shape

One Spring Boot application plus backing services, on a single existing Hetzner Cloud server,
orchestrated with Docker Compose. No Kubernetes. The reasoning, and the point at which that
choice should be revisited, is recorded in [ADR 0002](adr/0002-modular-monolith-and-background-worker.md).

```
                        Hetzner Cloud server (Docker Compose)
  +------------------------------------------------------------------------+
  |                                                                        |
  |  +--------------+    +----------------------+    +------------------+   |
  |  | Angular SPA  |    | cad-slicer-engine    |    | PostgreSQL       |   |
  |  | [planned]    |--->| (modular monolith)   |--->| business data    |   |
  |  +--------------+    |   web   |   worker   |    | [running, unused]|   |
  |                      +----------------------+    +------------------+   |
  |                            |           |                               |
  |                            v           v                               |
  |              +------------------+  +------------------+                 |
  |              | S3 object store  |  | Bambu Studio CLI |                 |
  |              | models + gcode   |  | in-container     |                 |
  |              | [blocked, see 6] |  | [feasible, see 5]|                 |
  |              +------------------+  +------------------+                 |
  +------------------------------------------------------------------------+
```

## 3. Upload-to-quotation workflow

The flow below is the target. Every step is **[planned]** — this is the sequence the roadmap
phases build toward, not a description of running code.

1. **Client requests an upload slot.** The SPA calls `POST /api/v1/uploads`. The server
   validates the declared filename, extension and size *before* any bytes move, creates an
   `Upload` row in state `PENDING`, and returns a short-lived pre-signed `PUT` URL plus an
   opaque upload id. Validating first is what makes a size cap enforceable without buffering
   a hostile file.
2. **Client uploads straight to object storage.** The browser `PUT`s the STL/3MF to the
   pre-signed URL. Bytes never transit the application, so JVM heap and server disk are not a
   function of user file size. This is the most important scaling decision in the design.
3. **Client confirms.** `POST /api/v1/uploads/{id}/complete`. The server does a `HEAD` on the
   object to confirm it exists and to read the *actual* byte size, rather than trusting the
   client's declared size, then re-validates against the cap and moves the row to `UPLOADED`.
4. **Quotation requested.** `POST /api/v1/quotes` with the upload id and print options
   (material, layer height, infill). The server creates a `SliceJob` in state `QUEUED` and
   returns `202 Accepted` with a job URL. The HTTP request does not wait for slicing.
5. **Worker slices.** A worker picks up the job, downloads the model from object storage to a
   scratch directory, runs the Bambu Studio CLI headlessly, and parses the slicer's
   `result.json` for material mass and estimated machine time.
6. **Worker persists the result.** G-code is uploaded to object storage; measured quantities
   and the derived price land in `SliceJob`/`Quote`; state becomes `COMPLETED` or `FAILED`
   with a reason.
7. **Client polls or is notified.** `GET /api/v1/quotes/{id}` returns the price breakdown.

Phases 2–5 implement this with the worker running **in-process** on a database-backed queue.
RabbitMQ replaces that transport in Phase 6 without changing steps 1–7 — see ADR 0002.

## 4. Component responsibilities

| Component | Responsibility | State |
|---|---|---|
| `CadSlicerEngineApplication` | Spring Boot entry point | **[implemented]** |
| `upload` module | Pre-signed URL issuing, declared-vs-actual size validation, upload lifecycle | **[planned]** |
| `quote` module | Pricing policy: material mass + machine time + margin → price. Pure, unit-testable, no I/O | **[planned]** |
| `slicing` module | Job lifecycle, orchestration of download → slice → parse → upload | **[planned]** |
| `SlicerExecutionService` | Wraps the Bambu Studio CLI via `ProcessBuilder`, enforces a timeout | **[partial]** — see §6 |
| `StorageService` | Object-storage boundary: put, get, and later pre-sign | **[partial]** — see §6 |
| `RabbitMQConfig` / `JobQueueConsumer` | AMQP queue, exchange, binding and consumer | **[partial]** — topology declared, consumer body is three TODOs |
| Persistence (`model`, `repository`) | Entities and Spring Data repositories | **[planned]** — packages exist, empty |
| `controller`, `dto` | HTTP boundary | **[planned]** — packages exist, empty |
| Angular SPA | Upload UI, progress, quote display | **[planned]** — no frontend directory exists |

Module boundaries are enforced by package structure and reviewed by hand through Phases 1–7.
An automated boundary test (ArchUnit or Spring Modulith) is deferred until the modules exist,
because there is nothing yet to constrain.

## 5. Slicing: verified feasible

Headless Bambu Studio CLI slicing **has been verified empirically** in a Linux x86_64
container: it runs with no `DISPLAY` and no `xvfb`, and produces `plate_1.gcode` plus a
machine-readable `result.json`. Exact commands, versions and the open questions are in
[`docs/feasibility/bambu-studio-cli-headless.md`](feasibility/bambu-studio-cli-headless.md).

Two consequences for the design:

- **Parse `result.json`, not G-code comments.** It carries `return_code`, `error_string`,
  `total_predication` (seconds), per-feature timings, a bounding box and per-filament
  `total_used_g` as structured JSON. Scraping `;` comments out of G-code is strictly worse.
- **Derive mass from filament length and material density, and treat the slicer's gram figure
  as a cross-check.** The observed gram fields are too coarsely rounded to trust for small
  parts (§6, finding 7).

## 6. Known gaps and blockers as of Phase 0

Each item below was established by inspection of the tree or by a command recorded in
`docs/feasibility/`.

1. **Object storage uses the wrong client.** The project constraint and ADR 0001 both specify
   AWS SDK v2. `pom.xml` instead ships `io.minio:minio:9.0.3` plus a hand-pinned
   `com.squareup.okhttp3:okhttp-jvm:5.3.2` that exists only to satisfy MinIO's OkHttp 5
   requirement, and `StorageService` is written against `MinioClient`. An orphaned
   `<aws.sdk.version>2.25.15</aws.sdk.version>` property is declared but consumed by no
   dependency. Moving to AWS SDK v2 removes two dependencies including a manual version
   override, and is the only way to issue pre-signed URLs through the SDK the ADR commits to.
   Phase 2 work.
2. **The MinIO image in `docker-compose.yml` is the commercial build and denies all S3 calls.**
   `quay.io/minio/aistor/minio:latest` reports `License: MinIO AIStor License` and logs
   `No valid license found, running in offline mode. All S3 operations are denied.`
   The container nevertheless stays up and `/minio/health/live` returns `200`, so the current
   healthcheck reports **healthy** while the service is unusable. The community images
   (`quay.io/minio/minio`, `minio/minio`) are no longer publicly pullable. Hard Phase 2
   blocker: storage cannot work until the image is replaced.
3. **No credential pair in `application.yml` matches `docker-compose.yml`.** Database
   `slicercore`/`sliceruser` vs `slicer_db`/`slicer_user`; RabbitMQ `guest` vs `slicer_mq`;
   S3 `minioadmin` vs `minio_admin`. Verified: the application does not start against the
   compose stack, failing with `FATAL: password authentication failed for user "sliceruser"`.
4. **Credentials are hard-coded in `application.yml`.** They are local development defaults,
   not live secrets, so nothing is leaking today. The problem is the pattern: the file is
   staged for commit and will later hold production values. `docker-compose.yml` already
   shows the better habit with `${MINIO_ROOT_USER:-minio_admin}`.
5. **No tests exist.** `src/test/java/com/slicercore` is empty. `mvn -B -o clean verify`
   reports `BUILD SUCCESS` while running zero tests, which is not evidence of correctness.
6. **No schema management.** `spring.jpa.hibernate.ddl-auto: update` is standing in for
   migrations. There are no entities yet, so this is cheap to fix now and expensive later.
7. **`SlicerExecutionService` returns a path that will not exist.** It predicts
   `<inputname>.gcode` in the output directory; the CLI actually writes `plate_1.gcode`
   (verified). Its stdout drain also runs to EOF *before* `waitFor`, so the configured
   timeout cannot fire while the child process is still producing output. Phase 5 work.
8. **`@RabbitListener` makes a broker mandatory to boot.** RabbitMQ is scheduled for Phase 6,
   but the dependency and the listener are already present, so the application cannot start
   without a broker it does not yet need.
9. **`README.md` overstates the system.** It advertises pre-signed URLs, Actuator/Prometheus
   metrics and KEDA-driven Kubernetes autoscaling. None are implemented,
   `spring-boot-starter-actuator` is not a dependency, and Kubernetes is explicitly out of
   scope. It also claims Spring Boot 3.x while `pom.xml` is on 4.1.1.
10. **No Maven wrapper.** There is no `mvnw`/`.mvn`, so builds depend on whatever Maven the
    machine happens to have and CI cannot pin a version.

## 7. Where security, testing and data protection enter

These are not a later phase. Each is tied to the phase that first makes it relevant, and each
has a checkable criterion in [`ROADMAP.md`](ROADMAP.md).

- **Phase 2 (storage):** credentials move to environment variables; buckets private with no
  anonymous policy; object keys are server-generated UUIDs so a key cannot be guessed from a
  filename.
- **Phase 3 (upload API):** the denial-of-cost surface. Hard size cap enforced before bytes
  move and re-checked against actual object size; extension *and* content sniffing; per-IP
  rate limiting; short pre-signed URL TTL; filenames never used as filesystem paths. Security
  tests are written in this phase, not retrofitted.
- **Phase 4 (Angular UI):** CORS restricted to known origins; no credentials in frontend code.
- **Phase 5 (slicing):** untrusted input reaches a native binary — the real risk boundary.
  Per-job scratch directory, a CPU/wall-clock timeout that actually fires, bounded
  concurrency, and no shell interpolation of user-controlled strings into the command line.
- **Phase 6 (queue):** dead-letter queue, bounded retries, idempotent consumption.
- **Phase 7 (quotation):** personal data appears here (contact details for an order). Lawful
  basis, retention period and a deletion path for uploaded models must be written down, and
  models deleted on a schedule — a customer's CAD file is their intellectual property, and
  holding it indefinitely is both a liability and a GDPR/FADP problem.
- **Phase 8 (deployment):** TLS; no public ports for Postgres, RabbitMQ or object storage;
  secrets from the environment; backups of database and buckets, with a *verified* restore.

Testing enters per phase: unit tests for pricing (pure logic), Testcontainers integration
tests for storage and persistence, and slice-path tests behind a tagged profile so the large
slicer image is not required for an ordinary `mvn verify`.
