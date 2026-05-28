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

Status: Implemented.

What was built:

- PgVector semantic retrieval over embedded child chunks
- PostgreSQL full-text retrieval over `child_chunks.text`
- Candidate models for vector, full-text, and fused results
- RRF fusion with default `k=60`
- Deduplication by `child_chunk_id`
- Retrieval debug API at `POST /api/v1/retrieval/debug`
- Tests for vector retrieval, full-text retrieval, RRF scoring, deduplication, source tracking, and API routing

How to explain it:

> I added a hybrid retrieval stage that runs two independent searches over child chunks: semantic vector search for meaning-based matches and PostgreSQL full-text search for keyword matches. Those result lists are not scored on the same scale, so I do not compare raw distances to raw keyword scores. Instead, I fuse the ranked lists with Reciprocal Rank Fusion and preserve debug metadata so retrieval behavior can be inspected.

Design defense:

- Semantic retrieval uses the query embedding and PgVector cosine distance against `child_chunk_embeddings`.
- Full-text retrieval uses PostgreSQL `websearch_to_tsquery` and `ts_rank_cd` over child chunk text.
- RRF uses rank positions only: `sum(1 / (k + rank))`.
- `child_chunk_id` is the deduplication key because embeddings, retrieval hits, citations, and cache entries all attach to child chunks.
- Source tracking reports `vector`, `full_text`, or `both`, which makes the debug API useful when tuning retrieval behavior.
- `parent_chunk_id` is preserved for the next milestone, where precise child hits will expand into parent context.

Failure behavior:

- Blank queries return a bad-request error.
- `topK` must be positive and is capped by configuration.
- Missing embeddings simply mean semantic retrieval has fewer or no candidates.
- Missing keyword matches simply mean full-text retrieval has fewer or no candidates.
- The debug API returns candidate lists; it does not pretend to answer questions.

Known limitations:

- Retrieval is exposed only through a debug endpoint.
- There is no reranking, context construction, answer generation, SSE, or Redis cache yet.
- The local deterministic embedding provider is not a production semantic model.
- PostgreSQL full-text search uses the English configuration for now.
- RRF does not calibrate raw scores; it intentionally fuses rank positions only.

Production changes:

- Add query preprocessing and language-aware full-text configuration.
- Add reranking and context construction before answer generation.
- Track retrieval metrics, latency, candidate counts, and no-result cases.
- Tune PgVector and full-text indexes with realistic document volume.

### Milestone 5: Reranking + Context Construction

Status: Implemented.

What was built:

- `Reranker` interface
- `DeterministicHeuristicReranker`
- `ParentContextExpansionService`
- `ContextBuilder`
- `CitationFormatter`
- Context debug API at `POST /api/v1/context/debug`
- Citation-aware context output with selected child chunks and expanded parent chunks
- Tests for reranking, source boost, parent diversity, parent expansion, parent deduplication, budget trimming, citation formatting, and API routing

How to explain it:

> I added the stage after hybrid retrieval that prepares evidence for later answer generation. The system takes fused child chunk candidates, reranks them with a deterministic heuristic, expands selected child hits to parent chunks, deduplicates repeated parent contexts, applies a character budget, and emits citation metadata. This milestone still does not call a language model or claim final answering exists.

Design defense:

- Reranking is behind an interface so the heuristic can be replaced by a cross-encoder or external rerank API later.
- The default reranker is deterministic and testable. It uses RRF score, keyword overlap, source signal, and diversity penalties.
- Child chunks remain retrieval units because they are precise.
- Parent chunks are context units because they provide enough surrounding text for later answer generation.
- `parent_chunk_id` connects selected child hits to expanded parent context.
- Context construction applies a character budget to avoid forwarding unbounded raw chunks.
- Citations preserve `document_id`, filename, parent chunk ID, child chunk ID, chunk index, character offsets, and preview text.

Failure behavior:

- Invalid `contextBudgetChars` returns a bad-request error.
- Empty retrieval results produce empty reranked, selected, context, and citation lists.
- Duplicate parent chunks are skipped during context selection.
- Over-budget parent text is trimmed and marked as truncated.

Known limitations:

