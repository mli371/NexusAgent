# Milestone 3 Learning Note: Embedding Pipeline + PgVector

## Problem This Milestone Solves

Milestone 2 created parent and child chunks. Milestone 3 turns child chunks into vectors and stores those vectors in PostgreSQL with PgVector.

This milestone does not implement user-facing retrieval. It prepares the semantic search storage layer that Milestone 4 will use.

The key rule is:

- Child chunks are embedded because they are small, focused retrieval units.
- Parent chunks are not embedded in this MVP because they are larger context-expansion units.

## What Was Built

This milestone adds:

- `EmbeddingProvider` interface
- `LocalDeterministicEmbeddingProvider`
- `SpringAiEmbeddingProvider` adapter boundary
- `child_chunk_embeddings` table with `vector(384)`
- `EmbeddingService`
- `ChildChunkEmbeddingService`
- `ChildChunkEmbeddingRepository`
- `VectorSearchRepository`
- `POST /api/v1/documents/{documentId}/embed`
- `GET /api/v1/documents/{documentId}/embedding-status`
- Tests for deterministic dimensions, child-only embedding, persistence, API routes, and simple vector similarity search

## Main Classes And Responsibilities

`EmbeddingProvider`

Defines the provider boundary. The rest of the application asks for an embedding vector without knowing whether it came from a local deterministic provider or a future Spring AI-backed provider.

`LocalDeterministicEmbeddingProvider`

Produces deterministic 384-dimensional vectors from token hashes. This is useful for local demos and tests because it does not require network access or an API key.

It is not a production semantic embedding model.

`SpringAiEmbeddingProvider`

Wraps a `SpringAiEmbeddingClient` boundary. This keeps the project ready for a real Spring AI `EmbeddingModel` adapter later, but no Spring AI client is required for tests or local runs.

`EmbeddingService`

Calls the configured provider and verifies the returned vector dimension matches the provider metadata.

`ChildChunkEmbeddingService`

Coordinates the document embedding use case:

1. Verify the document exists.
2. Load parent and child chunks.
3. Reject documents that have no child chunks.
4. Find child chunks that already have embeddings.
5. Embed only missing child chunks.
6. Persist embeddings.
7. Return embedding status.

`ChildChunkEmbeddingRepository`

Stores one embedding row per child chunk in PostgreSQL. It uses PgVector by casting the vector literal into the `vector` type.

`VectorSearchRepository`

Performs simple exact cosine-distance vector search over child chunk embeddings. This is repository-level functionality for Milestone 4; it is not exposed as a retrieval API yet.

`DocumentEmbeddingController`

Exposes the embedding APIs.

## Data Flow

```text
POST /api/v1/documents/{documentId}/embed
  -> DocumentEmbeddingController
  -> ChildChunkEmbeddingService
  -> DocumentRepository verifies document exists
  -> ChunkRepository loads parent/child chunks
  -> ChildChunkEmbeddingRepository finds existing embeddings
  -> EmbeddingService embeds missing child chunks
  -> ChildChunkEmbeddingRepository stores vectors in PgVector
  -> API returns embedding status
```

Status inspection uses:

```text
GET /api/v1/documents/{documentId}/embedding-status
  -> DocumentEmbeddingController
  -> ChildChunkEmbeddingService
  -> ChunkRepository counts child chunks
  -> ChildChunkEmbeddingRepository counts embedding rows
  -> API returns embedding status
```

## Database Shape

Milestone 3 adds `child_chunk_embeddings`:

```text
child_chunk_id UUID PRIMARY KEY
document_id UUID NOT NULL
embedding VECTOR(384) NOT NULL
provider TEXT NOT NULL
model_name TEXT NOT NULL
dimension INTEGER NOT NULL
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
```

Relationships:

- `child_chunk_id` references `child_chunks(id)` with `ON DELETE CASCADE`.
- `document_id` references `documents(id)` with `ON DELETE CASCADE`.

The cascade matters because `POST /chunks?force=true` deletes and recreates child chunks. Old embeddings are removed automatically with the old child chunk rows.

## Why A Separate Embedding Table

Embeddings are provider/model-specific vector data. Chunks are source text metadata.

Keeping embeddings in `child_chunk_embeddings` avoids mixing those responsibilities. It also makes future provider/model versioning easier because embedding metadata has its own table.

## Why Only Child Chunks Are Embedded

Child chunks are smaller and more focused. That makes each embedding represent a narrow piece of meaning, which is better for precise semantic matching.

