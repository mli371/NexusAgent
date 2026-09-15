# Limitations And Future Improvements

NexusAgent is an interview-ready local MVP, not a production system.

## Current Limitations

Real RAG Phases 1–3 add optional OpenAI embeddings, an opt-in live Responses answer pipeline and a two-scope learning frontend. Default backend Query mode and the old `/agent/query` example remain local/template-based; the new QA view refuses that mode. Capabilities describe configuration, not verified external API access or document readiness. Automated validation uses mock providers/local HTTP, synthetic browser responses and real isolated PostgreSQL. A later [citation-fix smoke check](learning/rag-answer-citation-validation-fix.md) used two explicitly authorized real queries: one reproduced a mismatch and one passed after the fix. This does not establish retrieval quality, broad model reliability or future account availability. Existing documents are not automatically migrated.

Vector replacement requires explicit `replaceExisting=true`, supports one active 384-dimensional model per child, and is bounded to 256 children/1,000,000 characters. Ordinary fill-missing operations can remain partially complete on failure; replacement commits are atomic but remote API usage and job/audit bookkeeping are not part of that transaction. Crashes/cancellations can leave job state requiring investigation. Header-based access is not production auth.

Live context cache keys include identity, embedding model, settings, resolved document IDs and PostgreSQL revisions. V10 transactionally advances revisions for chunk/vector/access changes, including same-model re-embedding. Hits still recheck authorization/evidence and call the answer model. Redis failures rebuild context; old entries remain until TTL and are not a deletion guarantee. The separate old offline mode still has TTL-only invalidation. Row-level revision triggers add write amplification; concurrent misses may duplicate work, with no distributed single-flight. No final-answer cache or server-side conversation memory was added. Citation validation checks reference consistency, not claim entailment. No token streaming or semantic evaluation benchmark was added. See [current cache design](learning/live-context-cache-ci.md) and historical [Phase 2 review](review/rag-phase-2.md), including unresolved Netty warnings observed during tests.

Live QA is read-only and supports bounded page-local follow-ups. Selected-document scope is limited to ten documents; default library scope supports at most 200 accessible documents and explicitly refuses overflow. Unready documents are excluded with reason counts, not automatically repaired. Scope is a request snapshot; newly ready documents join later requests and change the cache key. There is no persisted answer/run recovery, automatic answer retry or durable conversational memory. Stage summaries omit evidence; authorized evidence is sent only with the final debug result. Cached context contains bounded source excerpts, so Redis requires protection too; hashing keys does not encrypt values. Disconnects can leave scoped short-lived query status at started until TTL; cancellation cannot promise reversal of provider usage. Retrieval and final evidence checks can load all chunks of selected documents, which is simple but not optimized for large documents. Rechecks are not a serializable authorization snapshot across a remote call. In-memory fallback session storage retains its existing demo-only, non-production retention behavior. Redis audit/state writes are best-effort and not a durable delivery guarantee. `store:false` is not a zero-retention guarantee.

The QA frontend retains up to 12 turns only in page memory and sends the latest three completed answered turns in the same scope, each with a question of at most 2000 characters and an answer excerpt of at most 1000. It clears on refresh, clear, view/identity/scope/document-set changes. Answers render as plain text with known citation buttons, not rich Markdown/HTML. It displays actual stage events and final debug fields, not every Java method argument or hidden model reasoning. POST streaming has no automatic reconnect/repost; a lost connection is an unknown result. Automated browser tests use synthetic HTTP responses; the separately approved live smoke check is not part of the repeatable test suite. No browser key storage or login was added. Citation normalization handles only explicit, known comma-separated groups, not arbitrary model formatting; inconsistent or incomplete answers still fail safely. Reference consistency does not prove each claim is supported by its cited text.

Follow-up resolution makes one additional paid model call for every history-bearing request, even an independent new topic or eventual exact cache hit. The 20-second default timeout and cancellation cannot cancel already incurred usage. Strict structured output validates shape, not reference-resolution accuracy; automated tests do not establish model quality. History is untrusted client input and may contain incorrect statements. It is not copied into final evidence, but it can still bias the resolved question. Three-turn limits and excerpt truncation lose context; ambiguous references and prior-answer editing may require a self-contained restatement. Clarifications, errors, refusals and insufficient-context turns are not eligible history. Historical answer excerpts are not signed server records and their original evidence permissions are not reconstructed on later requests. Current document access is checked, but this is not a revocation/deletion guarantee for already displayed user-held text. The system prompt is a behavioral constraint, not a prompt-injection security boundary. See [follow-up design and validation](learning/page-follow-up-resolution.md).

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
- Coverage-first allocation protects complete selected child spans, not exhaustive recall. Only the highest-ranked child per parent is reserved; other hits in the same parent are not independently guaranteed visible. Too-small budgets explicitly exclude children. Sharing remaining space can remove useful neighboring context; no sentence-aware trimming or multi-window parent expansion is implemented. UTF-16 body characters exclude citation headers and can underuse budget slightly at surrogate boundaries. Evidence inclusion does not guarantee the model will discuss every year or use every citation. The prompt distinguishes missing excerpts from missing library documents, but cannot guarantee that distinction in every generated answer.
- `LocalTemplateAnswerGenerator` is a local placeholder. It does not provide production answer quality.
- SSE emits workflow stage events and a final message. It does not stream generated model tokens.
- Redis stores short-lived state/cache only. It is not durable memory.
- Offline retrieval cache invalidation is TTL-based only; live context caching includes document revision checks.
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

### Semantic Context Cache

- Optional bounded Java cosine matching, not a large-scale ANN cache or model-based equivalence judge.
- The initial 0.96 threshold has not been calibrated on real queries. It is not a 96% correctness probability. Fixed-vector tests verify control flow, not semantic quality.
- Follow-up resolution and semantic-cache constraints do not yet share a canonical time/entity scope. A rewrite can make an implicit range explicit and cause `no_compatible_source` against an earlier vague question. Observed near-topic queries can also fall below 0.96. These conservative misses are documented, not fixed by this release; do not claim reliable paraphrase-hit coverage or disable year guards to force a demo hit.
- Lexical number/negation/intent guards are incomplete. Unknown or complex questions skip semantic reuse; high-similarity questions with different entities can still be misclassified. Reused context may omit evidence required by a new question.
- Only fresh retrieval seeds sources; no approximate-hit chains, exact-key promotions or read-driven TTL renewal. This reduces drift but cannot eliminate false reuse.
- Source count is bounded per scope. Different versions/settings/identities can create many buckets; TTL is not a global quota. JSON vectors increase Redis traffic; no load/performance claims are made.
- Query vectors and hashed features are sensitive derived data, not anonymous data. Header tenant identity is still not authentication, and old physical entries remain until expiry/cleanup.
- Every hit still calls the answer model; semantic hits also require a question embedding. No final-answer caching or paid model quality evaluation was added.

### Learning Workbench

- The workbench shows durable diagnostic tasks and bounded page-local RAG follow-ups, not a production assistant UI or durable chat memory.
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
