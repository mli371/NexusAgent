# Milestone 2 Learning Note: Text Extraction + Parent-Child Chunking

## Problem This Milestone Solves

Milestone 1 stored raw files and document metadata. Milestone 2 turns supported raw documents into structured text chunks that later retrieval stages can use.

This milestone does not answer questions and does not search documents. It prepares the retrieval data model.

The key design is parent-child chunking:

- Child chunks are smaller and precise. They are the future embedding and retrieval units.
- Parent chunks are larger. They are the future context-expansion units used when constructing answers with citations.

## What Was Built

This milestone adds:

- `DocumentTextExtractor` interface
- Text/Markdown extractor for UTF-8 content
- `parent_chunks` table
- `child_chunks` table
- Parent-child chunking algorithm
- Chunking status transitions: `STORED`, `CHUNKED`, and `CHUNKING_FAILED`
- `POST /api/v1/documents/{documentId}/chunks`
- `GET /api/v1/documents/{documentId}/chunks`
- Tests for extraction, parent splitting, child sliding windows, relationships, short documents, empty extracted text, long paragraphs, idempotency, force regeneration, and status updates

## Main Classes And Responsibilities

`DocumentTextExtractor`

Defines the extraction boundary. It lets the system add PDF, Word, HTML, or OCR extractors later without changing the chunking service contract.

`PlainTextDocumentTextExtractor`

Supports `text/plain`, Markdown-like content types, `.txt`, `.md`, and `.markdown`. It reads the original object from MinIO through `ObjectStorageService`, decodes UTF-8 text, strips a UTF-8 BOM if present, and normalizes line endings.

`DocumentTextExtractionService`

Chooses the first extractor that supports the document. If none supports the document, it returns a clear bad-request error.

`ParentChildChunker`

Creates parent chunks and child chunks. Parent chunks are paragraph-aware context blocks. Child chunks are smaller overlapping character windows inside each parent.

`DocumentChunkingService`

Coordinates the use case:

1. Load document metadata from PostgreSQL.
2. If chunks already exist and `force=false`, return them unchanged.
3. Extract text from the raw MinIO object when chunks need to be created.
4. Reject blank extracted text.
5. Generate parent and child chunks.
6. Replace stored chunks only when chunks are missing or `force=true` requests regeneration.
7. Mark the document `CHUNKED` after success or `CHUNKING_FAILED` after a chunking failure when practical.

`ChunkRepository`

Persists and reads `parent_chunks` and `child_chunks` using R2DBC `DatabaseClient`.

`DocumentChunkController`

Exposes the chunking APIs.

## Data Flow

```text
POST /api/v1/documents/{documentId}/chunks
  -> DocumentChunkController
  -> DocumentChunkingService
  -> DocumentRepository
  -> ChunkRepository checks for existing chunks
  -> if chunks exist and force=false, return existing chunks
  -> otherwise DocumentTextExtractionService
  -> PlainTextDocumentTextExtractor
  -> ObjectStorageService reads MinIO object
  -> ParentChildChunker
  -> ChunkRepository transactionally replaces chunks
  -> API response
```

Inspecting chunks uses:

```text
GET /api/v1/documents/{documentId}/chunks
  -> DocumentChunkController
  -> DocumentChunkingService
  -> ChunkRepository
  -> API response
```

## Database Shape

Milestone 2 adds two tables.

`parent_chunks`:

```text
id
document_id
chunk_index
text
char_start
char_end
token_count
created_at
```

`child_chunks`:

```text
id
document_id
parent_chunk_id
chunk_index
text
char_start
char_end
token_count
created_at
```

`child_chunks.parent_chunk_id` points to `parent_chunks.id`. Both tables also store `document_id` so document-scoped reads are simple and indexed.

## Why Parent-Child Chunking

Small chunks are better for precise matching. If a future vector search embeds huge sections, the embedding can become too broad. Child chunks keep retrieval focused.

Large chunks are better for answer context. If a future answer builder only receives tiny child snippets, it may lose surrounding meaning. Parent chunks preserve the larger context around a precise child hit.

The future retrieval flow should look like:

```text
query
  -> retrieve matching child chunks
  -> map child hits to parent chunks
  -> build context from parent chunks
  -> cite source document/chunk metadata
```

Milestone 2 stops before embeddings and retrieval. It only prepares the chunk structure.

## Chunking Behavior

Parent chunks:

- Paragraph-aware.
- Try to pack nearby paragraphs into larger context blocks.
- Split very long paragraphs if they exceed the parent character limit.

Child chunks:

- Created inside each parent chunk.
- Smaller than parent chunks.
- Use overlap so a sentence or concept near a boundary is less likely to be lost.

Defaults:

```text
nexus.chunking.parent-max-chars=1200
nexus.chunking.child-max-chars=400
nexus.chunking.child-overlap-chars=80
```

