# Limitations And Future Improvements

NexusAgent is an interview-ready local MVP, not a production system.

## Current Limitations

- Text extraction supports UTF-8 plain text and Markdown-like files only.
- PDF, Word, HTML, image OCR, and archive extraction are not implemented.
- Chunk sizing is character-based with approximate whitespace token counts.
- Local deterministic embeddings are for demos and tests, not semantic production embeddings.
- Embedding dimension is fixed at `384` in the current schema.
- PgVector HNSW index creation is conditional; exact vector search remains the fallback.
- PostgreSQL full-text search uses English configuration only.
- RRF fuses rank positions and does not calibrate raw vector distance against full-text score.
- The reranker is deterministic and heuristic, not a trained cross-encoder.
- Context budget is character-based, not model-token-based.
- `LocalTemplateAnswerGenerator` is a local placeholder. It does not provide production answer quality.
- SSE emits workflow stage events and a final message. It does not stream generated model tokens.
- Redis stores short-lived state/cache only. It is not durable memory.
- Retrieval cache invalidation after force re-chunking is TTL-based only.
- The Plan-Execute-Critique workflow is deterministic and rule-based.
- The critique step checks citation presence and marker consistency, not factual correctness.
- There is no production authentication, authorization policy engine, complete tenant isolation guarantee, or document-level access control beyond the simple tenant/owner visibility skeleton.
- There is no malware scanning or data-loss-prevention policy.
- There is no production observability stack.
- There are no performance benchmarks or scale claims.
- Docker Compose is for local development only.
- Tenant and actor context is header-based and trust-on-input.
- There is no production authentication, OAuth2/JWT verification, RBAC, or ABAC.
- Tenant-aware filtering is an enterprise-readiness skeleton, not a complete tenant isolation guarantee.
- Missing tenant/actor headers fall back to `default` and `anonymous` for local demos only; production should require verified identity from OAuth/JWT claims, an API gateway, or another trusted layer.
- Audit events intentionally store small metadata only and are not compliance-grade audit infrastructure.
- Ingestion jobs are synchronous status records, not a background queue or worker system.
- Actuator is limited to health/info and is not a full observability stack.

## Future Improvements

- Add PDF and Office document extraction behind `DocumentTextExtractor`.
- Add model-aware token counting.
- Add a real Spring AI embedding provider behind `EmbeddingProvider`.
- Add embedding job tracking, retries, and provider version history.
- Add explicit cache invalidation when chunks or embeddings are regenerated.
- Add a real reranker behind `Reranker`.
- Add a production answer provider behind `AnswerGenerator`.
- Add model-token streaming if a real streaming answer provider is introduced.
- Add tenant-aware auth and per-document access checks.
- Add structured tracing, metrics, and dashboards.
- Add workflow audit records if agent workflows become durable business events.
- Add data security controls before using private enterprise documents.
- Replace tenant/actor headers with verified identity claims.
- Add RBAC/ABAC and document-level ACL enforcement.
- Add async ingestion workers and retryable job execution.
- Add audit retention, export, and compliance review tooling.
- Add metrics, tracing, dashboards, and alerting.

## Claims To Avoid

- Production ready.
- Benchmarked at scale.
- Live model answer quality.
- Trained cross-encoder reranking.
- Autonomous agent platform.
- Durable conversation memory.
- Production-grade enterprise security.
