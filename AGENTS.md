# NexusAgent Working Rules

## Project Purpose

NexusAgent is an interview-defensible Java backend portfolio project: an Enterprise Knowledge Assistant Backend. The project should demonstrate clear backend architecture, reliable document ingestion, retrieval-oriented data modeling, reactive orchestration, and practical engineering trade-offs.

The goal is a working local system that can be explained in interviews without pretending that simplified pieces are production-grade.

## Tech Stack

- Java 17
- Spring Boot 3.x
- Spring WebFlux
- Project Reactor
- Spring AI abstraction where it fits naturally
- PostgreSQL with PgVector
- Redis
- MinIO
- Docker Compose
- Flyway
- JUnit 5
- Testcontainers where practical

## Architecture Principles

- Build in small milestones. Each milestone must leave the project runnable, tested, and documented.
- Prefer readable, maintainable, interview-friendly code over clever abstractions.
- Keep the backend as a single Spring Boot service unless there is a clear reason to split it.
- Use parent-child chunking for retrieval design: child chunks are embedded for precise matching, parent chunks are used for context expansion.
- Treat MinIO as the raw file store, PostgreSQL as the source of truth for metadata and chunks, and Redis as short-lived cache/state only.
- Use WebFlux and Reactor to compose I/O-bound stages with explicit timeout, retry, fallback, and error handling where appropriate.
- If a dependency or library is blocking, isolate it behind a small service boundary and run it on an appropriate scheduler, such as Reactor bounded elastic.
- Avoid Kubernetes, MCP, complex multi-agent systems, and unsupported performance claims in early milestones.
- Prefer interfaces at integration boundaries, not around every class.

## Canonical Roadmap

This is the approved project roadmap:

1. Foundation + Raw Document Upload
2. Text Extraction + Parent-Child Chunking
3. Embedding Pipeline + PgVector
4. Hybrid Retrieval + RRF
5. Reranking + Context Construction
6. Query API + SSE, with placeholder state/cache interfaces
7. Redis State + Cache integration
8. Plan-Execute-Critique Workflow
9. Hardening + Interview Polish

Milestone 6 must implement the query API and SSE response flow, but it must not imply Redis-backed session or cache behavior is complete. In Milestone 6, create clean interfaces such as `SessionStateService`, `RetrievalCacheService`, and `ToolOutputStore`, backed by simple in-memory or no-op implementations if needed. Milestone 7 replaces or extends those interfaces with Redis-backed implementations.

## Coding Style

- Use clear package boundaries by feature and responsibility.
- Keep classes small enough to explain in an interview.
- Use domain names that match the project language: document, chunk, embedding, retrieval, rerank, context, session, citation.
- Favor constructor injection.
- Avoid static utility sprawl unless the function is pure, narrow, and genuinely reusable.
- Keep DTOs separate from persistence entities and domain concepts when it improves clarity.
- Validate external input at API boundaries.
- Return explicit errors rather than leaking low-level exceptions to API clients.
- Use Java records for simple immutable request/response models where appropriate.
- Add comments only when they clarify non-obvious design decisions or trade-offs.

## Testing Requirements

- Every milestone must include tests proportional to the risk of the change.
- Unit tests should cover deterministic business logic, validation, object key generation, chunking, ranking, fusion, context construction, and workflow decisions.
- Integration tests should cover persistence, Flyway migrations, and external service boundaries where practical.
- Use Testcontainers for PostgreSQL, Redis, and MinIO when it provides meaningful confidence without excessive complexity.
- Do not rely only on happy-path tests.
- Tests should be deterministic and runnable locally with a documented command.
- Mock AI, embedding, and reranking providers for tests unless the milestone is explicitly about live provider integration.

## Documentation Requirements

- Every milestone must update `README.md` or a file under `docs/`.
- Documentation must include:
  - What was built
  - How to run it locally
  - How to test it
  - Important design decisions
  - Known limitations
  - Future improvements
- API behavior must be documented with example requests and responses once endpoints exist.
- Database changes must be documented at a high level when migrations are added.

## Learning-Note Requirements

- Every milestone must add a learning note under `docs/learning/`.
- Learning notes should teach the design and implementation choices in plain language.
- Each learning note must explain:
  - The problem this milestone solves
  - The main classes and responsibilities
  - The data flow
  - Why the chosen design is reasonable
  - What is simplified for now
  - How to extend it later

## Interview-Defense Requirements

- Every milestone must include a short interview-defense section in its docs.
- The interview defense should explain:
  - What the module does
  - Why the design was chosen
  - What trade-offs were made
  - How the implementation handles failure
  - What would change in a production version
- Avoid vague claims. Be specific about boundaries, guarantees, and limitations.

## No Fake Features

- Do not silently fake unfinished features.
- Do not expose an API that implies real retrieval, embedding, reranking, AI generation, Redis state, or streaming behavior before it is implemented.
- If a provider is deterministic, local, mocked, or simplified, name it clearly in code and documentation.
- If a method is intentionally incomplete, mark it as unsupported or not yet implemented instead of returning misleading data.
- Prefer smaller honest milestones over broad but hollow implementations.

## Trade-Offs And Limitations

- Every milestone must document known limitations and future improvements.
- Blocking dependencies, simplified algorithms, local-only assumptions, missing production hardening, and mock providers must be called out explicitly.
- When choosing a simpler design, document why it is sufficient for the current milestone and what would trigger a more advanced design.
- Do not claim production readiness unless the project has the required operational, security, reliability, and observability work.

## Workflow Rules For This Repository

- Before implementing application code for a milestone, present the milestone plan and wait for approval.
- Implement only the approved milestone.
- For each approved milestone, produce:
  - Code changes
  - Tests
  - Documentation updates
  - A learning note under `docs/learning/`
  - An interview-defense section
  - Commands to run code and tests locally
  - Known limitations and future improvements
- Keep changes scoped to the current milestone.
- Do not introduce large unrelated refactors.
- Do not revert user changes unless explicitly asked.

## Milestone Definition Of Done

Every milestone is complete only when all of the following are true:

1. Code implemented
2. Tests added
3. `README.md` or docs updated
4. `docs/learning/<milestone>.md` created
5. Interview defense section added
6. Known limitations documented
7. Commands provided to run tests and demo
8. No fake or undocumented stubs