Parent chunks are intentionally larger. They are better used after retrieval, when the system expands from a precise child hit to the parent context for answer construction and citations.

Future retrieval should look like:

```text
query
  -> embed query
  -> vector search over child_chunk_embeddings
  -> get matching child chunks
  -> use parent_chunk_id to fetch parent context
  -> build citation-aware context
```

Milestone 3 stops after vector storage and repository-level vector search.

## Provider Behavior

Default configuration:

```text
nexus.embeddings.provider=local
nexus.embeddings.dimension=384
```

The local provider:

- Requires no API key.
- Is deterministic.
- Produces 384-dimensional vectors.
- Is suitable for tests and local demos.
- Is not a real semantic embedding model.

The Spring AI boundary:

- Exists as `SpringAiEmbeddingProvider`.
- Requires a `SpringAiEmbeddingClient` bean if `nexus.embeddings.provider=spring-ai`.
- Is not wired to a real external provider yet.

This is intentional. The project should not pretend that live AI provider integration exists before it is implemented.

## Vector Index Behavior

The migration creates PgVector storage and a normal `document_id` index.

It also attempts to create an HNSW cosine index only if the local PgVector build exposes the `hnsw` access method. If HNSW is unavailable, exact vector search still works. That is acceptable for this milestone because the goal is correctness and explainability, not benchmarked retrieval performance.

## APIs

Generate embeddings:

```bash
curl -X POST http://localhost:8080/api/v1/documents/{document-id}/embed
```

Check status:

```bash
curl http://localhost:8080/api/v1/documents/{document-id}/embedding-status
```

Example response:

```json
{
  "documentId": "7bfc50c2-81f9-49df-872f-f401f8fbff20",
  "childChunkCount": 2,
  "embeddedChildChunkCount": 2,
  "missingChildChunkCount": 0,
  "complete": true,
  "provider": "local",
  "modelName": "local-deterministic-hash-384",
  "dimension": 384
}
```

## How To Run

Start dependencies:

```bash
docker compose up -d
```

Run the app:

```bash
mvn spring-boot:run
```

Upload, chunk, and embed:

```bash
printf "# Handbook\n\nEmployees must complete access reviews.\n" > handbook.md

curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@handbook.md;type=text/markdown"

curl -X POST http://localhost:8080/api/v1/documents/{document-id}/chunks
curl -X POST http://localhost:8080/api/v1/documents/{document-id}/embed
curl http://localhost:8080/api/v1/documents/{document-id}/embedding-status
```

Run tests:

```bash
mvn test
```

Run a clean test build:

```bash
mvn clean test
```

Testcontainers-based PgVector tests require Docker. If Docker is unavailable, those integration tests are skipped.

## Design Defense

Use this concise explanation for Milestone 3:

> I added the embedding stage after chunking. The system embeds only child chunks because they are the precise retrieval units. Parent chunks are not embedded; they remain context-expansion units for later answer construction. Embeddings are stored in a separate `child_chunk_embeddings` table keyed by `child_chunk_id`, using PgVector with a fixed 384-dimensional local deterministic provider for repeatable tests and demos. The provider boundary lets a real Spring AI adapter be added later without rewriting the pipeline.

Key points to defend:

- Child chunks are embedded for precision.
- Parent chunks are preserved for context expansion.
- `child_chunk_id` is the stable link between chunks, embeddings, retrieval results, and future citations.
- The local deterministic provider is honest test/demo infrastructure, not a fake production embedding model.
- The Spring AI adapter boundary is optional and not enabled by default.
- PgVector exact search is enough for this milestone; index tuning belongs with real retrieval workloads.

## Known Limitations

- The local deterministic provider is not semantically comparable to a real embedding model.
- The embedding dimension is fixed at 384 in the schema.
- No external Spring AI provider is wired yet.
- No embedding job table, retry state, failure reason, or provider version history exists yet.
- No user-facing retrieval API exists yet.
- No hybrid retrieval, keyword search, RRF, reranking, context construction, query API, SSE, Redis state/cache, or agent workflow is implemented.
- HNSW index creation is conditional on PgVector support; exact scan is the fallback.

## Future Improvements

- Add a real Spring AI `EmbeddingModel` adapter.
- Add embedding job tracking with retries and failure reasons.
- Add provider/model versioning.
- Add explicit invalidation behavior after forced re-chunking.
- Add vector retrieval service and hybrid retrieval in Milestone 4.
- Tune PgVector indexing after realistic data volume and query patterns exist.
