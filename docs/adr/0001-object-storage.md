# ADR 0001 — S3-Compatible Object Storage

**Status:** Superseded for v1 by [ADR 0003](0003-synchronous-quotes-and-local-storage.md)
(2026-10-18). Order files use the local filesystem behind a storage interface; the S3
decision below is the fallback when ADR 0003's revisit triggers fire.

## Context

The CAD Slicer Engine needs to store user-uploaded STL files and generated slicing artifacts.

The application is initially hosted on an existing Hetzner Cloud server, with minimal additional infrastructure budget.

The storage implementation should be replaceable without affecting the application API.

## Decision

Use S3-compatible object storage through AWS SDK for Java v2.

During local development, use MinIO Server in Docker. Initially deploy using existing Hetzner server resources where practical, with the option to migrate to Hetzner Object Storage later.

Use a dedicated storage abstraction rather than coupling controllers to SDK-specific operations.

## Alternatives considered

- Local filesystem: simpler initially, but creates tighter coupling to the application server and complicates independent scaling.
- AWS S3: managed service with strong integration, but introduces another provider and additional cost.
- Hetzner Object Storage: managed S3-compatible service, attractive when the business requires independent storage.
- MinIO Server: self-hosted, inexpensive using existing capacity, but requires backup and operational responsibility.

## Consequences

**Benefits**
- No additional cloud storage bill during initial development.
- Transferable knowledge of the S3 API.
- Storage implementation can be switched by configuration.
- Compatible with later asynchronous job processing.

**Trade-offs**
- Self-hosted storage must be maintained and backed up.
- S3 implementations are not perfectly interchangeable.
- Provider-specific features must be tested separately.

## Revisit when

Customer data volume, availability requirements or operational effort justify moving to managed object storage.

---

## Addendum — Phase 0 verification (2026-10-14)

The decision above is unchanged, but Phase 0 established three facts that affect how it gets
implemented. Recorded here so the gap between the decision and the code is explicit.

### A1. The code does not yet follow this ADR

This ADR commits to AWS SDK for Java v2. The current implementation does not use it:

- `pom.xml` declares `io.minio:minio:9.0.3`.
- `pom.xml` also hand-pins `com.squareup.okhttp3:okhttp-jvm:5.3.2`, which exists *only* to
  satisfy MinIO's OkHttp 5 requirement.
- `pom.xml` declares `<aws.sdk.version>2.25.15</aws.sdk.version>`, a property consumed by no
  dependency.
- `StorageService` is written against `MinioClient`.

Moving to AWS SDK v2 therefore **removes two dependencies, one of them a manual version
override**, rather than adding one. It is also the only route to the pre-signed upload URLs
the architecture depends on through the SDK this ADR commits to: the MinIO client's presigning
is vendor-specific, which defeats the stated goal of a swappable implementation.

Status: **not yet implemented.** Scheduled as Phase 2, with "no `io.minio` or `okhttp-jvm`
in the dependency tree" as an explicit acceptance criterion.

### A2. The MinIO image currently in `docker-compose.yml` cannot serve S3 at all

`docker-compose.yml` pins `quay.io/minio/aistor/minio:latest`. Verified by running it:

```
minio version RELEASE.2026-09-19T17-05-25Z
License: MinIO AIStor License
...
WARN: No valid license found, running in offline mode. All S3 operations are denied.
      Please install a valid license using 'mc license update' to restore service.
```

This is MinIO's **commercial AIStor** product (image labels give
`org.opencontainers.image.source: https://github.com/miniohq/aistor`), not the community
build. It directly contradicts the near-zero-cost constraint.

Worse, the container stays `running` and `GET /minio/health/live` returns `200`, so the
existing Compose healthcheck reports the service **healthy** while every S3 call is refused.
A green healthcheck in front of a dead service is the most expensive kind of wrong.

The former community images are no longer publicly pullable:

```
docker pull quay.io/minio/minio:latest   -> unauthorized: access to the requested resource is not authorized
docker pull minio/minio:latest           -> pull access denied for minio/minio, repository does not exist
```

So "use MinIO locally" is no longer a free choice of image. Candidates checked on 2026-10-14
— pull verified, and licence read out of the binary itself rather than assumed from the name,
because an image being MinIO-branded is exactly what went wrong above:

| Image | Pull | `minio --version` reports | Size |
|---|---|---|---|
| **`chainguard/minio:latest`** | ok | `RELEASE.2026-09-22T19-25-18Z`, **`License: GNU AGPLv3`** | 169 MB |
| `bitnamilegacy/minio:latest` | ok | `DEVELOPMENT.2025-05-24T17-08-30Z`, `License: GNU AGPLv3` | 280 MB |
| `quay.io/minio/aistor/minio:latest` *(current)* | ok | `RELEASE.2026-09-19`, **`License: MinIO AIStor License`** → denies all S3 | 431 MB |
| `dxflrs/garage:v1.0.1` | ok | not checked — different implementation (Garage) | 22 MB |
| `chrislusf/seaweedfs:latest` | ok | not checked — different implementation (SeaweedFS) | 529 MB |

**Recommended replacement: `chainguard/minio`.** It is a current AGPLv3 community MinIO build,
so it keeps the MinIO behaviour the development workflow assumes without the licence wall. It
has *not* yet been exercised for a bucket round-trip or pre-signed URLs — that is the first
Phase 2 acceptance criterion, not a claim being made here.

Garage and SeaweedFS remain the fallbacks if MinIO's community distribution degrades further.
Both are independent S3 implementations, so switching to one tests the storage abstraction for
real; Garage in particular is a 22 MB image. Neither was functionally evaluated.

**This is a hard Phase 2 blocker:** no storage work can pass its acceptance criteria until the
image is replaced and a bucket round-trip actually succeeds.

### A3. The abstraction choice is now load-bearing, not just tidy

A2 is exactly the scenario this ADR's "storage implementation can be switched by
configuration" clause was written for — the local S3 server had to change for licensing
reasons, before the product even has a user. Keeping controllers free of SDK types, and
keeping the S3 endpoint/credentials in configuration, is what makes that a Compose edit rather
than an application rewrite. The same property is what makes a later move to Hetzner Object
Storage a configuration change.

Consequence for implementation: the storage boundary must expose only `put`, `get`, `head`,
`delete` and `presignPut`/`presignGet` in terms of keys and streams. No MinIO or AWS type may
appear in a module outside the storage adapter.