- The reranker is a deterministic heuristic, not a trained model.
- The budget is character-based, not model-token-based.
- Section/title metadata is not extracted yet, so citation section title is currently unavailable.
- The API builds context only; it does not generate final answers, stream responses, use Redis, or call a language model.

Production changes:

- Replace or supplement the heuristic with a real reranker.
- Use model-aware token budgeting.
- Add section/title extraction during ingestion.
- Add retrieval/context observability and evaluation data.
- Add access control before using context construction with private enterprise documents.

### Milestone 6: Query API + SSE, With Placeholder State/Cache Interfaces

Status: Implemented.

What was built:

- Query REST API at `POST /api/v1/query`
- SSE query stream API at `POST /api/v1/query/stream`
- `QueryOrchestrationService` to compose context construction and answer generation
- `AnswerGenerator` interface
- `LocalTemplateAnswerGenerator`
- `SessionStateService` with an in-memory implementation
- `RetrievalCacheService` with a no-op implementation
- `ToolOutputStore` with a no-op implementation
- Tests for query routes, SSE event flow, local answer generation, placeholder services, debug visibility, and timeout fallback

How to explain it:

> I added the user-facing query layer on top of the retrieval and context pipeline. A query request now builds citation-aware context, passes it to a local template answer generator, and returns an answer with citations and a trace ID. The SSE endpoint exposes the same flow as observable stage events. Redis-backed state and cache are intentionally only represented by interfaces here; the real Redis implementations arrive in the next milestone.

Design defense:

- `QueryOrchestrationService` keeps the controller thin and makes the request lifecycle testable.
- The orchestration composes existing retrieval, reranking, parent expansion, and citation formatting through `ContextBuilder`.
- `AnswerGenerator` is an integration boundary so a real answer provider can be plugged in later without changing the query API.
- `LocalTemplateAnswerGenerator` is deliberately named as a local placeholder and states that no external model was called.
- Debug fields are hidden unless `debug=true`, keeping the default response focused on answer, citations, and trace ID.
- `SessionStateService`, `RetrievalCacheService`, and `ToolOutputStore` are present as boundaries but use in-memory/no-op implementations in this milestone.
- The SSE API emits coarse stage events so clients can show progress before real token streaming exists.
- The SSE API is `POST`-based because it accepts a JSON request body; it works with `curl`, `fetch`-style clients, and server-side HTTP clients, while browser `EventSource` normally expects `GET`.

Failure behavior:

- Blank questions return a bad-request error.
- `topK < 1` and `contextBudgetChars < 1` return bad-request errors.
- Context construction has a timeout and retry; a timeout returns an empty local fallback context.
- Empty or timed-out context produces an explicit insufficient-context answer and no fabricated citations.
- Answer generation has a timeout; a timeout returns a local timeout answer.
- Streaming errors emit an `error` SSE event and record a failed session event when a session ID is present.

Known limitations:

- The answer is generated by a local template, not a production LLM.
- SSE emits stage events and a final message; it does not stream model tokens.
- `NoOpRetrievalCacheService` does not cache anything.
- `NoOpToolOutputStore` does not persist intermediate outputs.
- `InMemorySessionStateService` is process-local and not durable.
- Redis is still not used by the application in Milestone 6.
- The Plan-Execute-Critique workflow is added later in Milestone 8.

Next changes:

- Redis-backed session, cache, and tool-output implementations are added in Milestone 7.
- Add a real answer provider behind `AnswerGenerator`.
- Add stronger cancellation, tracing, metrics, and timeout policies.
- Add authentication, authorization, and tenant-aware filtering before exposing private enterprise documents.

### Milestone 7: Redis State + Cache Integration

Status: Implemented.

What was built:

- `RedisSessionStateService`
- `RedisRetrievalCacheService`
- `RedisToolOutputStore`
- `NexusRedisProperties`
- `RedisKeyFactory`
- Redis-backed JSON envelopes with `schemaVersion`
- Property-based selection between Redis-backed services and local fallback services
- Tests for key format, TTL defaults, serialization, cache hit/miss, session write/read, tool output write/read, oversized value skips, and Redis-down fallback

How to explain it:

