# NexusAgent Source Learning Design

Date: 2026-07-23

Status: Approved learning design

## 1. Purpose

This document defines a structured learning program for understanding and defending the
NexusAgent backend. It does not change application behavior and does not introduce new
features.

The first phase is interview-oriented: learn the system through complete request
lifecycles, connect each design decision to its implementation, and practice explaining
the trade-offs. A later phase can then examine individual classes and methods in greater
depth.

## 2. Learner Profile and Constraints

- Available time: 45-60 minutes per day.
- Primary explanation language: Chinese.
- Interview practice language: English.
- Preferred method: explanation, learner teach-back, then follow-up questions.
- Current priority: understand the architecture and major call chains before reading
  every implementation detail.
- Scope boundary: no new application code during the learning-design phase.

## 3. Completion Criteria

Phase one is complete when the learner can:

1. Give a clear 60-second project pitch without notes.
2. Give a coherent 5-minute architecture deep dive.
3. Whiteboard the ingestion, retrieval, and query-serving lifecycles.
4. Explain storage ownership across MinIO, PostgreSQL/PgVector, and Redis.
5. Calculate a small Reciprocal Rank Fusion example by hand.
6. Explain where WebFlux helps and where blocking work is isolated.
7. Defend the parent-child retrieval strategy and stable chunk identity.
8. Name at least five honest limitations and corresponding production improvements.
9. Distinguish real implementations from deterministic or simplified components.
10. Avoid unsupported claims about production security, LLM quality, scale, or
    autonomous agents.

## 4. Learning Route Decision

### Selected: Request-Lifecycle Route

The learning sequence follows the data:

1. Raw document ingestion
2. Extraction and chunking
3. Embedding
4. Retrieval and fusion
5. Reranking and context construction
6. Query serving and SSE
7. Redis state and cache
8. Agent workflow and enterprise-readiness controls

This route was selected because it creates an end-to-end mental model early. It also
matches how interviewers usually probe backend systems: request entry, data movement,
failure handling, consistency, and trade-offs.

### Alternative: Milestone Order

Following Milestones 1-10 would preserve project history, but it would repeat some
hardening topics and emphasize chronology over the final runtime architecture.

### Alternative: Layer-by-Layer Route

Reading all controllers, then services, repositories, domains, and tests would make
package ownership clear, but it would fragment each business flow. This route is more
appropriate for phase two after the lifecycle model is stable.

## 5. Standard Session Format

Each 45-60 minute session follows the same structure:

| Time | Activity |
| --- | --- |
| 5 min | Active recall from the previous session |
| 8 min | Place the topic in the overall architecture |
| 15 min | Trace the key source-code call chain |
| 10 min | Discuss design decisions, failure paths, and trade-offs |
| 10 min | Practice English interview answers |
| 5 min | Whiteboard or learner teach-back |
| 2-7 min | Review assignment and unresolved questions |

Each session must produce:

1. A Chinese explanation of one complete call chain.
2. At least two explicit design trade-offs.
3. Two short English interview answers.
4. One current limitation and one production improvement.
5. A teach-back that is corrected for technical accuracy and overclaims.

Phase one reads the classes that define each flow. It does not attempt to memorize every
DTO accessor, mapper, test fixture, or SQL statement.

## 6. Ten-Session Roadmap

### Session 1: System Overview and Storage Boundaries

Objectives:

- Build the top-level architecture map.
- Separate durable source-of-truth data from short-lived state.
- Identify the three main runtime flows.

Primary source entries:

- `src/main/java/com/nexusagent/NexusAgentApplication.java`
- `src/main/resources/application.yml`
- `docker-compose.yml`
- `src/main/resources/db/migration/V1__create_documents_table.sql`
- `src/main/resources/db/migration/V2__create_parent_child_chunks.sql`
- `src/main/resources/db/migration/V4__create_child_chunk_embeddings.sql`
- `src/main/resources/db/migration/V6__enterprise_readiness_slice.sql`
- `src/main/java/com/nexusagent/common/context/RequestContext.java`

Core questions:

- What does MinIO own?
- What does PostgreSQL own?
- What does PgVector add to PostgreSQL?
- What belongs in Redis, and why is it not the source of truth?
- Why does Flyway use JDBC while runtime repositories use R2DBC?

Acceptance:

- Draw the complete component diagram.
- Explain the ingestion and query paths at a high level.
- Deliver the 60-second project pitch.

### Session 2: Document Upload and Cross-Store Consistency

Objectives:

- Trace a multipart upload from HTTP to MinIO and PostgreSQL.
- Understand blocking boundaries and compensation.

Primary source entries:

