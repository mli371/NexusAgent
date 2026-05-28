# Enterprise Readiness Slice

Milestone 10 adds a narrow enterprise-readiness skeleton. It is useful for demonstrating how an enterprise knowledge backend starts to handle tenant boundaries, auditability, ingestion status, and basic operational visibility. It is not production-grade enterprise security.

## Request Context

API requests can include:

```text
X-Tenant-Id: tenant-a
X-Actor-Id: actor-1
X-Trace-Id: trace-123
```

If tenant or actor headers are missing, the local default context is:

```text
tenant_id=default
actor_id=anonymous
```

This keeps local demos simple while still ensuring a request without headers only sees the `default` tenant. The header values are trust-on-input for this MVP. A production system would derive tenant and actor from verified authentication claims, not caller-provided headers.

## Tenant-Aware Filtering

The `documents` table now includes:

- `tenant_id`
- `owner_id`
- `visibility`

Document list/get, chunking, embedding, retrieval, context construction, query, and agent flows use the effective request context. Retrieval repositories join back to `documents` and filter by tenant before returning child chunk candidates.

Visibility is intentionally simple:

- `TENANT`: accessible to the tenant.
- `PRIVATE`: accessible only to the owner in the same tenant.

This is an access-model skeleton. It is not full RBAC or ABAC.

## Tenant-Aware Retrieval Cache

Redis retrieval cache keys include the effective tenant and actor as part of the hashed cache input, along with the normalized question, document IDs, `topK`, context budget, and retrieval/context settings. This means the same question from tenant A and tenant B produces different cache keys.

The actor is included even for tenant-wide documents. That keeps the cache safe for `PRIVATE` visibility because an owner-scoped query and another actor's query in the same tenant do not share a cached context entry.

Cache keys are still short-lived TTL entries. PostgreSQL remains the source of truth for document metadata, chunks, embeddings, and visibility.

## Audit Events

The `audit_events` table records important lifecycle events:

- `DOCUMENT_UPLOADED`
- `DOCUMENT_CHUNKED`
- `FORCE_RECHUNKED`
- `DOCUMENT_EMBEDDED`
- `QUERY_EXECUTED`
- `AGENT_QUERY_EXECUTED`

Audit metadata avoids raw document text, full retrieved context, embeddings, and large user-controlled values. Query-like metadata uses a hash and small counts instead of storing sensitive user text or context blobs.

## Ingestion Jobs

The `ingestion_jobs` table records synchronous status rows for chunking and embedding endpoints.

Supported job types:

- `CHUNK`
- `EMBED`
- `FORCE_RECHUNK`
- `REEMBED`

Supported statuses:

- `PENDING`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`

Current chunk/embed APIs still execute synchronously. The job rows provide visibility into what happened; they are not a background queue or async worker system yet.

Inspect jobs for a document:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/ingestion-jobs \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1"
```

## Observability Basics

Spring Boot Actuator exposes safe local endpoints:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/info
```

Structured logs are emitted for upload, chunking, embedding, retrieval candidate counts, query completion, agent workflow completion, ingestion job transitions, and audit event writes.

This is not full OpenTelemetry, tracing, metrics, alerting, or production observability.

## Interview Defense

Explain this milestone as an enterprise-awareness slice:

> I added tenant-aware request context, document tenant fields, retrieval filtering, audit events, ingestion job status rows, trace propagation, and safe health/info endpoints. I intentionally did not build full enterprise security. The goal is to show the access-control and audit boundaries that production systems need, while documenting that real authentication, authorization, tenant isolation, and observability remain future work.

## Known Limitations

- Header-based tenant and actor context is not authentication.
- There is no OAuth2, JWT verification, Keycloak, RBAC, or ABAC.
- There is no production tenant isolation guarantee without real auth and authorization.
- Audit metadata is intentionally small and does not replace a compliance-grade audit system.
- Ingestion jobs are synchronous records, not async workers.
- Actuator exposure is limited to health/info only.
