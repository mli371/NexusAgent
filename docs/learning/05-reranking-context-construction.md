# Milestone 5 Learning Note: Reranking + Context Construction

## Problem This Milestone Solves

Milestone 4 produced fused retrieval candidates. Those candidates are still child chunks, and child chunks are too small to pass directly as final context.

Milestone 5 turns fused candidates into citation-aware context:

- rerank fused child chunk candidates
- select useful child chunks
- expand selected child chunks to parent chunks
- deduplicate repeated parent chunks
- apply a character budget
- produce citations and final context text

This milestone does not generate answers, stream responses, use Redis, or call a language model.

## What Was Built

This milestone adds:

- `Reranker`
- `DeterministicHeuristicReranker`
- `ParentContextExpansionService`
- `ContextBuilder`
- `CitationFormatter`
- `POST /api/v1/context/debug`
- citation metadata
- final context text
- tests for reranking, source boost, parent diversity, parent expansion, parent deduplication, budget trimming, citation formatting, and API routing

## Main Classes And Responsibilities

`Reranker`

Defines the reranking boundary. A future cross-encoder or external rerank API can implement this interface without changing context construction.

`DeterministicHeuristicReranker`

The MVP reranker. It is deterministic and local. It uses:

- RRF score
- keyword overlap between query and candidate preview text
- source signal: `both` gets more contribution than a single source
- parent diversity penalty
- document diversity penalty

It is not a trained reranking model.

`ParentContextExpansionService`

Loads the full child chunk, parent chunk, and document metadata for reranked candidates. This is where `parent_chunk_id` turns a precise child hit into larger parent context.

`ContextBuilder`

Coordinates the full context flow:

1. call hybrid retrieval
2. rerank fused candidates
3. expand child hits to parent chunks
4. deduplicate by `parent_chunk_id`
5. apply `contextBudgetChars`
6. build selected children, parent contexts, citations, and final context text

`CitationFormatter`

Creates citation markers such as `[C1]` and formats the final context text.

`ContextDebugController`

Exposes `POST /api/v1/context/debug`.

## Data Flow

```text
POST /api/v1/context/debug
  -> ContextDebugController
  -> ContextBuilder
  -> HybridRetrievalService
       -> vector retrieval
       -> full-text retrieval
       -> RRF fusion
  -> Reranker
       -> deterministic heuristic reranking
  -> ParentContextExpansionService
       -> load child chunks
       -> load parent chunks
       -> load document metadata
  -> ContextBuilder
       -> select child chunks
       -> deduplicate parent chunks
       -> apply character budget
  -> CitationFormatter
       -> citation metadata
       -> final context text
```

## Why Reranking Exists

Hybrid retrieval is optimized for recall. It tries to find plausible candidates from multiple retrieval paths.

Reranking is a second-stage ordering step. It can use more signals and should improve the quality of the smaller set of candidates that move forward to context construction.

In this MVP, reranking is deterministic and heuristic so tests remain stable and no external model is required.

## Heuristic Reranker Behavior

The heuristic score includes:

- weighted RRF score
- keyword overlap score
- source boost
- diversity penalty

The `both` source gets a larger boost than `vector` or `full_text` alone because two independent retrieval paths found the same child chunk.

Parent diversity helps avoid selecting many child chunks from the same parent chunk. Document diversity lightly discourages selecting too many candidates from one document.

## Why Parent Context Expansion

Child chunks are precise retrieval units. They are small enough for embeddings and exact matching.

Parent chunks are context units. They contain more surrounding text, which is more useful for later answer generation.

The flow is:

```text
retrieve child chunk
  -> keep child_chunk_id for citation
  -> use parent_chunk_id to fetch parent chunk
  -> include parent text in final context
```

## Deduplication

Context construction deduplicates by `parent_chunk_id`.

If multiple selected child chunks point to the same parent, only the first parent context is included. This avoids repeating the same large context block.

The debug metadata reports how many repeated parent contexts were skipped.

## Budget Behavior

The request supports:

```json
{
  "contextBudgetChars": 2000
}
```

If omitted, the default comes from:

```text
nexus.context.default-budget-chars
```

If larger than:

```text
nexus.context.max-budget-chars
```

it is capped.

The current budget is character-based, not model-token-based. This keeps the MVP deterministic and simple to test, but a production system should count tokens using the target model's tokenizer.

If a parent chunk exceeds the remaining budget, it is trimmed and marked as `truncated`. The trim is child-centered: the builder uses the selected child chunk's global `charStart` and `charEnd` offsets to choose a window around the matched evidence instead of blindly taking the beginning of the parent chunk. This keeps the cited evidence visible in `finalContextText` whenever the budget is large enough to include at least part of that child span.

## Citation Metadata

Each citation includes:

- `documentId`
- `originalFilename`
- `parentChunkId`
- `childChunkId`
- `chunkIndex`
- `sectionTitle`
- `charStart`
- `charEnd`
- `previewText`

Every citation is created from a selected child chunk and its expanded parent context. The citation's `childChunkId` should match one selected child chunk, and its `parentChunkId` should match one expanded parent context.

`sectionTitle` is currently `null` because section extraction is not implemented yet.

## API

Request:

```bash
curl -X POST http://localhost:8080/api/v1/context/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "security policy",
    "documentIds": ["{document-id}"],
    "topK": 5,
    "contextBudgetChars": 2000
  }'
```

Response shape:

```json
{
  "query": "security policy",
  "rerankedCandidates": [],
  "selectedChildChunks": [],
  "expandedParentContexts": [],
  "citations": [],
  "finalContextText": "",
  "debugMetadata": {
    "reranker": "deterministic-heuristic",
    "appliedBudgetChars": 2000
  }
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

Prepare a document:

```bash
printf "Security policy access controls require quarterly review.\nLunch menu is posted weekly.\n" > sample.txt

curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@sample.txt;type=text/plain"

curl -X POST http://localhost:8080/api/v1/documents/{document-id}/chunks

curl -X POST http://localhost:8080/api/v1/documents/{document-id}/embed
```

Build debug context:

```bash
curl -X POST http://localhost:8080/api/v1/context/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "security policy",
    "documentIds": ["{document-id}"],
    "topK": 5,
    "contextBudgetChars": 2000
  }'
```

## How To Test

Run all tests:

```bash
mvn test
```

Run only context tests:

```bash
mvn test -Dtest='com.nexusagent.context.**.*Test'
```

## Design Defense

The important design decision is separating candidate retrieval from context construction.

Retrieval returns precise child chunks. Reranking decides which candidates look strongest. Context expansion uses `parent_chunk_id` to fetch larger context. Citation formatting keeps the selected child chunk metadata so later answers can point back to evidence.

The reranker is intentionally honest: it is deterministic and heuristic. A production system would likely use a trained cross-encoder, provider rerank API, or evaluation-tuned reranker behind the same interface.

## Known Limitations

- The reranker is not a trained model.
- The context budget is character-based, not model-token-based.
- Section/title metadata is not extracted yet.
- The final context text is for debugging and later answer construction; it is not an answer.
- No Redis cache, SSE, query API, or language model call exists in this milestone.
- Parent context selection is deterministic but simple.

## Future Improvements

- Add a real reranker behind `Reranker`.
- Use model-aware token budgeting.
- Add section and heading extraction during ingestion.
- Add context evaluation tests with representative queries.
- Add final query API and SSE flow in Milestone 6.
