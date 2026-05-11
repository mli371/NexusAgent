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

Embed child chunks and store vectors in PostgreSQL using PgVector. Provide a deterministic local embedding provider for tests and local demos, plus an adapter boundary for Spring AI.

Expected scope:

- `EmbeddingProvider` interface
- Deterministic local embedding implementation
- Spring AI adapter boundary where reasonable
- PgVector schema and indexes
- Embedding persistence
- Tests that avoid live AI dependencies

## Milestone 4: Hybrid Retrieval + RRF

Implement semantic vector retrieval and PostgreSQL full-text keyword retrieval, then fuse candidate rankings with Reciprocal Rank Fusion.

Expected scope:

- Vector search service
- Keyword/full-text search service
- Candidate model
- RRF fusion service
- Tests for ranking and fusion behavior

## Milestone 5: Reranking + Context Construction

Add a reranking stage and build citation-aware context from retrieved child chunks expanded to parent chunks.

Expected scope:

- Reranker interface
- Deterministic/mock reranker
- Context builder
- Citation model
- Tests for reranking, context size, and citations

## Milestone 6: Query API + SSE, With Placeholder State/Cache Interfaces

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

Replace or extend the Milestone 6 placeholder interfaces with Redis-backed implementations.

Expected scope:

- Redis-backed session state
- Redis-backed retrieval cache
- Redis-backed intermediate tool output store
- Expiration policies
- Serialization strategy
- Tests for Redis behavior where practical

## Milestone 8: Plan-Execute-Critique Workflow

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
- Autonomous background agents

## Milestone 9: Hardening + Interview Polish

Improve reliability, documentation, local demo quality, and interview readiness.

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

## Definition Of Done For Every Milestone

1. Code implemented
2. Tests added
3. `README.md` or docs updated
4. `docs/learning/<milestone>.md` created
5. Interview defense section added
6. Known limitations documented
7. Commands provided to run tests and demo
8. No fake or undocumented stubs
