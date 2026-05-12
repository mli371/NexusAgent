# NexusAgent Architecture

This document will evolve milestone by milestone. It records the architecture that exists, the architecture that is planned, and the trade-offs behind each decision.

## Purpose

NexusAgent is an Enterprise Knowledge Assistant Backend. It ingests enterprise documents, stores raw files and metadata, chunks text for retrieval, embeds child chunks, retrieves relevant context through hybrid search, and serves query responses with citations.

The system is designed as a maintainable backend project with clear boundaries, practical trade-offs, runnable local dependencies, and explicit limitations.

## Current Architecture

Milestones 1, 2, and 3 are implemented.

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

Redis is available in Docker Compose but is not used by the application through Milestone 3.

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

Milestone 3 adds child-chunk embeddings:

```text
POST /api/v1/documents/{id}/embed
  |
  v
ChildChunkEmbeddingService
  |
  +-- DocumentRepository -> verify document exists
  +-- ChunkRepository -> load parent/child chunks
  +-- ChildChunkEmbeddingRepository -> find already embedded child chunks
  +-- EmbeddingService
  |     +-- EmbeddingProvider interface
  |     +-- LocalDeterministicEmbeddingProvider by default
  |     +-- SpringAiEmbeddingProvider adapter boundary
  |
  +-- ChildChunkEmbeddingRepository -> PgVector child_chunk_embeddings
```

Embedding status API:

```text
GET /api/v1/documents/{id}/embedding-status
```

The repository layer also contains exact PgVector cosine search for child chunk embeddings. It is intentionally not exposed as a retrieval API yet; hybrid retrieval starts in Milestone 4.

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
- `child_chunk_embeddings` stores PgVector embeddings for child chunks only.
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
- Embed child chunks only so vector search remains focused; parent chunks are reserved for context expansion.
- Store embeddings in a separate table so provider/model metadata and vectors do not bloat the chunk metadata table.
- Add Redis only after the query API exists, so the project does not imply cache/state behavior before it is real.
- Use deterministic local providers for tests and demos to avoid live AI dependencies in the core test suite.

## Known Limitations

- Text extraction supports only UTF-8 text and Markdown-like files.
- The local deterministic embedding provider is not a production semantic model.
- Hybrid retrieval, query answering, SSE, and Redis-backed behavior are not implemented yet.
- Uploads are buffered through temporary local files before MinIO storage.
- `force=true` chunk replacement deletes and recreates stored chunks for a document, but no chunk-version history exists yet.
- Forced re-chunking cascades old embeddings through child chunk foreign keys, but there is no embedding job history yet.
- Token counts are approximate whitespace counts.
- If object storage succeeds and metadata persistence fails, the service attempts best-effort MinIO cleanup. Orphaned MinIO objects can still occur if that cleanup fails.
- Production security, authentication, authorization, observability, and deployment hardening are not yet addressed.
- Performance benchmarking and scale claims are intentionally out of scope until the system has realistic workloads and measurements.

## Future Updates

Each milestone should update this document when it changes the architecture, data flow, storage model, or integration boundaries.
