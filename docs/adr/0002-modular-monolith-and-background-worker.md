# ADR 0002 — Modular monolith with a background worker

**Status:** Accepted
**Date:** 2026-10-14

## Context

Slicing is CPU-bound and slow relative to an HTTP request. A single slice of a real model
takes seconds to minutes, so it cannot run inside the request that asks for a quotation
without holding a connection open and making the API's latency a function of the heaviest
file a stranger chose to upload. Work has to move off the request thread.

That requirement — "offload heavy work" — is routinely answered with microservices, and the
existing `README.md` already describes this project in those terms, including KEDA-driven
Kubernetes autoscaling. The actual constraints point the other way:

- One existing Hetzner Cloud server, near-zero additional budget.
- Docker Compose, explicitly not Kubernetes.
- A single developer.
- No measured load. There are no users yet, so there is no throughput figure to design to.
- The portfolio goal is to demonstrate engineering judgement, and choosing a distributed
  architecture for a single-server, single-developer, zero-traffic system demonstrates the
  opposite.

Separate services would also multiply the cost that matters here: not CHF, but the number of
deploy units, network boundaries, failure modes and local-development steps one person has to
hold in their head.

## Decision

Build a **modular monolith**: one Spring Boot application, one deployable artifact, with
internal modules (`upload`, `slicing`, `quote`, `storage`) separated by package boundaries and
communicating through interfaces rather than reaching into each other's internals.

Within that artifact, run slicing as a **background worker**:

- The web layer accepts a quotation request, persists a `SliceJob` in state `QUEUED`, and
  returns `202 Accepted` immediately.
- A worker component consumes queued jobs, executes the slicer, and updates job state.
- **Phases 2–5 use the database as the queue** (`SELECT ... FOR UPDATE SKIP LOCKED`), because
  PostgreSQL is already a required dependency and this adds no new moving part.
- **Phase 6 replaces the transport with RabbitMQ**, keeping the same job-state model and the
  same module boundary.

The worker stays in the same deployable unit, started by configuration
(`app.worker.enabled`), so it can later run as a separate container from the *same image*
without any code change.

## Alternatives considered

- **Microservices (API service + slicer service).** Independent scaling and isolation of the
  native slicer crash surface. Rejected for now: multiple deploy units, inter-service
  contracts, distributed tracing and a service mesh's worth of failure modes, all for a system
  with one server and no traffic. The chosen design keeps the seam where a split would happen.
- **Synchronous slicing in the HTTP request.** Simplest possible option. Rejected: ties
  request latency and thread occupancy to attacker-controlled file complexity, and makes a
  slow upload indistinguishable from an outage.
- **Spring `@Async` / in-memory executor, no persisted job.** Simpler than a queue. Rejected:
  jobs vanish on restart with no record of what was lost, and a paying customer's quotation
  silently disappearing is a business failure, not a technical one.
- **RabbitMQ from the start.** It is already in `docker-compose.yml` and on the POM. Deferred
  deliberately: a broker is a second source of truth for job state, with its own delivery
  semantics, redelivery and dead-lettering to get right. Introducing it before any job
  pipeline works at all means debugging two unproven things at once. The project constraint
  states RabbitMQ comes later, and this ADR records *why* that ordering is correct.
- **Kubernetes with KEDA.** Out of scope by constraint, and unjustifiable on one server.

## Consequences

**Benefits**

- One build, one deploy, one log stream; `docker compose up` is the whole local environment.
- Refactoring across module boundaries stays a compile-time operation rather than a
  versioned-API negotiation.
- The async contract (`202` + job state + polling) is settled in Phase 3 and never changes,
  so swapping the queue transport in Phase 6 is invisible to the frontend.
- Running the worker from the same image with a different flag means horizontal scaling is a
  Compose change, not a rewrite.

**Trade-offs**

- The web and worker share a JVM and a machine, so a runaway slice can degrade API latency.
  Mitigated by bounding worker concurrency and enforcing a hard slice timeout (Phase 5).
- A native-code crash in the slicer could in principle take down the process hosting the API.
  The slicer runs as a child process rather than in-JVM, which contains the blast radius.
- Module boundaries are a convention until they are enforced. An automated check (ArchUnit or
  Spring Modulith) should be added once the modules actually exist.
- Database-as-queue does not scale to high job rates. That is the point: it is the simple
  option that gets replaced when measurement, not speculation, says it must be.

## Revisit when

- Slicing throughput measurably contends with API latency on the one server.
- Slicing needs to scale on different hardware from the API (e.g. a dedicated CPU machine).
- More than one developer needs to deploy independently.
- Job volume outgrows `SKIP LOCKED` polling — which is the Phase 6 trigger for RabbitMQ.

Each of those is a measurement. None of them is "a microservice architecture looks more
impressive".
