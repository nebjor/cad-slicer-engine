# Documentation

| Document | What it answers |
|---|---|
| [`architecture.md`](architecture.md) | How the system is shaped, the quote path as it runs today, what is implemented, and where security/testing/data protection enter |
| [`ROADMAP.md`](ROADMAP.md) | The shop v1 phases in dependency order, each with acceptance criteria written as a command to run and a result to check |
| [`adr/0001-object-storage.md`](adr/0001-object-storage.md) | The original S3-compatible storage decision and the Phase 0 findings; superseded for v1 by ADR 0003 |
| [`adr/0002-modular-monolith-and-background-worker.md`](adr/0002-modular-monolith-and-background-worker.md) | Why one modular monolith instead of microservices; the background worker is deferred by ADR 0003 |
| [`adr/0003-synchronous-quotes-and-local-storage.md`](adr/0003-synchronous-quotes-and-local-storage.md) | Why quotes are synchronous with in-process guards, order files live on a local volume, and the broker was removed — with the measurements behind it |
| [`adr/0004-multi-item-orders.md`](adr/0004-multi-item-orders.md) | Why an order holds several items placed by model id, and what that changes for pricing, payment and erasure |
| [`adr/0005-passwordless-customer-accounts.md`](adr/0005-passwordless-customer-accounts.md) | Why customer accounts log in by e-mail link instead of a password, how the cookie session and the CSRF guard work, and what is retained |
| [`adr/0006-quantity-by-arithmetic.md`](adr/0006-quantity-by-arithmetic.md) | Why one copy is sliced and every quantity derived by arithmetic, with the production measurement behind the per-plate overhead and the plate packing |
| [`data-protection.md`](data-protection.md) | Per field: why it is held, the lawful basis, the retention period and the deletion path |
| [`feasibility/bambu-studio-cli-headless.md`](feasibility/bambu-studio-cli-headless.md) | Whether headless Bambu Studio slicing works in Docker — reproduction commands, verified findings, and open questions |

## Conventions

- **ADRs** are numbered, immutable records of a decision and its reasoning. Supersede or add a
  dated addendum rather than rewriting history.
- **Feasibility notes** separate *verified* (a command was run, output recorded) from
  *unresolved*. Anything not actually executed is labelled unverified.
- **Component status** in `architecture.md` is tagged `[implemented]`, `[partial]` or
  `[planned]`. A document never implies something works that has not been run.