Token count is currently an approximate whitespace count. It is useful for inspection, but it is not a model-token count.

## Idempotency And `force=true`

`POST /api/v1/documents/{documentId}/chunks` is idempotent by default. If parent and child chunks already exist for the document, the service returns the stored chunks and does not extract, split, delete, or insert anything. The existing `parent_chunk_id`, `child_chunk_id`, and chunk `created_at` values remain unchanged.

If a document already has chunks but still has an older `STORED` status, the service can repair the status to `CHUNKED` without changing the stored chunks. A repeated call for an already `CHUNKED` document is a no-op for chunk rows and status.

Stable chunk IDs matter because later milestones will attach more data to them:

- Milestone 3 embeddings will reference `child_chunk_id`.
- Retrieval results will reference `child_chunk_id` and `parent_chunk_id`.
- Citations will point back to chunk IDs.
- Redis retrieval cache entries may cache chunk IDs.

When a caller intentionally wants to rebuild chunks, it can call:

```bash
curl -X POST "http://localhost:8080/api/v1/documents/{document-id}/chunks?force=true"
```

The force path deletes old child chunks and parent chunks for that document, then inserts the regenerated chunks. That delete-and-insert sequence is inside `ChunkRepository.replaceChunks`, which has a Spring transaction boundary through `@Transactional`.

Use `force=true` when the source document changed, the extraction logic changed, or the chunking configuration changed. It may create new chunk IDs, so later milestones will need to treat force regeneration as invalidating downstream embeddings and cached retrieval results.

## Blocking Dependency Trade-Off

Reading the raw object from MinIO uses the blocking MinIO Java client. This remains isolated in `MinioObjectStorageService`, which schedules the work on Reactor `boundedElastic`.

The extraction implementation itself is simple UTF-8 decoding for text files. Future PDF or Word extractors may also be blocking and should follow the same isolation pattern.

## APIs

Extract and chunk:

```bash
curl -X POST http://localhost:8080/api/v1/documents/{document-id}/chunks
```

Regenerate chunks explicitly:

```bash
curl -X POST "http://localhost:8080/api/v1/documents/{document-id}/chunks?force=true"
```

Inspect chunks:

```bash
curl http://localhost:8080/api/v1/documents/{document-id}/chunks
```

The default `POST` operation returns existing chunks when they are already present. This keeps chunk IDs stable for future embeddings, retrieval, citations, and cache entries. `force=true` is the explicit escape hatch for regeneration.

## How To Run

Start dependencies:

```bash
docker compose up -d
```

Run the app:

```bash
mvn spring-boot:run
```

Upload a supported document:

```bash
printf "# Handbook\n\nEmployees can search internal policies.\n" > handbook.md
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@handbook.md;type=text/markdown"
```

Use the returned document ID:

```bash
curl -X POST http://localhost:8080/api/v1/documents/{document-id}/chunks
curl http://localhost:8080/api/v1/documents/{document-id}/chunks
curl -X POST "http://localhost:8080/api/v1/documents/{document-id}/chunks?force=true"
```

Run tests:

```bash
mvn test
```

## Design Defense

Use this concise explanation for Milestone 2:

> I added the ingestion stage that turns stored raw text files into retrieval-ready chunks. The extractor boundary supports text and Markdown now, and it can be extended for PDF or Word later. The chunking strategy creates larger parent chunks for context expansion and smaller overlapping child chunks for precise retrieval. I persisted both levels in PostgreSQL with explicit parent-child relationships, but I did not implement embeddings or retrieval yet.

Key points to defend:

- `DocumentTextExtractor` is the extension point for future formats.
- Child chunks are the future embedding/retrieval unit.
- Parent chunks are the future context-construction unit.
- The algorithm is deterministic, local, and covered by unit tests.
- Default chunking is idempotent so existing chunk IDs stay stable for future embeddings, retrieval, citations, and cache entries.
- `force=true` is explicit regeneration and runs through the repository replacement transaction.
- MinIO reads are blocking and isolated on `boundedElastic`.

## Known Limitations

- Only UTF-8 text and Markdown-like files are supported.
- PDF and Word extraction are not implemented.
- Milestone 2 itself does not create embeddings.
- No vector search or keyword retrieval is implemented.
- No RRF, reranking, context construction, query API, SSE, Redis state/cache, or agent workflow is implemented.
- Token counts are approximate whitespace counts.
- Chunk sizes are character-based, not model-token-based.
- `force=true` replacement has no chunk-version history or audit trail yet.
- Future embeddings and retrieval caches will need invalidation when `force=true` regenerates chunk IDs.

## Future Improvements

- Add PDF and Word extractors.
- Add extraction status fields and failure reasons.
- Add model-aware token counting.
- Add chunk versioning or an ingestion-job table for richer production auditability.
- Use Milestone 3 child chunk embeddings as the semantic retrieval input.
- Add retrieval over child chunks and parent context expansion in later milestones.
