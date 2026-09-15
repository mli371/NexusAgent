# Resume Claims

Use claims that match the code. Avoid wording that implies production capabilities not implemented here.

## Supported Claims

Optional extension, separate from the frozen MVP: "Built a diagnostic harness with PostgreSQL-backed runs, scoped tools, invocation replay and lease/deadline fencing; added actor-bound approvals for bounded ingestion actions with stale-state checks, exact job linkage and fresh-session continuation. Added bounded worker recovery, conservative uncertain-write reconciliation, cancellation coordination and sequence-based SSE replay." Scripted integration tests cover approval/execution and worker process interruption; the earlier live OpenAI GPT-5.6 Luna smoke covers read-only diagnostics. Neither is a quality benchmark. Do not claim autonomous repairs, general crash recovery, production authorization, exactly-once distributed writes, guaranteed rollback on cancellation, or replacement of the template answer generator.

- Built a Java 17 Spring Boot WebFlux backend for an enterprise knowledge assistant.
- Built a Java/Spring WebFlux backend for enterprise-aware document retrieval and citation-aware question answering workflows.
- Implemented raw document upload with MinIO object storage and PostgreSQL metadata.
- Implemented Flyway migrations for document, chunk, embedding, and full-text search schema.
- Designed parent-child chunking where child chunks are retrieval units and parent chunks are context-expansion units.
- Implemented parent-child RAG ingestion with MinIO raw-file storage, PostgreSQL metadata, child-only PgVector embeddings, and parent-context expansion.
- Added deterministic local embedding provider and PgVector storage for child chunk embeddings.
- Implemented hybrid retrieval with PgVector semantic search and PostgreSQL full-text search.
- Implemented Reciprocal Rank Fusion over vector and keyword candidate rankings.
- Implemented hybrid retrieval with PgVector semantic search, PostgreSQL full-text search, RRF, heuristic reranking, and citation-aware context construction.
- Built deterministic reranking and citation-aware context construction.
- Exposed REST and SSE query APIs using WebFlux/Reactor.
- Integrated Redis for short-lived session state, retrieval cache, query status, and temporary tool outputs.
- Added Redis-backed short-lived session state, retrieval/context cache, query status, and temporary tool-output storage with TTLs and graceful fallback.
- Added a deterministic Plan-Execute-Critique workflow around the query pipeline.
- Added an enterprise-readiness slice with header-based tenant context, tenant-aware retrieval filtering, audit events, ingestion job status rows, trace propagation, and Actuator health/info.
- Added enterprise-readiness slice with tenant-aware request context, repository-level tenant filtering, tenant-scoped Redis cache keys, audit events, ingestion job tracking, and trace-friendly observability.
- Wrote unit and integration-style tests covering ingestion, chunking, embeddings, retrieval, context building, query orchestration, Redis behavior, and workflow decisions.
- Added versioned, tenant/actor-scoped Redis context caching to opt-in live RAG, with transactional PostgreSQL revision invalidation, evidence/access rechecks, bounded values and failure fallback. Final model answers are not cached.
- Configured GitHub Actions for backend/worker and frontend test/build checks, including isolated database tests and synthetic browser tests; no automatic deployment or performance claims.

## Safe Resume Wording

```text
Built NexusAgent, a Java/Spring WebFlux backend for enterprise document retrieval and citation-aware question answering workflows, using PostgreSQL/PgVector, Redis, MinIO, Flyway, and Docker Compose.
```

```text
Implemented parent-child chunking, child-only embedding storage in PgVector, hybrid retrieval with PostgreSQL full-text search, RRF fusion, deterministic reranking, and citation-aware context construction.
```

```text
Designed Redis-backed short-lived session/cache/tool-output services with TTLs, bounded values, and fallback behavior when Redis is unavailable.
```

```text
Added a deterministic Plan-Execute-Critique orchestration layer that reuses the existing retrieval/query pipeline and validates citation-grounding signals.
```

```text
Added enterprise-readiness skeletons for tenant-aware filtering, audit events, ingestion status tracking, and traceable request handling, with clear documentation of production security gaps.
```

## Claims To Avoid

- "Ready for production deployment."
- "Autonomous multi-agent system."
- "The offline/default template generator is a real LLM" or "live answers have evaluated production quality." Opt-in real OpenAI answer generation is implemented, but that is separate from quality evaluation.
- "Cross-encoder reranking."
- "Benchmarked high-scale vector search."
- "Kubernetes-native deployment."
- "Durable enterprise memory."
- "Secure multi-tenant document access."
- "Production-grade tenant isolation."
- "Compliance-grade audit logging."
- "Async ingestion worker platform."

## Why Those Claims Are Unsafe

The project does not include production authentication, authorization, full observability, live LLM answer generation, live embedding provider configuration, real reranker model inference, Kubernetes manifests, compliance-grade audit infrastructure, async workers, or performance benchmarks.

The right framing is: working local MVP, strong architecture, honest boundaries, and clear production improvement path.