- `src/main/java/com/nexusagent/documents/api/DocumentController.java`
- `src/main/java/com/nexusagent/documents/application/DocumentUploadService.java`
- `src/main/java/com/nexusagent/documents/application/UploadedFileInspector.java`
- `src/main/java/com/nexusagent/storage/ObjectStorageService.java`
- `src/main/java/com/nexusagent/storage/MinioObjectStorageService.java`
- `src/main/java/com/nexusagent/documents/repository/DocumentRepository.java`
- `src/main/java/com/nexusagent/enterprise/audit/AuditService.java`

Core questions:

- Why is the file copied to a temporary path?
- Where are file hashing and MinIO calls scheduled?
- What happens if MinIO succeeds but metadata insertion fails?
- Why is best-effort compensation used instead of a distributed transaction?
- How are tenant, actor, and audit data attached?

Acceptance:

- Explain success and failure paths in order.
- Defend `boundedElastic` isolation for blocking file and MinIO operations.
- Explain why compensation is best-effort and what risk remains.

### Session 3: Text Extraction and Parent-Child Chunking

Objectives:

- Trace a stored raw file into stable parent and child chunk records.
- Understand chunk offsets, overlap, idempotency, and forced regeneration.

Primary source entries:

- `src/main/java/com/nexusagent/chunking/api/DocumentChunkController.java`
- `src/main/java/com/nexusagent/chunking/application/DocumentChunkingService.java`
- `src/main/java/com/nexusagent/chunking/application/DocumentTextExtractionService.java`
- `src/main/java/com/nexusagent/chunking/application/PlainTextDocumentTextExtractor.java`
- `src/main/java/com/nexusagent/chunking/application/ParentChildChunker.java`
- `src/main/java/com/nexusagent/chunking/repository/ChunkRepository.java`
- `src/main/java/com/nexusagent/enterprise/ingestion/IngestionJobService.java`

Core questions:

- Why are parent and child sizes configured separately?
- Why do child windows overlap?
- Why is `chunk_index` document-global?
- Why are `char_start` and `char_end` global, with an exclusive end?
- Why does a second normal request preserve IDs and timestamps?
- What does `force=true` change, and where is the transaction boundary?

Acceptance:

- Draw one document with parent-child relationships and offsets.
- Explain the current 1200-character parent, 400-character child, and 80-character
  overlap as configurable heuristics rather than universal constants.
- Explain why stable child IDs matter for embeddings, retrieval, citations, and cache.

### Session 4: Child-Only Embedding and PgVector

Objectives:

- Trace child chunks through embedding generation and vector persistence.
- Separate the provider boundary from the local deterministic implementation.

Primary source entries:

- `src/main/java/com/nexusagent/embeddings/api/DocumentEmbeddingController.java`
- `src/main/java/com/nexusagent/embeddings/application/ChildChunkEmbeddingService.java`
- `src/main/java/com/nexusagent/embeddings/application/EmbeddingService.java`
- `src/main/java/com/nexusagent/embeddings/application/EmbeddingProvider.java`
- `src/main/java/com/nexusagent/embeddings/application/LocalDeterministicEmbeddingProvider.java`
- `src/main/java/com/nexusagent/embeddings/application/SpringAiEmbeddingProvider.java`
- `src/main/java/com/nexusagent/embeddings/repository/ChildChunkEmbeddingRepository.java`
- `src/main/java/com/nexusagent/embeddings/repository/VectorSearchRepository.java`

Core questions:

- Why embed child chunks but not parent chunks?
- What does `VECTOR(384)` mean?
- How is cosine distance used and ordered?
- Why is the local provider useful for tests but not evidence of production semantic
  quality?
- What must match when replacing the local provider with a real model?

Acceptance:

- Explain the child chunk to embedding record relationship.
- Explain distance versus similarity without reversing the sort order.
- Clearly label the local deterministic provider as a test/demo implementation.

### Session 5: Hybrid Retrieval and Reciprocal Rank Fusion

Objectives:

- Trace query execution through semantic and full-text retrieval.
- Understand rank-based fusion and candidate identity.

Primary source entries:

- `src/main/java/com/nexusagent/retrieval/application/HybridRetrievalService.java`
- `src/main/java/com/nexusagent/retrieval/application/SemanticRetrievalService.java`
- `src/main/java/com/nexusagent/retrieval/application/FullTextRetrievalService.java`
- `src/main/java/com/nexusagent/retrieval/application/RrfFusionService.java`
- `src/main/java/com/nexusagent/embeddings/repository/VectorSearchRepository.java`
- `src/main/java/com/nexusagent/retrieval/repository/FullTextSearchRepository.java`

Core questions:

- Why are vector distance and full-text scores not compared directly?
- Why does vector search sort cosine distance ascending?
- Why does full-text search sort `ts_rank_cd` descending?
- How does `sum(1 / (60 + rank))` work with one-based ranks?
- How are duplicate `child_chunk_id` values merged?
- How are `vector`, `full_text`, and `both` sources preserved?
- Where are tenant and optional document filters applied?

