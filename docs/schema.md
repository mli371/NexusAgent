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
| `V7__create_agent_runs.sql` | Adds durable diagnostic runs, document scope, tool observations, and ordered events. |
| `V8__agent_approvals_and_retries.sql` | Adds approvals, unique action executions, model-round reservations, typed ingestion errors and exact job/trace linkage. |
| `V9__agent_recovery_and_cancellation.sql` | Adds recovery_count, cancel_requested_at, RECOVERY_REQUIRED run status and CANCELLED pending-approval status. |
| `V10__live_context_cache_revisions.sql` | Adds transactional document retrieval revisions for live context-cache invalidation. |

## Agent Harness Tables

These tables support the optional harness. They do not change chunk-index/offset semantics or Redis records. Only explicitly approved ordinary ingestion actions may create chunks or missing child embeddings.

| Table | Main fields and constraints |
| --- | --- |
| `agent_runs` | UUID ID; tenant/actor/trace; bounded question; captured execution mode/provider/model; protocol/prompt versions; status/version; idempotency key/request hash; claim hash/attempt/lease/deadline; tool/round/event counters; recovery_count (default 0); cancel_requested_at (nullable); bounded JSON report and safe error code; timestamps |
| `agent_run_documents` | Composite primary key `(run_id, document_id)`; authoritative scope via foreign keys |
| `agent_tool_calls` | UUID observation ID; run/attempt/invocation; tool name; canonical arguments/hash; optional retry parent; status; JSON observation/error; timestamps; unique `(run_id, invocation_id)` |
| `agent_run_events` | Primary key `(run_id, sequence)`; type; bounded JSON payload; timestamp |
| `agent_approvals` | Run/document/action; canonical arguments and SHA-256 hash; state fingerprint; reason <=1,000 characters; requester/decider; PENDING/APPROVED/REJECTED/EXPIRED/CANCELLED; expiry and decision time; one PENDING per run |
| `agent_action_executions` | UUID ID; unique approval FK; run FK; PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED/UNKNOWN; safe error code and timestamps |
| `agent_model_rounds` | Composite PK `(run_id, reservation_id)` makes pre-request model-round reservation idempotent; cumulative `agent_runs.round_count` |

V8 adds nullable `error_code`, `agent_execution_id` (unique FK) and `trace_id` to
`ingestion_jobs`. Existing public ingestion jobs have no harness execution link.
Historical free-text errors remain unknown; typed codes are recorded at the
failure boundary. The model sees the code, not `error_message`.
WAITING_APPROVAL and CANCELLED extend run statuses. The APPROVED decision remains
immutable even when execution becomes FAILED/DOCUMENT_STATE_CHANGED or SKIPPED.
An approval decision and its execution outcome are separate records.

V9 adds RECOVERY_REQUIRED for uncertain reserved writes. Recovery preserves run identity and cumulative
tool/round counts; it increments recovery_count, clears the old claim/lease/deadline and queues a fresh
attempt. Cancellation records intent separately from completion. Pending approvals may become CANCELLED
without inventing a human approve/reject decision. Recovery does not rewrite historical Phase 2 FAILED rows.

Creation uniqueness is `(tenant_id, actor_id, idempotency_key)`; a null key permits another run. A partial unique index permits only one RUNNING run globally. State changes and events commit in the same short transaction. Questions/reports/reasons may be sensitive; there is no automatic purge policy. Linked document deletion is restricted to preserve provenance. Linked ingestion jobs also prevent casually deleting their execution records; coordinated retention/deletion is deferred. No hidden reasoning, credentials, raw documents, or raw job errors are intentionally persisted by the harness tables.

See [runtime/retention limitations](agent-harness.md#state-limits-and-failures). Existing Redis tool outputs are temporary; these tool observations are durable evidence in PostgreSQL.

## `documents`

Stores source-of-truth metadata for uploaded files.

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `uuid` | Primary key. |
| `tenant_id` | `text` | Tenant boundary for local enterprise-readiness skeleton. |
| `owner_id` | `text` | Actor that uploaded/owns the document in the skeleton model. |
| `visibility` | `text` | `PRIVATE` or `TENANT`. |
| `retrieval_revision` | `bigint` | Nonnegative, default 0. Triggers increment on parent/child/embedding writes and document access/filename changes, within the same transaction. |
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
