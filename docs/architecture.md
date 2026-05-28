# NexusAgent Architecture

This document will evolve milestone by milestone. It records the architecture that exists, the architecture that is planned, and the trade-offs behind each decision.

## Purpose

NexusAgent is an Enterprise Knowledge Assistant Backend. It ingests enterprise documents, stores raw files and metadata, chunks text for retrieval, embeds child chunks, retrieves relevant context through hybrid search, and serves query responses with citations.

The system is designed as a maintainable backend project with clear boundaries, practical trade-offs, runnable local dependencies, and explicit limitations.

## Current Architecture

Milestones 1 through 10 are implemented.

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

Redis is used by the query layer for short-lived state and cache. It is not the source of truth.

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

Milestone 4 adds hybrid retrieval:

```text
POST /api/v1/retrieval/debug
  |
  v
RetrievalDebugController
  |
  v
HybridRetrievalService
  |
  +-- SemanticRetrievalService
  |     +-- EmbeddingService -> query embedding
  |     +-- VectorSearchRepository -> PgVector search over child_chunk_embeddings
  |
  +-- FullTextRetrievalService
  |     +-- FullTextSearchRepository -> PostgreSQL full-text search over child_chunks.text
  |
  +-- RrfFusionService
        +-- deduplicate by child_chunk_id
        +-- fuse vector and full-text rank positions
        +-- preserve debug metadata for each candidate
```

This is a retrieval debug API, not a query-answering API. It returns ranked candidates and fusion details so the retrieval stage can be inspected.

Milestone 5 adds reranking and context construction:

```text
POST /api/v1/context/debug
  |
  v
ContextDebugController
  |
  v
ContextBuilder
  |
  +-- HybridRetrievalService -> fused child chunk candidates
  +-- Reranker
  |     +-- DeterministicHeuristicReranker by default
  |
  +-- ParentContextExpansionService
  |     +-- ChunkRepository -> child and parent chunks
  |     +-- DocumentRepository -> original filenames
  |
  +-- CitationFormatter
        +-- citation markers
        +-- final context text
```

This is a context debug API, not a final query-answering API. It produces reranked candidates, selected child chunks, expanded parent contexts, citations, and final context text for inspection.

Milestone 6 adds the query API and SSE response flow:

```text
POST /api/v1/query
  |
  v
QueryController
  |
  v
QueryOrchestrationService
  |
  +-- SessionStateService -> InMemorySessionStateService
  +-- RetrievalCacheService -> NoOpRetrievalCacheService
  +-- ContextBuilder -> retrieval, reranking, parent expansion, citations
  +-- AnswerGenerator -> LocalTemplateAnswerGenerator
  +-- ToolOutputStore -> NoOpToolOutputStore
```

Streaming API:

```text
POST /api/v1/query/stream
  |
  +-- received
  +-- retrieving
  +-- reranking
  +-- building_context
  +-- generating
  +-- message
  +-- completed
```

Milestone 7 adds Redis-backed implementations behind the same query interfaces:

```text
QueryOrchestrationService
  |
  +-- SessionStateService -> RedisSessionStateService
  |     +-- session:{sessionId}:recent
  |     +-- session:{sessionId}:summary
  |     +-- query:{traceId}:status
  |
  +-- RetrievalCacheService -> RedisRetrievalCacheService
  |     +-- retrieval:{queryHash}:candidates
  |
  +-- ToolOutputStore -> RedisToolOutputStore
        +-- tool:{sessionId}:{toolCallId}:result
```

The Milestone 6 in-memory/no-op implementations remain available when Redis is disabled. Redis entries use JSON envelopes with `schemaVersion` values and configurable TTLs. Redis read failures degrade to cache misses, and Redis write failures are logged as no-op writes.

When query debug mode is enabled, the API includes `retrievalCacheStatus` with `hit` or `miss`. Normal non-debug query responses omit this field.

Milestone 8 adds a minimal Plan-Execute-Critique workflow over the query pipeline:

```text
POST /api/v1/agent/query
  |
  v
AgentQueryController
  |
  v
AgentOrchestrator
  |
  +-- Plan
  |     +-- deterministic rules choose one of:
  |         +-- RETRIEVE_CONTEXT
  |         +-- GENERATE_ANSWER
  |         +-- FALLBACK_INSUFFICIENT_CONTEXT
  |
  +-- Execute
  |     +-- existing QueryOrchestrationService for retrieval-backed answers
  |     +-- local deterministic direct/fallback responses for simple cases
  |     +-- ToolOutputStore for plan/execution/critique outputs
  |
  +-- Critique
        +-- checks retrieval usage
        +-- checks insufficient-context fallback
        +-- checks citation presence and answer citation markers that exist in the citation list
```

This is not a multi-agent platform. It is a thin deterministic workflow layer around the existing query pipeline.

Milestone 10 adds an enterprise-readiness slice:

