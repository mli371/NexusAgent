# Milestone 10: Enterprise Readiness Slice

## Problem

The MVP had the core retrieval pipeline, but enterprise systems also need a first pass at access boundaries, auditability, ingestion status, and operational visibility. Milestone 10 adds those signals without claiming production-grade security.

## Main Components

- `RequestContext`: resolves `X-Tenant-Id`, `X-Actor-Id`, and local defaults.
- `DocumentVisibility`: models `PRIVATE` and `TENANT` document visibility.
- `AuditService`: writes bounded audit events without raw document text or full context.
- `IngestionJobService`: records synchronous chunk/embed job transitions.
- Tenant-aware repository methods: filter documents and retrieval candidates by tenant.

## Data Flow

Requests enter with optional tenant and actor headers. Controllers resolve a `RequestContext` and pass it into document, chunking, embedding, retrieval, context, query, and agent services.

Upload stores tenant and owner metadata on the document. Retrieval joins candidate chunks back to `documents`, so vector and full-text paths cannot return another tenant's documents.

Chunk and embed endpoints create ingestion job rows, mark them succeeded or failed, and write audit events. Query and agent flows write audit events using query hashes and small metadata.

## Why This Design

This is intentionally small:

- It shows where tenant and actor context belongs.
- It keeps PostgreSQL as the source of truth.
- It keeps Redis as short-lived cache/state only.
- It avoids pretending headers are real authentication.
- It avoids building a full async worker system before it is needed.

## Simplifications

- Header-based identity is trust-on-input.
- Visibility is simple and not full RBAC/ABAC.
- Ingestion jobs are synchronous status records.
- Actuator exposes only health/info.
- Logs are structured enough for local debugging, not a full observability stack.

## How To Run

```bash
mvn test
```

Local demo with tenant headers:

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -F "file=@examples/security-handbook.md;type=text/markdown"
```

Inspect ingestion jobs:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/ingestion-jobs \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1"
```

## Interview Defense

Say:

> Milestone 10 is an enterprise-readiness skeleton. It adds tenant-aware filtering, audit events, synchronous ingestion job status, trace propagation, and limited Actuator endpoints. It does not claim production security because real enterprise isolation requires verified auth, authorization policy, audit retention, and operational controls.

## Future Work

- Replace header trust with verified auth claims.
- Add RBAC/ABAC and document-level ACLs.
- Add async ingestion workers.
- Add audit retention and compliance export.
- Add metrics, tracing, dashboards, and alerting.