Acceptance:

- Calculate a small RRF example by hand.
- Explain rank-only fusion and its trade-off.
- Trace a fused child candidate back to its parent and document.

### Session 6: Reranking, Parent Expansion, and Citations

Objectives:

- Trace fused child candidates into a bounded, citation-aware context.
- Distinguish retrieval score, heuristic rerank score, and context selection.

Primary source entries:

- `src/main/java/com/nexusagent/context/application/ContextBuilder.java`
- `src/main/java/com/nexusagent/context/application/Reranker.java`
- `src/main/java/com/nexusagent/context/application/DeterministicHeuristicReranker.java`
- `src/main/java/com/nexusagent/context/application/ParentContextExpansionService.java`
- `src/main/java/com/nexusagent/context/application/CitationFormatter.java`
- `src/main/java/com/nexusagent/context/domain/ContextBuildResult.java`

Core questions:

- Why is a second ranking stage useful after RRF?
- What signals does the deterministic heuristic use?
- Why is it not a real cross-encoder?
- Why expand from selected children to parents?
- How does parent deduplication prevent repeated context?
- How does child-centered trimming preserve evidence under a character budget?
- How are citation IDs and offsets connected to selected evidence?

Acceptance:

- Explain the complete fused-candidate-to-context call chain.
- Defend parent expansion without claiming that larger context is always better.
- Explain why the MVP uses a character budget rather than model token accounting.

### Session 7: Query API, Reactor, and SSE

Objectives:

- Trace normal and streaming query requests.
- Understand reactive composition, resilience, and public/debug response boundaries.

Primary source entries:

- `src/main/java/com/nexusagent/query/api/QueryController.java`
- `src/main/java/com/nexusagent/query/application/QueryOrchestrationService.java`
- `src/main/java/com/nexusagent/query/application/AnswerGenerator.java`
- `src/main/java/com/nexusagent/query/application/LocalTemplateAnswerGenerator.java`
- `src/main/java/com/nexusagent/query/api/QueryStreamEvent.java`
- `src/main/java/com/nexusagent/query/application/QueryProperties.java`

Core questions:

- How does `Mono` compose cache lookup, context construction, answer generation,
  session state, audit, and status updates?
- Which failures are retried, timed out, or converted to fallbacks?
- What does `debug=false` intentionally hide?
- How is one `traceId` preserved across SSE events?
- Why is POST SSE usable with curl/fetch while browser `EventSource` normally expects
  GET?
- Which answer-generation behavior is a local placeholder?

Acceptance:

- Explain the REST and SSE paths using the same orchestration core.
- List the ordered SSE progress events.
- Identify the risk that a timeout/fallback result may be cached and propose a fix.

### Session 8: Redis State, Cache, and Graceful Fallback

Objectives:

- Understand Redis key/value/TTL design and correctness boundaries.
- Trace cache hit, miss, disabled, and unavailable behavior.

Primary source entries:

- `src/main/java/com/nexusagent/query/application/SessionStateService.java`
- `src/main/java/com/nexusagent/query/application/RetrievalCacheService.java`
- `src/main/java/com/nexusagent/query/application/ToolOutputStore.java`
- `src/main/java/com/nexusagent/query/redis/RedisConfiguration.java`
- `src/main/java/com/nexusagent/query/redis/RedisKeyFactory.java`
- `src/main/java/com/nexusagent/query/redis/RedisSessionStateService.java`
- `src/main/java/com/nexusagent/query/redis/RedisRetrievalCacheService.java`
- `src/main/java/com/nexusagent/query/redis/RedisToolOutputStore.java`
- `src/main/java/com/nexusagent/query/application/InMemorySessionStateService.java`
- `src/main/java/com/nexusagent/query/application/NoOpRetrievalCacheService.java`
- `src/main/java/com/nexusagent/query/application/NoOpToolOutputStore.java`

Core questions:

- Which settings are included in a retrieval cache key?
- How do tenant and access scope prevent cross-tenant cache reuse?
- Why do session, retrieval, query status, and tool output use different TTLs?
- Why are values JSON-serialized and versioned?
- How are oversized values rejected?
- Why does Redis failure become a cache miss or ignored write?
- What data must never use Redis as its durable source?

Acceptance:

- Draw representative keys and TTLs.
- Explain first-query miss and second-query hit behavior.
- Identify the remaining session/tool key tenant-scope limitation and production fix.

### Session 9: Plan-Execute-Critique and Enterprise-Readiness Slice

Objectives:

- Understand the deterministic workflow without presenting it as autonomous agents.
- Connect request context, tenant filters, audit events, and ingestion job records.

