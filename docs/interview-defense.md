# NexusAgent Interview Defense

This document collects concise explanations for how to defend NexusAgent design choices in an interview. It should be updated after each milestone.

## Project Summary

NexusAgent is an Enterprise Knowledge Assistant Backend built with Java 17 and Spring Boot 3.x. It is designed to ingest documents, store raw files, chunk and embed content, retrieve relevant context through hybrid search, and serve query responses with citations.

The project prioritizes explainable backend design over broad but shallow feature coverage.

## High-Level Talking Points

- The backend starts as a single Spring Boot service to keep local development, testing, and interview explanation straightforward.
- MinIO stores raw uploaded files, PostgreSQL stores source-of-truth metadata and retrieval data, and Redis is reserved for short-lived state/cache.
- Parent-child chunking separates retrieval precision from context quality.
- Hybrid retrieval combines semantic vector search with keyword/full-text search.
- RRF is used to fuse candidates from different retrieval strategies without requiring their scores to be directly comparable.
- Reranking is a separate stage so the retrieval pipeline can be improved without rewriting search.
- WebFlux/Reactor is used for I/O orchestration, while blocking dependencies are isolated and documented.

## Milestone Defense Notes

### Milestone 1: Foundation + Raw Document Upload

Status: Implemented.

What was built:

- Spring Boot WebFlux backend
- Docker Compose for PostgreSQL with PgVector, Redis, and MinIO
- Flyway migration for the `documents` table
- Raw document upload to MinIO
- Metadata persistence in PostgreSQL
- APIs for upload, fetch-by-id, list, and health

How to explain it:

> I built the storage foundation before retrieval. MinIO stores the raw uploaded bytes, and PostgreSQL stores the source-of-truth metadata record with the MinIO bucket and object key. The upload API does not claim that extraction or retrieval has happened; it only returns a `STORED` document record.

Design defense:

- MinIO and PostgreSQL have separate responsibilities. The object store keeps large file bytes; the database keeps queryable metadata.
- The application uses WebFlux, but blocking file hashing and MinIO SDK calls are isolated on Reactor `boundedElastic`.
- `ObjectStorageService` is an integration boundary, so the upload orchestration can be tested without MinIO.
- R2DBC `DatabaseClient` keeps metadata persistence reactive.
- Flyway owns schema creation through JDBC, including enabling PgVector for later milestones. Request-time persistence uses R2DBC.
- The Docker Compose database image is `pgvector/pgvector:pg16`, and `CREATE EXTENSION IF NOT EXISTS vector` is idempotent.

Failure behavior:

- Empty files are rejected.
- Oversized files are rejected based on `nexus.upload.max-file-size-bytes`.
- Missing documents return a 404.
- Invalid request input returns a 400.
- Temporary upload files are cleaned up through Reactor `usingWhen` on success, error, and cancellation.
- If metadata persistence fails after MinIO storage succeeds, the service attempts best-effort deletion of the stored object.
- Unexpected server failures return a generic 500 response instead of leaking low-level details.

Known limitations:

- Text extraction is not implemented.
- Parent-child chunking is not implemented.
- Embeddings, retrieval, RRF, reranking, context construction, query answering, and SSE are not implemented.
- Redis is present in Docker Compose but not used by the application yet.
- If MinIO storage succeeds but PostgreSQL persistence fails, an orphaned object may still remain if best-effort cleanup also fails.
- There is no authentication, authorization, tenant isolation, malware scanning, or production observability yet.

Production changes:

- Add outbox-based reconciliation for partial failures that remain after best-effort cleanup.
- Add access control and tenant-aware authorization.
- Add upload limits at the HTTP edge.
- Add structured logging, metrics, tracing, and operational health checks.
- Add file type validation and malware scanning for enterprise use.

### Milestone 2: Text Extraction + Parent-Child Chunking

Status: Not implemented yet.

### Milestone 3: Embedding Pipeline + PgVector

Status: Not implemented yet.

### Milestone 4: Hybrid Retrieval + RRF

Status: Not implemented yet.

### Milestone 5: Reranking + Context Construction

Status: Not implemented yet.

### Milestone 6: Query API + SSE, With Placeholder State/Cache Interfaces

Status: Not implemented yet.

Important defense point:

- The query API and SSE flow may exist here, but Redis-backed session/cache/tool-output storage is not complete until Milestone 7.

### Milestone 7: Redis State + Cache Integration

Status: Not implemented yet.

### Milestone 8: Plan-Execute-Critique Workflow

Status: Not implemented yet.

### Milestone 9: Hardening + Interview Polish

Status: Not implemented yet.

## Trade-Off Language

Use specific, honest language:

- "This is deterministic for local testing, not a production embedding model."
- "Redis is used as a cache/state store, not the source of truth."
- "This milestone defines the interface, but the Redis implementation arrives in Milestone 7."
- "This design favors interview clarity and local reproducibility over distributed-system complexity."

Avoid unsupported claims:

- Do not claim production readiness before production hardening exists.
- Do not claim benchmarked performance without measurements.
- Do not imply AI generation, retrieval, or Redis behavior exists before it is implemented.

## Future Updates

After each milestone, add:

- What was built
- Why the design was chosen
- Failure handling
- Known limitations
- What would change in production
