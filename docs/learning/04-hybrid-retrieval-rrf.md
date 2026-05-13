# Milestone 4 Learning Note: Hybrid Retrieval + RRF

## Problem This Milestone Solves

Milestone 3 made child chunks searchable by vector similarity. Milestone 4 adds a second retrieval path: PostgreSQL full-text search over child chunk text.

The goal is hybrid retrieval:

- Vector search finds semantically similar child chunks.
- Full-text search finds exact or keyword-oriented child chunks.
- RRF combines both ranked lists without comparing their raw scores directly.

This milestone does not answer questions. It exposes a debug API so retrieval behavior can be inspected before reranking and context construction are added.

## What Was Built

This milestone adds:

- `SemanticRetrievalService`
- `FullTextRetrievalService`
- `HybridRetrievalService`
- `RrfFusionService`
- `FullTextSearchRepository`
- PgVector semantic retrieval with optional document filtering
- PostgreSQL full-text retrieval with optional document filtering
- `POST /api/v1/retrieval/debug`
- Full-text GIN index on `child_chunks.text`
- Tests for semantic retrieval, full-text retrieval, RRF scoring, deduplication, source tracking, route behavior, and repository integration where Docker is available

## Main Classes And Responsibilities

`SemanticRetrievalService`

Embeds the query through `EmbeddingService`, then calls `VectorSearchRepository` to search `child_chunk_embeddings`. It assigns one-based vector ranks to the returned candidates.

`FullTextRetrievalService`

Calls `FullTextSearchRepository` to run PostgreSQL full-text search over child chunk text. It assigns one-based full-text ranks to the returned candidates.

`HybridRetrievalService`

Validates the request, normalizes `topK`, runs semantic and full-text retrieval, and passes both candidate lists to RRF fusion.

`topK` is the requested candidate limit for each retrieval path and for the final fused list. If `topK` is omitted, the service uses `nexus.retrieval.default-top-k`. If it is larger than `nexus.retrieval.max-top-k`, it is capped. If it is less than 1, the request is rejected.

`RrfFusionService`

Deduplicates candidates by `child_chunk_id`, tracks which retrieval source found each candidate, and computes the fused RRF score.

`FullTextSearchRepository`

Uses PostgreSQL `websearch_to_tsquery`, `to_tsvector`, and `ts_rank_cd` to return keyword-ranked child chunks.

`RetrievalDebugController`

Exposes `POST /api/v1/retrieval/debug`. The endpoint returns vector candidates, full-text candidates, and fused candidates with debug metadata.

## Data Flow

```text
POST /api/v1/retrieval/debug
  -> RetrievalDebugController
  -> HybridRetrievalService
  -> SemanticRetrievalService
       -> EmbeddingService embeds the query
       -> VectorSearchRepository searches child_chunk_embeddings
  -> FullTextRetrievalService
       -> FullTextSearchRepository searches child_chunks.text
  -> RrfFusionService
       -> deduplicate by child_chunk_id
       -> calculate RRF score from rank positions
       -> preserve vector/full-text debug details
  -> API response
```

## Why Hybrid Retrieval

Vector search and full-text search solve different retrieval problems.

Vector search is useful when the query and document use different words but similar meaning. Full-text search is useful when exact terms, identifiers, product names, policy names, or technical phrases matter.

Using both gives the retrieval layer two independent signals. A candidate that appears in both lists is often more trustworthy than a candidate found by only one path.

## Why RRF

Vector distance and full-text score are not directly comparable:

- Vector distance measures similarity in embedding space.
- Full-text score measures term matching and term distribution.

RRF avoids score calibration by using rank positions only:

```text
score(candidate) = sum(1 / (k + rank_i(candidate)))
```

Ranks are one-based, not zero-based. The best candidate from a retrieval path has rank `1`, so with the default `k=60`, a rank 1 hit contributes:

```text
1 / (60 + 1)
```

If the same child chunk appears as rank 1 in vector search and rank 2 in full-text search, its fused score is:

```text
1 / 61 + 1 / 62
```

That candidate's source becomes `both`.

The implementation keeps vector distance and full-text score as debug fields, but RRF does not use those raw values when computing the fused score.

## Candidate Identity And Source Tracking

The fusion key is `child_chunk_id`.