> I added Redis as a short-lived state and cache layer behind the interfaces introduced in Milestone 6. Redis now stores recent session events, session summaries, query status, retrieval context cache entries, and temporary tool outputs. PostgreSQL and MinIO remain the source of truth. Redis is allowed to be unavailable; query execution degrades to cache misses and no-op state writes rather than failing the whole request.

Design defense:

- Redis implementations keep the existing `SessionStateService`, `RetrievalCacheService`, and `ToolOutputStore` interfaces stable.
- Redis is selected with configuration, while in-memory/no-op implementations remain available for local fallback and tests.
- Redis keys are explicit: `session:{sessionId}:recent`, `session:{sessionId}:summary`, `retrieval:{queryHash}:candidates`, `tool:{sessionId}:{toolCallId}:result`, and `query:{traceId}:status`.
- Retrieval cache keys include normalized query text, sorted document IDs, effective `topK`, effective context budget, and retrieval/context settings that affect output.
- Redis values are JSON envelopes with `schemaVersion`, which gives a path for future serialization changes.
- TTLs differ by data type because session recency, summaries, retrieval cache entries, tool outputs, and query statuses have different useful lifetimes.
- Value sizes are bounded so Redis is not used as a raw document store.
- Query responses expose `retrievalCacheStatus` only when `debug=true`, so cache hit/miss behavior is inspectable without changing the public response shape.

Failure behavior:

- Redis cache read failure returns a cache miss.
- Redis cache write failure is logged and ignored.
- Redis session or query-status write failure is logged and ignored.
- Redis tool-output read failure returns empty.
- Redis tool-output write failure is logged and ignored.
- Oversized retrieval cache and tool output values are skipped.

Known limitations:

- Retrieval cache invalidation after `force=true` re-chunking is TTL-based only.
- There are no Redis hit/miss metrics yet.
- Session state is still recent lifecycle state, not durable conversation memory.
- Tool outputs are temporary and bounded, not durable workflow history.
- Redis does not store raw uploaded documents.
- The Plan-Execute-Critique workflow is added later in Milestone 8.

Production changes:

- Add explicit cache invalidation from chunking and embedding lifecycle events.
- Add Redis health metrics, cache hit/miss counters, and alerting.
- Add tenant-aware key prefixes before multi-tenant use.
- Add encryption or stricter redaction if sensitive intermediate outputs are cached.
- Tune TTLs and size limits from observed workload behavior.

### Milestone 8: Plan-Execute-Critique Workflow

Status: Implemented.

What was built:

- `AgentOrchestrator`
- Structured workflow domain objects: `Plan`, `PlanStep`, `PlanAction`, `ExecutionResult`, `CritiqueResult`, and `AgentWorkflowStatus`
- Agent query API at `POST /api/v1/agent/query`
- Deterministic rule-based planning
- Execution through the existing query pipeline when retrieval is needed
- Direct local response and insufficient-context fallback paths
- Deterministic critique checks for retrieved context and citations
- Tool-output storage for plan, execution, and critique outputs
- Tests for planning, execution, critique, fallback behavior, debug visibility, tool-output writes, and API routing

How to explain it:

> I added a minimal Plan-Execute-Critique workflow around the existing query pipeline. The planner is deterministic and chooses between retrieval-backed answering, direct local response, or explicit fallback. The executor reuses the existing query orchestration for retrieval-backed answers. The critique step checks whether retrieved answers have citations and whether fallback was used. This is not a multi-agent platform and it does not call a production LLM.

Design defense:

- The workflow is intentionally thin and deterministic so it can be explained and tested.
- `AgentOrchestrator` reuses `QueryOrchestrationService` instead of duplicating retrieval, context construction, answer generation, Redis cache, and session behavior.
- Plan actions are explicit: `RETRIEVE_CONTEXT`, `GENERATE_ANSWER`, and `FALLBACK_INSUFFICIENT_CONTEXT`.
- `ExecutionResult` records what actually ran, whether retrieval was used, whether fallback was used, cache status when available, citation count, and notes.
- `CritiqueResult` is deterministic. It checks insufficient context, missing citations, and whether the answer references a citation marker that exists in the returned citation list.
- Workflow internals are hidden by default and exposed only with `debug=true`.
- Intermediate plan, execution, and critique summaries go through `ToolOutputStore`, so Redis TTL and value-size limits apply when Redis is enabled.

