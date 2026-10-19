# ADR 0003 — Synchronous quotes and local file storage for shop v1

**Status:** Accepted
**Date:** 2026-10-18
**Supersedes:** ADR 0001 (object storage) for v1. **Amends:** ADR 0002 (the background
worker and the `202` job contract are deferred; the modular monolith stands).

## Context

ADR 0002 rejected slicing inside the HTTP request because request latency would depend on
attacker-controlled file complexity, and ADR 0001 chose S3-compatible storage with pre-signed
uploads so that bytes never transit the application. Both were written before any slice had
been measured and before the product goal was narrowed.

Two things changed.

**The goal is a print-on-demand shop, not a slicing platform.** What a customer needs is a
price, an order, a payment and a confirmation. Every component that does not serve that path
is cost: a broker, an object store, a job state machine and a polling UI each add a deploy
unit, a failure mode and a page of documentation, for one developer on one server.

**The quote path was built and measured** (see `docs/feasibility/bambu-studio-cli-headless.md`
and the quote implementation):

| Measurement | Value |
|---|---|
| Benchy (11 MB STL, 225k triangles), one copy, A1 profiles | 4 s wall-clock |
| 10 copies, arranged on one plate | 10 s |
| 20 copies (the quantity cap) | 18 s |
| Cached repeat, any colour | about 0.3 s |

Request cost is bounded by a hard timeout (120 s), a two-slot semaphore (`503` + `Retry-After`
when saturated), a per-client limit of 10 starts per minute and 100 per hour (`429`), a 50 MB
upload cap and a quote cache keyed by model hash. A burst of 12 parallel requests from one
address was observed to yield 6 slices, 4 `503` and 2 `429`, with the host responsive
throughout. The concern that motivated ADR 0002 — unbounded latency and thread occupancy under
hostile input — is answered by these guards rather than by a queue.

## Decision

1. **Quotes are synchronous.** `POST /api/quotes` slices in the request and returns the
   priced quote. There is no job table, no `202`, no polling. The guards above are part of the
   contract and stay under test.
2. **Order files live on the local filesystem.** When an order is placed, the model and the
   sliced output are written under a server-generated UUID into a Docker volume, never under
   the client's filename. The volume is part of the nightly backup. The storage boundary is a
   small interface (`store`, `open`, `delete` by key) so an S3 implementation can be added
   behind it if ADR 0001's triggers ever fire.
3. **No message broker in v1.** The RabbitMQ topology, consumer and dependency are removed
   rather than left dormant: dormant infrastructure code is attack surface and reviewer
   confusion. If background work appears (sending jobs to printers, retention deletion), it
   runs from a Postgres table and a scheduler inside the application, as ADR 0002 already
   planned for its first phases.
4. **No MinIO.** The commercial-licence trap documented in ADR 0001 A2 goes away with it.

## Alternatives considered

- **Keep ADR 0001/0002 as written and build storage and the job pipeline first.** Rejected:
  several weeks of infrastructure before the first order, for a load profile that the
  measurements show does not need it.
- **Async quotes with the database as queue (ADR 0002's phase 2 to 5 plan).** Still the right
  shape if slices become long; rejected for now because the synchronous path with guards is
  simpler and no slice has exceeded 20 s.
- **S3 for order files from day one.** Rejected: one server, one volume, one backup. The
  interface keeps the door open.

## Consequences

**Benefits:** the whole system is one JVM, one Postgres and one slicer binary. Local
development is `docker compose up`. The quote path is already tested end to end.

**Trade-offs:** a slow slice holds an HTTP connection; the upload page shows a spinner for up
to 18 s at the quantity cap. The frontend debounces and skips unchanged requests to keep that
rare. Horizontal scaling of slicing needs the deferred worker design; that is a known seam,
not a surprise.

## Revisit when

- p95 slice time exceeds about 30 s, or the quantity cap must rise so far that it does.
- Semaphore rejections (`503`) appear in logs under ordinary traffic.
- A second server is needed, which makes local files and in-memory limits wrong.
- Order volume makes a volume backup impractical.

Each is a measurement; none is "a queue would look more serious".
