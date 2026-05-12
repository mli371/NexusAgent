# NexusAgent Design Defense

This document collects concise explanations for NexusAgent design choices. It should be updated after each milestone.

## Project Summary

NexusAgent is an Enterprise Knowledge Assistant Backend built with Java 17 and Spring Boot 3.x. It is designed to ingest documents, store raw files, chunk and embed content, retrieve relevant context through hybrid search, and serve query responses with citations.

The project prioritizes explainable backend design over broad but shallow feature coverage.

## High-Level Talking Points

- The backend starts as a single Spring Boot service to keep local development, testing, and operational reasoning straightforward.
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
- At Milestone 1, embeddings, retrieval, RRF, reranking, context construction, query answering, and SSE were not implemented.
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

Status: Implemented.

What was built:

- `DocumentTextExtractor` interface
- Text/Markdown extraction from raw MinIO objects
- `parent_chunks` table
- `child_chunks` table
- Parent-child chunking service
- APIs to extract/chunk a document and inspect chunks
- Tests for extraction, parent splitting, child sliding windows, relationships, empty text, short documents, long paragraphs, idempotency, force regeneration, and status updates

How to explain it:

> I added a deterministic ingestion stage after raw upload. For supported text and Markdown files, the service reads the original object from MinIO, extracts UTF-8 text, creates larger parent chunks for context, then creates smaller overlapping child chunks inside each parent. The child chunks are the future retrieval units; the parent chunks are the future context-expansion units.

Design defense:

- Extraction is behind `DocumentTextExtractor`, so PDF and Word support can be added without rewriting the chunking pipeline.
- Parent chunks and child chunks are stored in separate tables because they have different future roles.
- Child chunks include `parent_chunk_id`, so retrieval can later find precise child hits and expand to the parent context.
- The algorithm is deterministic and testable. It uses paragraph-aware parent splitting and character-window child splitting with overlap.
- Chunking is idempotent by default, which keeps chunk IDs stable for future embeddings, retrieval results, citations, and cache entries.
- `force=true` is the explicit regeneration path when source content or chunking rules change.
- MinIO reads are still blocking through the MinIO SDK and remain isolated behind `ObjectStorageService` on `boundedElastic`.

Failure behavior:

- Unsupported file types return a clear bad-request error.
- Blank extracted text is rejected before chunk persistence.
- Missing documents return 404.
- Successful chunking marks the document `CHUNKED`.
- Failed chunking attempts mark the document `CHUNKING_FAILED` when practical.
- Re-running chunking without `force=true` returns existing chunks unchanged.
- `force=true` deletes old child chunks and parent chunks, then inserts regenerated chunks inside the repository transaction boundary.

Known limitations:

- Only plain text and Markdown-like files are supported.
- PDF and Word extraction are not implemented.
- Token counts are approximate whitespace counts.
- At Milestone 2, embeddings, vector search, hybrid retrieval, reranking, query answering, Redis state/cache, and SSE were still out of scope.
- Chunk sizes are character-based, not model-token-based.
- Forced regeneration can invalidate future embeddings, citations, and caches, but invalidation is not needed until those features exist.

Production changes:

- Add robust PDF and Office extractors.
- Add language-aware and model-token-aware chunking.
- Add chunk versioning or an ingestion-job audit table for regeneration history.
- Add observability around extraction duration, chunk counts, and failures.

### Milestone 3: Embedding Pipeline + PgVector

Status: Implemented.

What was built:

- `EmbeddingProvider` interface
- `LocalDeterministicEmbeddingProvider` for local demos and tests
- `SpringAiEmbeddingProvider` adapter boundary
- `child_chunk_embeddings` table with `vector(384)`
- Child chunk embedding service
- PgVector persistence and exact cosine search repository
- APIs to embed a document's child chunks and inspect embedding status
- Tests for deterministic dimensions, child-only embedding, embedding persistence, API routes, and vector similarity search

How to explain it:

> I added the embedding stage after chunking. The service embeds only child chunks because they are the precise retrieval units. Parent chunks stay unembedded and are reserved for context expansion. Embeddings are stored in a separate `child_chunk_embeddings` table keyed by `child_chunk_id`, using PgVector with a fixed 384-dimensional local deterministic provider for repeatable tests and demos.

Design defense:

- `EmbeddingProvider` keeps provider choice behind a small boundary.
- The default local provider is deterministic and requires no external API key, so tests are stable.
- The Spring AI adapter boundary is optional and not enabled unless a real client bean is supplied.
- Child chunks are embedded, not parent chunks, to keep future vector search precise.
- Parent chunks remain available through `parent_chunk_id` for later context expansion.
- Embeddings live in a separate table so vector/provider metadata is decoupled from chunk metadata.
- `child_chunk_id` is the primary key, so one current embedding row exists per child chunk.

Failure behavior:

- Embedding a document with no child chunks returns a clear bad-request error.
- Re-running embedding skips child chunks that already have embeddings.
- If `force=true` chunking deletes child chunks, old embeddings are deleted by foreign-key cascade.
- No external API key is required for local tests.

Known limitations:

- The deterministic local provider is not a semantic production model.
- Embedding dimension is fixed at 384 in the schema.
- There is no embedding job table, retry state, or failure reason history.
- PgVector search exists only at repository level; no retrieval API, hybrid search, RRF, or reranking is implemented yet.
- The HNSW index is created only when the PgVector build exposes the `hnsw` access method; exact scan remains the fallback.

Production changes:

- Add a real Spring AI `EmbeddingModel` client adapter.
- Track embedding jobs, failures, retries, and provider/model versions.
- Add explicit cache/vector invalidation events after forced re-chunking.
- Tune vector indexes and distance operators based on real data volume and provider behavior.

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

### Milestone 9: Hardening + Documentation Polish

Status: Not implemented yet.

## Trade-Off Language

Use specific, honest language:

- "This is deterministic for local testing, not a production embedding model."
- "Redis is used as a cache/state store, not the source of truth."
- "This milestone defines the interface, but the Redis implementation arrives in Milestone 7."
- "This design favors clarity and local reproducibility over distributed-system complexity."

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
