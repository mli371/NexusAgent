# NexusAgent Roadmap

This roadmap is canonical for the NexusAgent project. Work should proceed one milestone at a time, and application code should be written only after the current milestone plan is approved.

## Milestone 1: Foundation + Raw Document Upload

Status: Implemented.

Build the initial Spring Boot WebFlux service, Docker Compose environment, Flyway migration baseline, PostgreSQL document metadata storage, MinIO raw file storage, and minimal document upload/read APIs.

Expected scope:

- Java 17 Spring Boot 3.x project setup
- WebFlux application skeleton
- Docker Compose for PostgreSQL with PgVector, Redis, and MinIO
- Flyway migration for document metadata
- Raw file upload to MinIO
- Document metadata persistence in PostgreSQL
- Basic validation and error handling
- Tests for core upload and persistence behavior
- README/docs and learning note

Out of scope:

- Text extraction
- Chunking
- Embeddings
- Retrieval
- Query answering
- Redis-backed behavior
- SSE

## Milestone 2: Text Extraction + Parent-Child Chunking

Status: Implemented.

Extract text from stored documents and split it into parent and child chunks. Parent chunks support context expansion, while child chunks support precise retrieval in later milestones.

Expected scope:

- Text extraction boundary
- Local deterministic extraction for supported text formats
- Parent-child chunking service
- Chunk metadata schema
- Chunk persistence
- Tests for chunk boundaries and parent-child relationships

## Milestone 3: Embedding Pipeline + PgVector

Status: Implemented.

Embed child chunks and store vectors in PostgreSQL using PgVector. Provide a deterministic local embedding provider for tests and local demos, plus an adapter boundary for Spring AI.

Expected scope:

- `EmbeddingProvider` interface
- Deterministic local embedding implementation
- Spring AI adapter boundary where reasonable
- PgVector schema and indexes
- Embedding persistence
- Tests that avoid live AI dependencies

## Milestone 4: Hybrid Retrieval + RRF

Status: Implemented.

Implement semantic vector retrieval and PostgreSQL full-text keyword retrieval, then fuse candidate rankings with Reciprocal Rank Fusion.

Expected scope:

- Vector search service
- Keyword/full-text search service
- Candidate model
- RRF fusion service
- Retrieval debug API
- Tests for ranking and fusion behavior

## Milestone 5: Reranking + Context Construction

Status: Implemented.

Add a reranking stage and build citation-aware context from retrieved child chunks expanded to parent chunks.

Expected scope:

- Reranker interface
- Deterministic/mock reranker
- Context builder
- Citation model
- Context debug API
- Tests for reranking, context size, and citations

## Milestone 6: Query API + SSE, With Placeholder State/Cache Interfaces

Status: Implemented.

Implement the query API and SSE response flow. This milestone may define state/cache/tool-output interfaces, but it must not claim Redis-backed behavior is complete.

Expected scope:

- Query REST API
- SSE streaming API
- Reactor orchestration for query stages
- `SessionStateService` interface
- `RetrievalCacheService` interface
- `ToolOutputStore` interface
- Simple in-memory or no-op implementations if needed
- Clear documentation that Redis is not integrated yet

Out of scope:

- Redis-backed session state
- Redis-backed retrieval cache
- Redis-backed tool output storage

## Milestone 7: Redis State + Cache Integration

Status: Implemented.

Replace or extend the Milestone 6 placeholder interfaces with Redis-backed implementations.

Expected scope:

- Redis-backed session state
- Redis-backed retrieval cache
- Redis-backed intermediate tool output store
- Expiration policies
- Serialization strategy
- Tests for Redis behavior where practical

## Milestone 8: Plan-Execute-Critique Workflow

Status: Implemented.

Add a minimal Plan-Execute-Critique workflow for query handling without overbuilding multi-agent orchestration.

Expected scope:

- Planner boundary
- Executor orchestration
- Critique step
- Clear limits on what the workflow can and cannot do
- Tests for workflow decisions and failure paths

Out of scope:

- MCP
- Multi-agent platform abstractions
- Autonomous background workers

## Milestone 9: Hardening + Documentation Polish

Status: Implemented.

Improve reliability, documentation, local demo quality, and project readiness.

Expected scope:

- Error handling review
- Observability basics
- Documentation cleanup
- Architecture diagrams where useful
- End-to-end demo path
- Test cleanup
- Explicit production-readiness gaps

