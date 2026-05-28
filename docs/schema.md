# Database Schema

NexusAgent uses PostgreSQL as the source of truth for document metadata, chunk metadata, and child chunk embeddings. Raw files live in MinIO. Redis stores short-lived cache/state only.

## Migrations

| Migration | Purpose |
| --- | --- |
| `V1__create_documents_table.sql` | Enables PgVector and creates `documents`. |
| `V2__create_parent_child_chunks.sql` | Creates `parent_chunks` and `child_chunks`. |
| `V3__add_document_chunking_statuses.sql` | Adds `CHUNKED` and `CHUNKING_FAILED` document statuses. |
| `V4__create_child_chunk_embeddings.sql` | Creates `child_chunk_embeddings` with `vector(384)`. |
| `V5__add_child_chunk_full_text_index.sql` | Adds PostgreSQL full-text GIN index over `child_chunks.text`. |
| `V6__enterprise_readiness_slice.sql` | Adds document tenant fields, audit events, and ingestion jobs. |

## `documents`

Stores source-of-truth metadata for uploaded files.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key. |
| `tenant_id` | `text` | Tenant boundary for local enterprise-readiness skeleton. |
| `owner_id` | `text` | Actor that uploaded/owns the document in the skeleton model. |
| `visibility` | `text` | `PRIVATE` or `TENANT`. |
| `original_filename` | `text` | Client-provided filename. |
| `content_type` | `text` | MIME type when available. |
| `size_bytes` | `bigint` | Must be positive. |
| `sha256` | `text` | Hash of uploaded bytes. |
| `minio_bucket` | `text` | Raw file bucket. |
| `minio_object_key` | `text` | Unique object key. |
| `status` | `text` | `STORED`, `CHUNKED`, or `CHUNKING_FAILED`. |
| `created_at` | `timestamptz` | Creation timestamp. |
| `updated_at` | `timestamptz` | Last update timestamp. |

Indexes:

- `idx_documents_created_at`
- `idx_documents_status`
- `idx_documents_tenant_created_at`
- `idx_documents_tenant_status`
- `idx_documents_tenant_owner`

## `parent_chunks`

Stores larger context blocks. Parent chunks are used for context expansion after retrieval finds precise child chunks.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key. |
| `document_id` | `uuid` | References `documents(id)` with cascade delete. |
| `chunk_index` | `integer` | Parent order within document. |
| `text` | `text` | Parent chunk text. |
| `char_start` | `integer` | Global offset in extracted document text. |
| `char_end` | `integer` | Global exclusive end offset. |
| `token_count` | `integer` | Approximate whitespace token count. |
| `created_at` | `timestamptz` | Creation timestamp. |

Constraint:

- Unique `(document_id, chunk_index)`.

## `child_chunks`

Stores smaller overlapping retrieval windows. Child chunks are embedded and searched.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key. |
| `document_id` | `uuid` | References `documents(id)` with cascade delete. |
| `parent_chunk_id` | `uuid` | References `parent_chunks(id)` with cascade delete. |
| `chunk_index` | `integer` | Document-global child chunk order. |
| `text` | `text` | Child chunk text. |
| `char_start` | `integer` | Global offset in extracted document text. |
| `char_end` | `integer` | Global exclusive end offset. |
| `token_count` | `integer` | Approximate whitespace token count. |
| `created_at` | `timestamptz` | Creation timestamp. |

Constraint:

- Unique `(document_id, chunk_index)`.

Indexes:

- `idx_child_chunks_document_id`
- `idx_child_chunks_parent_chunk_id`
- `idx_child_chunks_text_fts`

## `child_chunk_embeddings`

Stores vectors for child chunks only.

| Column | Type | Notes |
| --- | --- | --- |
| `child_chunk_id` | `uuid` | Primary key; references `child_chunks(id)` with cascade delete. |
| `document_id` | `uuid` | References `documents(id)` with cascade delete. |
| `embedding` | `vector(384)` | PgVector embedding. |
| `provider` | `text` | Provider name, such as `local`. |
| `model_name` | `text` | Model/provider identifier. |
| `dimension` | `integer` | Must be `384` for current schema. |
| `created_at` | `timestamptz` | Creation timestamp. |
| `updated_at` | `timestamptz` | Last update timestamp. |

Indexes:

- `idx_child_chunk_embeddings_document_id`
- Optional HNSW cosine index if the PgVector build supports `hnsw`.

## `audit_events`

Stores bounded audit records for important lifecycle events. It does not store raw document text, full retrieved context, embeddings, or unbounded user input.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key. |
| `tenant_id` | `text` | Effective tenant from request context. |
| `actor_id` | `text` | Effective actor from request context. |
| `trace_id` | `text` | Query/agent trace or generated operation trace. |
| `event_type` | `text` | Example: `DOCUMENT_UPLOADED`, `QUERY_EXECUTED`. |
| `resource_type` | `text` | Example: `document`, `query`, `agent_query`. |
| `resource_id` | `uuid` | Resource/event correlation ID. |
| `document_id` | `uuid` | Optional related document. |
| `metadata_json` | `jsonb` | Small bounded metadata; no raw document text, full context, embeddings, or unbounded user input. |
| `created_at` | `timestamptz` | Creation timestamp. |

## `ingestion_jobs`

Stores synchronous status records for chunk/embed API calls. It is not an async worker queue yet.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key. |
| `document_id` | `uuid` | References `documents(id)` with cascade delete. |
| `tenant_id` | `text` | Tenant owning the job. |
| `job_type` | `text` | `CHUNK`, `EMBED`, `FORCE_RECHUNK`, or `REEMBED`. |
| `status` | `text` | `PENDING`, `RUNNING`, `SUCCEEDED`, or `FAILED`. |
| `error_message` | `text` | Truncated failure reason when available. |
| `started_at` | `timestamptz` | Start timestamp. |
| `finished_at` | `timestamptz` | Finish timestamp. |
| `created_at` | `timestamptz` | Creation timestamp. |
| `updated_at` | `timestamptz` | Last update timestamp. |

## Offset Semantics

`char_start` and `char_end` are global offsets in the extracted document text. `char_end` is exclusive. This makes citations easier to explain because every chunk can be traced back to a range in the extracted document.

## Source Of Truth

- PostgreSQL: document metadata, chunks, embeddings.
- MinIO: raw uploaded files.
- Redis: short-lived state/cache only.

Redis does not own durable document state.

The tenant fields are an enterprise-readiness skeleton. Production tenant isolation would require verified authentication and authorization, not caller-provided headers.