```text
Incoming request
  |
  +-- X-Tenant-Id / X-Actor-Id -> RequestContext
  +-- X-Trace-Id -> query or agent trace when provided
  |
  +-- documents.tenant_id / owner_id / visibility
  +-- retrieval repositories join documents for tenant filtering
  +-- audit_events records upload/chunk/embed/query/agent lifecycle events
  +-- ingestion_jobs records synchronous chunk/embed status transitions
  +-- Actuator exposes health/info only
```

This is a skeleton, not production authentication or full authorization.

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
  |     +-- Vector search: implemented in Milestone 4
  |     +-- PostgreSQL full-text search: implemented in Milestone 4
  |     +-- RRF fusion: implemented in Milestone 4
  |     +-- Reranking: deterministic heuristic implemented in Milestone 5
  |     +-- Context construction with citations: implemented as debug API in Milestone 5
  |
  +-- Query workflow
        +-- Query API: implemented in Milestone 6
        +-- SSE response flow: implemented in Milestone 6
        +-- Session/cache/tool output interfaces: implemented in Milestone 6
        +-- Redis-backed implementations: implemented in Milestone 7
        +-- Plan-Execute-Critique workflow: implemented in Milestone 8
        +-- Enterprise-readiness skeleton: implemented in Milestone 10
```

## Storage Responsibilities

- MinIO stores raw uploaded files.
- PostgreSQL stores source-of-truth document metadata and chunk metadata.
- `parent_chunks` stores larger context blocks.
- `child_chunks` stores smaller windows with `parent_chunk_id` relationships.
- `child_chunk_embeddings` stores PgVector embeddings for child chunks only.
- `audit_events` stores bounded lifecycle audit metadata.
- `ingestion_jobs` stores synchronous chunk/embed job status.
- PostgreSQL full-text search indexes `child_chunks.text` for keyword retrieval.
- Redis stores short-lived session state, retrieval cache entries, query status values, and intermediate tool outputs.
- Redis values are bounded and TTL-based. Raw uploaded documents are not stored in Redis.
- Every Redis key written by the application has a positive TTL.

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
  context
  query
  agent
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
- Use RRF for hybrid retrieval because vector distances and full-text ranks are not directly comparable.
- Preserve vector rank, vector distance, full-text rank, full-text score, and RRF score in the debug API so retrieval behavior is inspectable.
- Keep reranking behind a `Reranker` interface so the deterministic heuristic can later be replaced by a cross-encoder or external rerank API.
- Use parent chunks for final context text, while keeping child chunks as the precise retrieval and citation unit.
- Apply a character budget in context construction so the API does not pass an unbounded set of raw chunks forward.
- Add Redis only after the query API exists, so the state/cache interfaces have real call sites before adding infrastructure.
- Treat Redis as an optimization and short-lived state store; cache failures must not break the query API.
- Keep answer generation behind `AnswerGenerator`; Milestone 6 uses a local template generator so the API can be exercised without external model credentials.
- Return query debug fields only when requested so normal API responses expose citations without leaking internal retrieval/context payloads by default.
- Keep the Plan-Execute-Critique workflow deterministic and thin; it should orchestrate the existing query pipeline, not introduce a new autonomous agent platform.
- Store workflow plan/execution/critique outputs through `ToolOutputStore` so Redis TTL and size-limit behavior applies when Redis is enabled.
- Use deterministic local providers for tests and demos to avoid live AI dependencies in the core test suite.

## Known Limitations

- Text extraction supports only UTF-8 text and Markdown-like files.
- The local deterministic embedding provider is not a production semantic model.
- Query answering uses `LocalTemplateAnswerGenerator`, which is a local placeholder and not a production LLM answer service.
- The agent workflow is a deterministic rule-based workflow, not a full autonomous or multi-agent system.
- The critique step validates citation presence and markers, not factual correctness with a learned judge.
- The workflow is not production agent infrastructure.
- SSE emits query stage events and a final message, but it does not stream tokens from a production LLM.
- Redis cache invalidation after forced re-chunking is TTL-based for now; there is no active invalidation hook yet.
- Redis failures degrade to misses/no-op writes, but cache health metrics are not implemented yet.
- The default reranker is heuristic and deterministic, not a trained cross-encoder.
- Full-text search currently uses PostgreSQL's English text search configuration.
- Uploads are buffered through temporary local files before MinIO storage.
- `force=true` chunk replacement deletes and recreates stored chunks for a document, but no chunk-version history exists yet.
- Forced re-chunking cascades old embeddings through child chunk foreign keys, but there is no embedding job history yet.
- Token counts are approximate whitespace counts.
- If object storage succeeds and metadata persistence fails, the service attempts best-effort MinIO cleanup. Orphaned MinIO objects can still occur if that cleanup fails.
- Production security, authentication, authorization, observability, and deployment hardening are not yet addressed.
- Performance benchmarking and scale claims are intentionally out of scope until the system has realistic workloads and measurements.

## Future Updates

Each milestone should update this document when it changes the architecture, data flow, storage model, or integration boundaries.
