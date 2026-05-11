# NexusAgent Architecture

This document will evolve milestone by milestone. It records the architecture that exists, the architecture that is planned, and the trade-offs behind each decision.

## Purpose

NexusAgent is an Enterprise Knowledge Assistant Backend. It ingests enterprise documents, stores raw files and metadata, chunks text for retrieval, embeds child chunks, retrieves relevant context through hybrid search, and serves query responses with citations.

The system is designed as an interview-defensible backend project: clear boundaries, practical trade-offs, runnable local dependencies, and no hidden fake features.

## Current Architecture

Milestones 1 and 2 are implemented.

```text
Client
  |
  | POST multipart /api/v1/documents
  v
DocumentController
  |
  v
DocumentUploadService
  |
  +-- temporary local upload file
  +-- UploadedFileInspector: size + SHA-256
  +-- ObjectStorageService -> MinIO raw file
  +-- DocumentRepository -> PostgreSQL document metadata
  +-- best-effort MinIO cleanup if metadata persistence fails
```

Read APIs:

```text
GET /api/v1/documents/{id}
GET /api/v1/documents
GET /api/v1/health
```

Redis is available in Docker Compose but is not used by the application through Milestone 2.

Milestone 2 adds text extraction and parent-child chunking:

```text
POST /api/v1/documents/{id}/chunks
  |
  v
DocumentChunkingService
  |
  +-- DocumentRepository -> load document metadata
  +-- ChunkRepository -> return existing chunks when force=false and chunks already exist
  +-- DocumentTextExtractionService
  |     +-- PlainTextDocumentTextExtractor
  |     +-- ObjectStorageService -> read raw MinIO object
  |
  +-- ParentChildChunker
  |     +-- parent chunks: larger context blocks
  |     +-- child chunks: smaller overlapping retrieval windows
  |
  +-- ChunkRepository -> transactionally replace PostgreSQL parent_chunks and child_chunks when missing or forced
```

Chunk inspection API:

```text
GET /api/v1/documents/{id}/chunks
```

## Target Architecture

```text
Client
  |
  | REST / SSE
  v
Spring Boot WebFlux API
  |
  +-- Document ingestion
  |     +-- MinIO raw file storage
  |     +-- PostgreSQL document metadata
  |     +-- Text extraction
  |     +-- Parent-child chunking
  |
  +-- Embedding pipeline
  |     +-- EmbeddingProvider interface
  |     +-- Deterministic local provider
  |     +-- Spring AI adapter boundary
  |     +-- PgVector storage
  |
  +-- Retrieval pipeline
  |     +-- Vector search
  |     +-- PostgreSQL full-text search
  |     +-- RRF fusion
  |     +-- Reranking
  |     +-- Context construction with citations
  |
  +-- Query workflow
        +-- Query API
        +-- SSE response flow
        +-- Session/cache/tool output interfaces
        +-- Redis-backed implementations in Milestone 7
```

## Storage Responsibilities

- MinIO stores raw uploaded files.
- PostgreSQL stores source-of-truth document metadata and chunk metadata.
- `parent_chunks` stores larger context blocks.
- `child_chunks` stores smaller windows with `parent_chunk_id` relationships.
- PgVector is enabled by Flyway in Milestone 1 for later vector embedding storage.
- Redis stores short-lived session state, retrieval cache entries, and intermediate tool outputs after Milestone 7.

Redis is not the source of truth.

## Reactive Design

WebFlux and Reactor are used to compose I/O-bound stages. Blocking libraries must be isolated behind small service boundaries and scheduled appropriately.

Examples that may require isolation:

- MinIO SDK operations: isolated in `MinioObjectStorageService` on `boundedElastic`
- MinIO object reads for text extraction: isolated in `MinioObjectStorageService` on `boundedElastic`
- File hashing: isolated in `UploadedFileInspector` on `boundedElastic`
- Temporary file creation/deletion: isolated in `DocumentUploadService` on `boundedElastic`
- Text extraction libraries
- Blocking database or provider clients, if introduced

Milestone 1 uses reactive PostgreSQL access through R2DBC `DatabaseClient`.

Flyway uses JDBC at startup for schema migration. Runtime persistence uses R2DBC during request handling.

Temporary upload files are cleaned up with Reactor `usingWhen`, which handles success, error, and cancellation paths.

## Planned Package Boundaries

```text
com.nexusagent
  common
  config
  documents
  storage
  chunking
  embeddings
  retrieval
  query
  workflow
  cache
```

## Key Trade-Offs

- Start as one backend service to keep the system understandable and runnable.
- Use interfaces at external integration boundaries rather than abstracting every class.
- Add `DocumentTextExtractor` as an extension point for PDF and Word later, while implementing only text and Markdown now.
- Store child chunks separately from parent chunks to make the future embedding table naturally point at child chunks.
- Keep chunking idempotent by default because later embeddings, retrieval results, citations, and caches will reference stable chunk IDs.
- Add Redis only after the query API exists, so the project does not imply cache/state behavior before it is real.
- Use deterministic local providers for tests and demos to avoid live AI dependencies in the core test suite.

## Known Limitations

- Text extraction supports only UTF-8 text and Markdown-like files.
- Embeddings, retrieval, query answering, SSE, and Redis-backed behavior are not implemented yet.
- Uploads are buffered through temporary local files before MinIO storage.
- `force=true` chunk replacement deletes and recreates stored chunks for a document, but no chunk-version history exists yet.
- Token counts are approximate whitespace counts.
- If object storage succeeds and metadata persistence fails, the service attempts best-effort MinIO cleanup. Orphaned MinIO objects can still occur if that cleanup fails.
- Production security, authentication, authorization, observability, and deployment hardening are not yet addressed.
- Performance benchmarking and scale claims are intentionally out of scope until the system has realistic workloads and measurements.

## Future Updates

Each milestone should update this document when it changes the architecture, data flow, storage model, or integration boundaries.
