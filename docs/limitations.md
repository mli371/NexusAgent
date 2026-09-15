# Limitations And Future Improvements

NexusAgent is an interview-ready local MVP, not a production system.

## Current Limitations

Real RAG Phases 1–3 add optional OpenAI embeddings, an opt-in live Responses answer pipeline and a two-scope learning frontend. Default backend Query mode and the old `/agent/query` example remain local/template-based; the new QA view refuses that mode. Capabilities describe configuration, not verified external API access or document readiness. Automated validation uses mock providers/local HTTP, synthetic browser responses and real isolated PostgreSQL. A later [citation-fix smoke check](learning/rag-answer-citation-validation-fix.md) used two explicitly authorized real queries: one reproduced a mismatch and one passed after the fix. This does not establish retrieval quality, broad model reliability or future account availability. Existing documents are not automatically migrated.

Vector replacement requires explicit `replaceExisting=true`, supports one active 384-dimensional model per child, and is bounded to 256 children/1,000,000 characters. Ordinary fill-missing operations can remain partially complete on failure; replacement commits are atomic but remote API usage and job/audit bookkeeping are not part of that transaction. Crashes/cancellations can leave job state requiring investigation. Header-based access is not production auth.

Live context cache keys include identity, embedding model, settings, resolved document IDs and PostgreSQL revisions. V10 transactionally advances revisions for chunk/vector/access changes, including same-model re-embedding. Hits still recheck authorization/evidence and call the answer model. Redis failures rebuild context; old entries remain until TTL and are not a deletion guarantee. The separate old offline mode still has TTL-only invalidation. Row-level revision triggers add write amplification; concurrent misses may duplicate work, with no distributed single-flight. No final-answer cache or cross-request conversation memory was added. Citation validation checks reference consistency, not claim entailment. No token streaming or semantic evaluation benchmark was added. See [current cache design](learning/live-context-cache-ci.md) and historical [Phase 2 review](review/rag-phase-2.md), including unresolved Netty warnings observed during tests.

Live QA is single-turn and read-only. Selected-document scope is limited to ten documents; default library scope supports at most 200 accessible documents and explicitly refuses overflow. Unready documents are excluded with reason counts, not automatically repaired. Scope is a request snapshot; newly ready documents join later requests and change the cache key. There is no persisted answer/run recovery, automatic answer retry or conversational memory. Stage summaries omit evidence; authorized evidence is sent only with the final debug result. Cached context contains bounded source excerpts, so Redis requires protection too; hashing keys does not encrypt values. Disconnects can leave scoped short-lived query status at started until TTL; cancellation cannot promise reversal of provider usage. Retrieval and final evidence checks can load all chunks of selected documents, which is simple but not optimized for large documents. Rechecks are not a serializable authorization snapshot across a remote call. In-memory fallback session storage retains its existing demo-only, non-production retention behavior. Redis audit/state writes are best-effort and not a durable delivery guarantee. `store:false` is not a zero-retention guarantee.

The QA frontend retains up to 12 turns only in page memory and clears on view/identity changes. It sends single-turn requests, not chat history. Answers render as plain text with known citation buttons, not rich Markdown/HTML. It displays actual stage events and final debug fields, not every Java method argument or hidden model reasoning. POST streaming has no automatic reconnect/repost; a lost connection is an unknown result. Automated browser tests use synthetic HTTP responses; the separately approved live smoke check is not part of the repeatable test suite. No browser key storage or login was added. Citation normalization handles only explicit, known comma-separated groups, not arbitrary model formatting; inconsistent or incomplete answers still fail safely. Reference consistency does not prove each claim is supported by its cited text.

The optional [Agent Harness](agent-harness.md) adds asynchronous run orchestration; each approved ingestion operation remains synchronous. Harness Phase 2 permits only human-approved ordinary CHUNK and EMBED_MISSING. Harness Phase 3 adds limited read-only lease recovery, conservative exact-job reconciliation, cancellation-pending semantics and polling-based SSE replay. Uncertain writes never automatically redispatch. Maintenance is lazy; an unresolved RUNNING job can require operator investigation indefinitely. There is no general recovery/admin platform, verified identity, queue admission limit or automatic retention. One RUNNING run globally does not fence old external I/O still finishing under RECOVERY_REQUIRED. The earlier live OpenAI `gpt-5.6-luna` smoke covered read-only Harness Phase 1, not Harness Phase 2/3 model-driven acceptance or live QA quality evaluation. Scripted success is not evidence of live tool-calling quality.

Approvals are not production authentication. The application trusts caller-supplied
tenant/actor headers. Fingerprints detect changed metadata, chunk IDs, jobs and
embedding coverage; they do not rehash MinIO bytes or serialize every legacy public
ingestion route. Concurrent manual ingestion/access changes remain a limitation.
Partial writes can survive errors or disconnects. UNKNOWN outcomes block replay
and require investigation. Saved explanations are not semantically verified.
There is no global transaction or exactly-once guarantee across infrastructure failures.

New run endpoints require explicit tenant/actor headers, unlike the older APIs' local-demo defaults. Internal worker routes share the same listener and need trusted-network restriction; the worker shared secret is cross-tenant infrastructure authority. Questions and reports may contain sensitive input even though tools omit raw documents. Report validation checks saved provenance/condition labels, not natural-language truth. Lease cleanup is lazy on reads/claims. See the guide for exact boundaries.

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

### Learning Workbench

- The workbench shows durable diagnostic tasks, not multi-turn RAG chat or a production assistant UI.
- Demo headers are not login; existing tenant/actor visibility checks assume trustworthy identity input.
- Tool details are size-bounded allowlisted projections, not a general-purpose sensitive-content filter. Allowed question/reason/report strings can still contain user-supplied information.
- Source explanations are a static mapping, not runtime method instrumentation or hidden model reasoning.
- Reconnection does not mean exactly-once event delivery. The client deduplicates by run/sequence, replays missing history and confirms terminal state.
- Upload response loss can leave a successful upload whose identity the browser did not receive. No automatic upload retry is attempted.
- Scripted browser-to-backend tests passed; this does not establish equivalent behavior for a live model, production permissions, production deployment or load.
- See [current UI bounds and startup](learning-workbench.md) for payload, history, browser and Node constraints.

### General

- Production ready.
- Benchmarked at scale.
- Live model answer quality.
- Trained cross-encoder reranking.
- Autonomous agent platform.
- Durable conversation memory.
- Production-grade enterprise security.