Out of scope:

- Kubernetes
- Production performance benchmarking claims
- Unsupported scale or reliability guarantees

## Milestone 10: Enterprise Readiness Slice

Status: Implemented.

Add a small enterprise-readiness skeleton without building a full production enterprise platform.

Expected scope:

- Header-based tenant and actor request context
- Tenant fields on documents
- Tenant-aware document and retrieval filtering
- Audit events for major lifecycle operations
- Synchronous ingestion job status rows for chunk/embed APIs
- Trace propagation for query and agent APIs
- Safe Actuator health/info endpoints

Out of scope:

- OAuth2, Keycloak, or full authentication
- Full RBAC/ABAC
- Production tenant isolation guarantees
- Async ingestion workers
- Full OpenTelemetry, Prometheus, or Grafana
- Production-grade enterprise security claims

## Approved Extension: Agent Harness

The original ten milestones remain unchanged. This is a separately reviewed extension, not a completed Milestone 11 or a new production-readiness claim.

1. Read-only diagnostics: implemented for review, including Java/PostgreSQL run management, two scoped tools, a Pi adapter, and a model-free scripted worker. Live provider smoke: PASS with OpenAI `gpt-5.6-luna`; see [validation scope](docs/verification/agent-harness-gpt-5.6-luna.md).
2. Human approval and bounded ingestion retry: reviewed. Actor-bound proposals, CHUNK/EMBED_MISSING dispatch, exact job linkage, typed failures, stale-state checks, duplicate suppression and bounded planned continuation. See [Phase 2 learning note](docs/learning/agent-harness-02-approval-retry.md).
3. Recovery, cancellation coordination and run event streaming: implemented for review. Bounded lease recovery, exact-job reconciliation without ambiguous write replay, explicit cancellation-pending state, and PostgreSQL-backed SSE replay. See [Chinese review](docs/review/agent-harness-phase-3.md) and [learning note](docs/learning/agent-harness-03-recovery-events.md).

See [the approved design](docs/superpowers/specs/2026-09-10-agent-harness-design.md), [Phase 3 plan](docs/superpowers/specs/2026-09-11-agent-harness-phase-3-plan.md), and [harness guide](docs/agent-harness.md). Review each phase before proceeding. Frontend/login is not implemented by these phases.

### Approved Learning Workbench

Implemented for review after the three backend phases: a separate React/TypeScript client for upload/selection, independent diagnostic tasks, human approvals, real persisted execution nodes, bounded tool observations and static source explanations. Includes a synthetic document corpus, replay/reconnect handling, scoped task restoration, and scripted browser-to-backend validation. It does not add RAG chat, login, new tools, new approval policies, or a new numbered milestone. Header-based identity remains unverified.

See [approved design](docs/superpowers/specs/2026-09-11-learning-workbench-design.md), [Chinese review](docs/review/learning-workbench-01.md), and [startup](docs/learning-workbench.md).

## Approved Real RAG Question-Answering Extension

Separately reviewed extension, not a new production-readiness claim:

1. Provider/document preparation: implemented and reviewed. Optional OpenAI embeddings, tested Responses answer adapter, model-scoped vector search/cache keys, accurate coverage and explicit atomic vector replacement. See [Chinese review](docs/review/rag-phase-1.md).
2. Live query orchestration and genuine stage SSE: implemented for review, including authorization/readiness checks, citation filtering, safe errors and context cache bypass. No live paid acceptance or frontend changes in this step. See [Chinese review](docs/review/rag-phase-2.md).
3. Question-answering learning frontend: implemented for review. Default accessible-ready-library and optional 1–10-document scope, real parallel stage DAG, final evidence/source inspector, parent/child citation highlighting, bounded page memory and no automatic POST replay. Library scope explicitly rejects more than 200 accessible documents. Pi management/approvals remain separate. Browser tests use synthetic HTTP; paid live QA acceptance remains unperformed. See [Chinese review](docs/review/rag-phase-3.md).

See [approved design](docs/superpowers/specs/2026-09-14-rag-question-answering-design.md). Each phase keeps local test providers but cannot present them as live model execution.

## Definition Of Done For Every Milestone

1. Code implemented
2. Tests added
3. `README.md` or docs updated
4. `docs/learning/<milestone>.md` created
5. Design defense section added
6. Known limitations documented
7. Commands provided to run tests and demo
8. No fake or undocumented stubs
