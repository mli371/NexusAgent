# Interview Defense

## 60-Second Pitch

NexusAgent is a Java 17 Spring Boot WebFlux backend for enterprise knowledge retrieval. It ingests raw documents into MinIO, stores metadata and chunks in PostgreSQL, embeds child chunks into PgVector, combines vector semantic search with PostgreSQL full-text search, fuses candidates with RRF, reranks them, expands child hits into parent context, and returns citation-aware query responses. Redis is used for short-lived state, retrieval cache, query status, and temporary tool outputs. The project also includes a deterministic Plan-Execute-Critique workflow, but it deliberately avoids production LLM calls, fake multi-agent abstractions, and unsupported performance claims.

## 5-Minute Deep Dive

The system is built as one Spring Boot service to keep the local project runnable and explainable.

Ingestion starts with `POST /api/v1/documents`. The raw file is stored in MinIO and the metadata is stored in PostgreSQL. That keeps large bytes out of the database while preserving a queryable source-of-truth record.

Chunking creates parent chunks and child chunks. Child chunks are smaller overlapping retrieval windows. Parent chunks are larger context blocks. This lets retrieval be precise without sending tiny isolated snippets to the answer stage.

Embedding runs only on child chunks. The local deterministic provider creates repeatable 384-dimensional vectors for tests and demos. PgVector stores the vectors in `child_chunk_embeddings`.

Retrieval runs two paths: vector search over embeddings and PostgreSQL full-text search over child chunk text. Their raw scores are not comparable, so RRF fuses rank positions rather than mixing distances and text scores.

Context construction reranks fused candidates, expands selected child chunks to parent chunks, deduplicates parent context, applies a character budget, and builds citations.

The query API composes those stages and uses `LocalTemplateAnswerGenerator`. The answer generator is intentionally a local placeholder. SSE exposes progress events.

Redis is used after the query layer exists. It stores short-lived session events, retrieval cache entries, query status, and tool outputs. It is not the source of truth.

The Plan-Execute-Critique workflow wraps the query pipeline with deterministic rules. It is not an autonomous platform; it is a clean orchestration pattern.

## Architecture Explanation

```text
Upload -> MinIO raw file + PostgreSQL document metadata
Chunk -> parent_chunks + child_chunks
Embed -> child_chunk_embeddings using PgVector
Retrieve -> vector search + full-text search
Fuse -> RRF by rank position
Rerank -> deterministic heuristic
Context -> parent expansion + citations
Query -> local placeholder answer + SSE progress
Redis -> short-lived state/cache/tool outputs
Agent -> deterministic Plan-Execute-Critique wrapper
Enterprise slice -> tenant headers, audit events, ingestion jobs, health/info
```

## Parent-Child Chunking Q&A

Q: Why not just one chunk size?

A: Small chunks are better for precise retrieval, but they can lack context. Parent-child chunking separates retrieval precision from context quality.

Q: What do child chunks do?

A: Child chunks are embedded and searched. They are the precise retrieval units.

Q: What do parent chunks do?

A: Parent chunks provide surrounding context after a child chunk is selected.

Q: Are offsets global or parent-relative?

A: `char_start` and `char_end` are global offsets in extracted document text, with `char_end` exclusive.

## Embedding And PgVector Q&A

Q: Why embed only child chunks?

A: Child chunks are smaller and more precise. Parent chunks are larger context blocks and are not embedded in this MVP.

Q: Is the local embedding model real semantic AI?

A: No. It is deterministic test/demo infrastructure. The provider interface allows a real embedding provider later.

Q: Why use PgVector?

A: It keeps vector storage close to metadata for a local backend project and avoids introducing a separate vector database before it is needed.

## Hybrid Retrieval And RRF Q&A

Q: Why combine vector and full-text search?

A: Vector search handles semantic similarity. Full-text search handles exact keywords, identifiers, and domain terms.

Q: Why RRF?

A: Vector distances and full-text scores are not directly comparable. RRF fuses by rank positions.

Q: What is the formula?

A: `score = sum(1 / (k + rank_i))`, using 1-based ranks.

## Reranking And Context Q&A

Q: Is the reranker a cross-encoder?

A: No. It is a deterministic heuristic reranker using RRF score, keyword overlap, source signal, and diversity signals.

Q: Why not send top-30 chunks directly?

A: That can produce redundant, tiny, and noisy context. The context builder selects, expands, deduplicates, and budgets context.

Q: How are citations built?

A: Citations include document ID, filename, parent chunk ID, child chunk ID, chunk index, character offsets, and preview text.

## Redis Key Design Q&A

Q: What does Redis store?

A: Recent session events, session summaries, retrieval cache entries, query status, and temporary tool outputs.

Q: What does Redis not store?

A: Raw documents, durable metadata, chunks, embeddings as source of truth, or unbounded context.

Q: What are the key patterns?

A: `session:{sessionId}:recent`, `session:{sessionId}:summary`, `retrieval:{queryHash}:candidates`, `query:{traceId}:status`, and `tool:{sessionId}:{toolCallId}:result`.

## WebFlux And SSE Q&A

Q: Why WebFlux?

A: The pipeline is mostly I/O-bound: object storage, database calls, Redis, and HTTP streaming. Reactor composes those stages with timeout/fallback behavior.

Q: How are blocking calls handled?

A: MinIO and file hashing are isolated behind service boundaries and scheduled on `boundedElastic`.

Q: What does SSE stream?

A: Stage events such as received, retrieving, reranking, building context, generating, message, completed, and error.

## Plan-Execute-Critique Q&A

Q: Is this a multi-agent system?

A: No. It is a deterministic workflow wrapper over the query pipeline.

Q: What does the planner do?

A: It chooses retrieval-backed answer, direct local response, or fallback.

Q: What does critique check?

A: It checks insufficient context, missing citations, and citation marker consistency. It does not judge factual correctness.

## Enterprise Readiness Q&A

Q: What did the enterprise-readiness slice add?

A: Header-based tenant and actor context, tenant-aware document/retrieval filtering, document owner/visibility fields, audit events, synchronous ingestion job status rows, trace propagation, structured lifecycle logs, and safe Actuator health/info endpoints.

Q: Is header-based tenant context production security?

A: No. It is an interview-friendly skeleton. Production systems must derive tenant and actor from verified auth claims and enforce authorization policy server-side.

Q: How do you avoid cross-tenant retrieval?

A: Retrieval repositories join child chunks back to `documents` and filter by `tenant_id` before returning vector or full-text candidates. Document list/get, chunk, embed, context, query, and agent flows also receive the request context.

Q: What goes into audit metadata?

A: Small bounded metadata such as counts, status, visibility, provider name, and query hashes. Raw document text and full context are intentionally excluded.

Q: Are ingestion jobs async?

A: No. They are synchronous status records for the current APIs. They make lifecycle state visible and create a path toward async workers later.

## Limitations And Production Improvements

The project is not production ready. It lacks production authentication, complete tenant isolation guarantees, live LLM answer generation, real semantic embeddings by default, trained cross-encoder reranking, production observability, and benchmarks.

Production improvements would include real providers behind existing interfaces, document access control, cache invalidation hooks, metrics/tracing, workflow audit tables, and stronger security controls.