That matters because child chunks are the precise retrieval unit. Embeddings are stored by `child_chunk_id`, retrieval results refer to `child_chunk_id`, and later citations can point back through `child_chunk_id`, `parent_chunk_id`, `document_id`, and character offsets.

The API reports source as:

- `vector`
- `full_text`
- `both`

It also preserves:

- vector rank
- vector distance
- full-text rank
- full-text score
- RRF score
- child chunk ID
- parent chunk ID
- document ID
- chunk index
- preview text

## Database Changes

Milestone 4 adds a full-text index:

```sql
CREATE INDEX idx_child_chunks_text_fts
    ON child_chunks
    USING GIN (to_tsvector('english', text));
```

Semantic search uses the existing `child_chunk_embeddings.embedding VECTOR(384)` column from Milestone 3.

Full-text search uses PostgreSQL's English text search configuration for now.

## Ranking And Filtering Semantics

Semantic retrieval ranks by ascending PgVector cosine distance. Smaller distance means the query vector is closer to the child chunk vector.

Full-text retrieval ranks by descending PostgreSQL `ts_rank_cd` score. Higher score means PostgreSQL considers the text match stronger for the query.

Both retrieval paths accept optional `documentIds`. When provided, the filter is applied inside both SQL queries:

- vector retrieval filters joined child chunks by `c.document_id`
- full-text retrieval filters child chunks by `c.document_id`

If a document has chunks but no stored child chunk embeddings, vector retrieval returns an empty candidate list for that document. The debug endpoint should still return a normal response with empty vector and fused lists unless full-text search finds candidates.

## API

Request:

```bash
curl -X POST http://localhost:8080/api/v1/retrieval/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "security policy",
    "documentIds": ["{document-id}"],
    "topK": 5
  }'
```

Response shape:

```json
{
  "query": "security policy",
  "vectorCandidates": [],
  "fullTextCandidates": [],
  "fusedCandidates": []
}
```

Each candidate includes IDs, ranks, scores, source, and preview text.

## How To Run

Start dependencies:

```bash
docker compose up -d
```

Run the app:

```bash
mvn spring-boot:run
```

Prepare a document:

```bash
printf "Security policy access controls require quarterly review.\nLunch menu is posted weekly.\n" > sample.txt

curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@sample.txt;type=text/plain"

curl -X POST http://localhost:8080/api/v1/documents/{document-id}/chunks

curl -X POST http://localhost:8080/api/v1/documents/{document-id}/embed
```

Run retrieval:

```bash
curl -X POST http://localhost:8080/api/v1/retrieval/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "security policy",
    "documentIds": ["{document-id}"],
    "topK": 5
  }'
```

## How To Test

Run all tests:

```bash
mvn test
```

Run only retrieval unit and route tests:

```bash
mvn test -Dtest='com.nexusagent.retrieval.application.*Test,com.nexusagent.retrieval.api.*Test'
```

Repository integration tests use Testcontainers and the `pgvector/pgvector:pg16` image. If Docker is unavailable, Testcontainers skips those tests.

## Design Defense

The key design choice is keeping retrieval paths independent until the fusion stage.

Semantic retrieval owns query embedding and PgVector search. Full-text retrieval owns PostgreSQL keyword search. RRF owns fusion. This keeps the code readable and makes it clear that raw vector and full-text scores are not mixed.

The debug API is intentionally not a query-answering API. It is a tool to inspect candidate generation before adding reranking, context construction, and citations.

## Known Limitations

- The API returns retrieval candidates only; it does not rerank, construct context, answer questions, or stream responses.
- The local deterministic embedding provider is not a production semantic model.
- Full-text search currently uses PostgreSQL's English configuration only.
- RRF does not tune or calibrate raw vector and full-text scores.
- The vector search path only returns child chunks that already have embeddings.
- There is no Redis retrieval cache yet.
- There are no retrieval metrics or tracing yet.

## Future Improvements

- Add reranking in Milestone 5.
- Expand child hits to parent context in Milestone 5.
- Add citation-aware context construction.
- Add language-aware full-text configuration and query preprocessing.
- Add retrieval metrics for candidate counts, latency, and empty-result cases.
- Tune PgVector and full-text indexes against realistic data volume.
