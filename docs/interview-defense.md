# Interview Defense

## Real Providers And Live Queries: Phases 1–3

The optional OpenAI embedding adapter uses nonblocking WebClient HTTP; it is not a Spring AI SDK integration. The Responses answer adapter is wired to an explicitly enabled LiveQueryService for Query REST/SSE. It reuses Java retrieval/context construction, not Pi tool dispatch. A two-scope learning frontend shows real stages, final retrieval/context data and child evidence within parent text.

**Why is dimension alone insufficient?** A local hash vector and an OpenAI vector may both have 384 components but represent different spaces. Coverage checks and SQL retrieval filter by provider/model/dimension; cache keys include that identity too.

**How can embeddings be replaced safely?** Generate new vectors outside the database transaction. Under a document row lock, recheck access and the immutable child snapshot, then commit the replacement atomically. Chunk replacement takes the same lock. Child IDs and offsets are unchanged; failures before commit preserve old vectors. There is no distributed transaction with the API provider or guarantee of zero billable usage after cancellation.

**How is streaming honest?** Stage observations originate at subscription/completion/failure boundaries in retrieval and context services. REST/SSE share one execution. Search branches run concurrently; the answer is emitted once only after validation. It is progress streaming, not token streaming or model reasoning.

**What do citations prove?** Java checks that the answer markers and declared markers agree and map to selected child chunks and expanded parents. Final citations are the used subset. This validates provenance structure, not whether each claim is entailed by the text.

**How do failures behave?** Input and document readiness fail before opening SSE. Provider/timeouts do not fall back to a local answer, and the paid answer call is not retried automatically. Empty evidence returns an explicit abstention without an answer-model call. Redis cache failures become misses; other Redis side effects are bounded and best-effort. Cached evidence is versioned in PostgreSQL and reauthorized, not trusted merely because a key exists.

**Does the user need to choose citations?** No. Default library scope resolves all accessible, current-model-ready documents, then ranks child chunks and derives citations from selected evidence. Optional documents scope constrains the corpus. Tenant and PRIVATE-owner filtering happen before coverage counts/search, not after topK. The learning limit is 200 accessible documents, rejected explicitly on overflow; selected scope supports 1–10.

**Why not automatically reconnect QA like Pi tasks?** Pi tasks have persisted runs/events and a replay API. QA has a single ephemeral POST stream. Repeating it can repeat paid inference, so the browser stops with an uncertain-result message. It validates trace/sequence and waits for completed before displaying the answer. Identity changes abort old subscriptions and clear page data.

**What remains incomplete?** Broad paid-provider acceptance and retrieval-quality evaluation are separate work. Automated tests simulate provider responses while exercising real PostgreSQL/PgVector SQL; browser tests use synthetic HTTP. A separately authorized, bounded live check reproduced a citation mismatch and then verified one successful real browser/SSE answer after the fix. That supports a narrow live smoke-test claim, not a semantic-quality benchmark, model-reliability guarantee, production authentication, cross-encoder, or autonomous agentic retrieval. See [validation scope](learning/rag-answer-citation-validation-fix.md).

## 60-Second Pitch

NexusAgent is a Java 17 Spring Boot WebFlux backend for enterprise-aware knowledge retrieval. It stores raw documents in MinIO and metadata/chunks in PostgreSQL, embeds child chunks into PgVector, combines vector and full-text search with RRF, reranks heuristically, and expands parent context for citation-aware answers. Offline mode uses deterministic providers; an opt-in OpenAI query pipeline adds model-readiness checks, access rechecks and genuine stage SSE. Redis stores short-lived state and versioned context, with identity-scoped keys and graceful failure. Cache hits still use current authorization and generate a new model answer. A separate Pi harness handles document diagnostics and human-approved processing. These are bounded backend workflows, not a production autonomous platform or a claim of evaluated answer quality.

## 5-Minute Deep Dive

The system is built as one Spring Boot service to keep the local project runnable and explainable.

Ingestion starts with `POST /api/v1/documents`. The raw file is stored in MinIO and the metadata is stored in PostgreSQL. That keeps large bytes out of the database while preserving a queryable source-of-truth record.

Chunking creates parent chunks and child chunks. Child chunks are smaller overlapping retrieval windows. Parent chunks are larger context blocks. This lets retrieval be precise without sending tiny isolated snippets to the answer stage.

Embedding runs only on child chunks. The local deterministic provider creates repeatable 384-dimensional vectors for tests and demos. PgVector stores the vectors in `child_chunk_embeddings`.

Retrieval runs two paths: vector search over embeddings and PostgreSQL full-text search over child chunk text. Their raw scores are not comparable, so RRF fuses rank positions rather than mixing distances and text scores.

Context construction reranks fused candidates, expands selected child chunks to parent chunks, deduplicates parent context, applies a character budget, and builds citations.

The default query API uses `LocalTemplateAnswerGenerator`. Explicit live mode instead uses `LiveQueryService` with the OpenAI Responses adapter, strict citation checks and no template fallback. Live SSE exposes actual execution progress; it does not stream model tokens.

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

## Optional Agent Harness Defense

The newer diagnostic run API is distinct from the deterministic Plan-Execute-Critique API. Java owns run state, tenant/owner checks, tool scope, idempotency, leases, and report acceptance. The TypeScript worker uses two read-only tools and a proposal tool in scripted or optional Pi mode. PostgreSQL stores durable observations and approvals; Redis is not authoritative for them.

Safe explanation: "I built a diagnostic workflow with scoped tools, persisted evidence and response replay. It pauses for actor-bound approval of a fixed ingestion action. Java checks state again, links the exact job at reservation time, and prevents duplicate dispatch. A fresh model session receives saved evidence afterward. Read-only lease recovery is bounded; uncertain writes are reconciled, not blindly retried. Cancellation waits for in-flight write outcomes, and SSE clients replay persisted events by sequence." Scripted tests cover approvals/execution and worker interruption; the earlier live GPT-5.6 Luna test covers read-only diagnostics. Do not claim general crash recovery, autonomous repairs, an OS sandbox, model quality or verified production identity. See [Phase 2 Q&A](learning/agent-harness-02-approval-retry.md#interview-defense) and [Phase 3 Chinese learning note with English interview answers](learning/agent-harness-03-recovery-events.md#interview-defense).

## Limitations And Production Improvements

### Learning Workbench Q&A

Q: Does the frontend execute the agent workflow?

A: No. It creates a durable task, reads scoped state and observations, and submits human decisions. Java owns approval policy and execution. The worker uses the existing internal protocol; the browser never receives its credentials.

Q: Why separate proposal, approval, and action nodes?

A: They prove different things. Proposal success means a request was saved, approval means a person allowed it, and action completion means the linked ingestion job produced a recorded outcome. A model statement or HTTP 200 is not proof that a write succeeded.

Q: What is real in the learning view?

A: Event order, run status, tool arguments/results and action records come from PostgreSQL-backed APIs. Source/function explanations are a separate static mapping, not runtime tracing. A scripted full-stack browser test exercised real upload, two approvals, persisted chunks/embeddings and report restoration without a model call.

The project is not production ready. It lacks production authentication, complete tenant isolation guarantees, evaluated live LLM answer quality, real semantic embeddings by default, trained cross-encoder reranking, production observability, and benchmarks. Optional live adapters and a working client are not evidence of those guarantees.

Production improvements would include real providers behind existing interfaces, document access control, cache invalidation hooks, metrics/tracing, workflow audit tables, and stronger security controls.
