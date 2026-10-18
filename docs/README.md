# Documentation

| Document | What it answers |
|---|---|
| [`architecture.md`](architecture.md) | How the system is shaped, the upload-to-quotation workflow, what is actually implemented today, and where security/testing/data protection enter |
| [`ROADMAP.md`](ROADMAP.md) | The eight phases in dependency order, each with acceptance criteria written as a command to run and a result to check |
| [`adr/0001-object-storage.md`](adr/0001-object-storage.md) | Why S3-compatible object storage, local MinIO for development, Hetzner Object Storage later — plus the Phase 0 findings that affect how it gets implemented |
| [`adr/0002-modular-monolith-and-background-worker.md`](adr/0002-modular-monolith-and-background-worker.md) | Why one modular monolith with a background worker instead of microservices, and why RabbitMQ and Kubernetes are deferred |
| [`feasibility/bambu-studio-cli-headless.md`](feasibility/bambu-studio-cli-headless.md) | Whether headless Bambu Studio slicing works in Docker — reproduction commands, verified findings, and open questions |

## Conventions

- **ADRs** are numbered, immutable records of a decision and its reasoning. Supersede or add a
  dated addendum rather than rewriting history.
- **Feasibility notes** separate *verified* (a command was run, output recorded) from
  *unresolved*. Anything not actually executed is labelled unverified.
- **Component status** in `architecture.md` is tagged `[implemented]`, `[partial]` or
  `[planned]`. A document never implies something works that has not been run.
