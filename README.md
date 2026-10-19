# CAD Slicer Engine

[![CI](https://github.com/nebjor/cad-slicer-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/nebjor/cad-slicer-engine/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Backend for a print-on-demand shop: it turns an uploaded 3D model into a priced quote by
running the headless Bambu Studio CLI against the Bambu Lab A1 profiles.

## What it does today

- `POST /api/quotes` accepts an STL or 3MF (up to 50 MB) plus filament, colour, quality and
  quantity, slices the whole quantity as one arranged batch across plates and returns print
  time, material, plate count, dimensions, an itemised price and a lead time.
- `GET /api/quotes/options` lists the filaments, colours, quality tiers and limits the backend
  accepts; `GET /api/quotes/sample` quotes a bundled sample model for local development.
- Profiles are passed explicitly and flattened, because the CLI ignores the `inherits` chain
  of the bundled profiles; mass is derived from filament length and density.
- Three guards protect the host: a semaphore on concurrent slicer runs (`503` + `Retry-After`),
  a per-client rate limit of 10 starts per minute and 100 per hour (`429` + `Retry-After`), and
  a quote cache keyed by model hash so repeated requests never slice twice.
- Failures are RFC 9457 problem details without stack traces or server paths.

The Angular frontend and the local Docker stack live in
[nebjor/pod-frontend](https://github.com/nebjor/pod-frontend).

## Not yet built

Orders, payment, notifications, an admin view and deployment are the next phases;
`docs/ROADMAP.md` lists each with acceptance criteria and marks what is done.
`docs/architecture.md` and the ADRs record the decisions behind the design, including why
quotes are synchronous and why there is no object store or message broker in v1 (ADR 0003).

## Run it

Requirements: JDK 21, Docker. The Bambu Studio CLI is only needed to produce real quotes; the
unit tests do not need it.

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

The service starts on port 8080 with the development defaults from `application.yml`, which
match `docker-compose.yml`. Every credential can be overridden through the variables listed in
`.env.example`. Postgres is the only backing service.

For quotes with a real slicer use the Compose stack in the frontend repository, which installs
the CLI into the backend container.

## Build and test

```bash
./mvnw -B verify
```

Runs 68 unit and web-layer tests in about 20 seconds: pricing arithmetic pinned to real Benchy
slices, profile flattening, process handling against a shell script that imitates the CLI,
rate limiting, caching, the concurrency gate and the HTTP contract. CI runs the same command on
every push. `ArchitectureTest` (ArchUnit) enforces the module boundaries of the monolith: no
cycles between modules, the quote module knows nothing about orders or payments, the order
module owns the payment port, controllers never touch repositories, no field injection.

## Configuration

| Block | Purpose |
|---|---|
| `slicer.*` | CLI binary, timeout, profile directory, machine profile, bed type, concurrency |
| `quote.*` | currency, setup fee, machine rate, material prices, quantity cap, cache |
| `catalog.*` | colours stocked per filament |
| `rate-limit.*` | per-client limits, support address, admin token for resets |
| `spring.datasource.*` | database credentials, from the environment |

Schema changes go through Flyway migrations in `src/main/resources/db/migration`; Hibernate
only validates.