Primary source entries:

- `src/main/java/com/nexusagent/agent/api/AgentQueryController.java`
- `src/main/java/com/nexusagent/agent/application/AgentOrchestrator.java`
- `src/main/java/com/nexusagent/agent/domain/Plan.java`
- `src/main/java/com/nexusagent/agent/domain/ExecutionResult.java`
- `src/main/java/com/nexusagent/agent/domain/CritiqueResult.java`
- `src/main/java/com/nexusagent/common/context/RequestContext.java`
- `src/main/java/com/nexusagent/documents/domain/DocumentVisibility.java`
- `src/main/java/com/nexusagent/enterprise/audit/AuditService.java`
- `src/main/java/com/nexusagent/enterprise/ingestion/IngestionJobService.java`

Core questions:

- What are the inputs and outputs of plan, execute, and critique?
- How does execution reuse the existing query pipeline?
- How is citation-marker consistency checked?
- What is stored through `ToolOutputStore`, and how is it bounded?
- Where is tenant filtering enforced?
- Why are tenant headers not production authentication?
- Why are audit metadata and ingestion jobs deliberately limited?

Acceptance:

- Explain why this is deterministic orchestration, not a multi-agent platform.
- Explain a retrieval, direct-answer, and insufficient-context plan.
- Defend the enterprise-readiness slice while naming its security limitations.

### Session 10: Final Whiteboard and Mock Interview

Objectives:

- Integrate all topics into one interview narrative.
- Find weak explanations before an interviewer does.

Exercises:

1. Draw the complete architecture from memory.
2. Draw document upload, chunking, and embedding.
3. Draw query retrieval, RRF, reranking, parent expansion, and answering.
4. Draw Redis and enterprise-readiness boundaries.
5. Deliver the 60-second pitch.
6. Deliver the 5-minute technical deep dive.
7. Answer failure scenarios and follow-up questions for 15-20 minutes.

Acceptance:

- Explain all three major flows without notes.
- Name each storage system's ownership boundary.
- Calculate RRF correctly.
- Identify blocking boundaries in a reactive application.
- Present at least five limitations and five production improvements.
- Avoid claims of production readiness, real LLM quality, real cross-encoder reranking,
  complete tenant security, or autonomous multi-agent behavior.

## 7. Teaching Method

Each topic uses the following loop:

1. **Recall:** the learner explains the previous flow before seeing notes.
2. **Trace:** tutor and learner follow one request through controller, application
   service, repository/client, and persistence.
3. **Challenge:** the tutor changes one condition, such as Redis unavailable, metadata
   insertion failure, force rechunk, empty retrieval, or tenant mismatch.
4. **Teach-back:** the learner explains the design in Chinese.
5. **Interview answer:** the learner gives a concise English answer.
6. **Correction:** the tutor fixes technical errors, missing trade-offs, and overclaims.

Questions should increasingly test causal reasoning. The target is not "what class is
this?" but "why is this boundary here, what failure can occur, and how would production
change it?"

## 8. Progress Tracking

For each session, record:

- Date completed
- Confidence from 1-5
- Call chain reproduced without notes: yes/no
- English answers completed: 0/2
- Trade-offs explained: 0/2
- Limitation and improvement stated: yes/no
- Open questions

A topic with confidence below 4 or an incorrect teach-back is reviewed at the beginning
of the next session.

## 9. Phase-One Scope Boundaries

Phase one intentionally does not:

- Add or change application features.
- Read every class line by line.
- Memorize framework annotations without connecting them to behavior.
- Claim production-grade embedding, reranking, answer generation, authentication,
  tenant isolation, agent autonomy, or performance.
- Treat documentation as proof when the source behaves differently.

When documentation and code differ, the implementation and tests are inspected and the
difference is recorded as a project limitation or stale-documentation issue.

## 10. Transition to Phase Two

After Session 10 passes, phase two can use a layer-oriented deep dive:

1. Controllers and validation
2. Application services and orchestration
3. Repositories and SQL
4. Domain records and invariants
5. Configuration and conditional beans
6. Error handling, logging, and failure recovery
7. Unit, route, repository, and integration tests

Phase two should focus on method-level reasoning, Reactor operator behavior, SQL query
semantics, and test design. It should start only after the learner can place each class
inside the end-to-end architecture.

## 11. Visual Aids

Visual aids may be used for architecture, lifecycles, RRF examples, parent-child windows,
and Redis key boundaries. They support discussion but do not replace reading the source
or performing the learner teach-back.

## 12. Start Condition

Session 1 begins after this learning design is reviewed. The first session starts with
an unassisted architecture recall, then verifies that mental model against the project
configuration, migrations, request context, and storage boundaries.