Failure behavior:

- Blank questions return a bad-request error.
- `topK < 1` and `contextBudgetChars < 1` return bad-request errors.
- Low-information questions return an explicit insufficient-context fallback instead of pretending to answer.
- Retrieval-backed answers with no context are marked `MISSING_CONTEXT`.
- Retrieval-backed answers with missing citations are marked `MISSING_CITATIONS`.
- Tool-output write failures are logged and do not fail the query.

Known limitations:

- The planner is rule-based, not model-generated.
- The critique step is not a learned judge and does not verify factual correctness.
- There is no multi-step tool use beyond the existing query pipeline.
- There is no autonomous background execution.
- There is no production LLM call.
- There is no agent SSE endpoint yet.
- This is not production agent infrastructure.

Production changes:

- Add richer planning policies after the basic workflow has evaluation coverage.
- Add stronger grounding checks if a real answer generator is introduced.
- Add explicit workflow metrics and audit history.
- Add tenant-aware authorization before exposing workflow APIs over private documents.
- Add cancellation and resumability if workflows become longer-running.

### Milestone 9: Hardening + Documentation Polish

Status: Implemented.

What was built:

- Final README polish with an architecture diagram and full local setup path.
- Schema, API, demo, troubleshooting, limitations, interview-defense, and resume-claims docs.
- Demo helper script and Makefile targets for the end-to-end local flow.
- Example documents under `examples/`.
- A final claim-hygiene pass so deterministic and simplified components are clearly labeled.

How to explain it:

> I closed the MVP by making the repository easy to run and honest to defend. Milestone 9 does not add a major feature; it verifies that the code, docs, demo commands, limitations, and resume wording all describe the same implemented system.

Design defense:

- The demo flow exercises upload, chunking, embedding, retrieval debug, context debug, query, SSE, agent query, and Redis inspection.
- The docs separate implemented behavior from future improvements.
- Resume claims are limited to capabilities backed by code.
- Simplified components remain explicitly named, including local deterministic embeddings, heuristic reranking, local answer generation, and deterministic Plan-Execute-Critique.

Known limitations:

- The project remains a local MVP and is not production ready.
- No production performance numbers are claimed.
- No real production LLM answer quality, cross-encoder reranking, or autonomous platform behavior is claimed.

### Milestone 10: Enterprise Readiness Slice

Status: Implemented.

What was built:

- Header-based `RequestContext` using `X-Tenant-Id`, `X-Actor-Id`, and `X-Trace-Id`.
- `tenant_id`, `owner_id`, and `visibility` fields on `documents`.
- Tenant-aware document, retrieval, context, query, and agent flows.
- `audit_events` table and audit writes for upload, chunk, force re-chunk, embed, query, and agent query.
- `ingestion_jobs` table and synchronous job status rows for chunk/embed endpoints.
- Safe Actuator health/info exposure and structured lifecycle logs.

How to explain it:

> I added a narrow enterprise-readiness slice: request context, tenant filtering, audit records, ingestion status rows, and basic health/info visibility. I did not claim production enterprise security because real security requires verified authentication and authorization, not trusted headers.

Design defense:

- Tenant filtering happens at the repository layer for document access and retrieval candidates.
- Retrieval cache keys include tenant and actor context to avoid cross-context cache reuse.
- Audit metadata avoids raw document text and full context.
- Ingestion jobs are synchronous records that create a path toward future async workers.
- Actuator exposure is intentionally limited to health/info.

Known limitations:

- Header-based tenant/actor context is not authentication.
- There is no full RBAC/ABAC.
- There is no production tenant isolation guarantee yet.
- Ingestion jobs are not an async worker queue.
- Observability is basic and not a full telemetry stack.

## Trade-Off Language

Use specific, honest language:

- "This is deterministic for local testing, not a production embedding model."
- "Redis is used as a cache/state store, not the source of truth."
- "The interface existed before Redis so the query orchestration did not depend directly on Redis APIs."
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
