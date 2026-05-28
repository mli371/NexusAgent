# Milestone 7: Redis State And Cache

## What This Milestone Solves

Milestone 7 replaces the placeholder query state/cache behavior from Milestone 6 with Redis-backed implementations.

Redis is used only for short-lived state and cache:

- recent session lifecycle events
- session summaries
- query status
- retrieval/context cache entries
- temporary tool outputs

Redis is not the source of truth. PostgreSQL remains the source of truth for document metadata, chunks, and embeddings. MinIO remains the source of truth for raw uploaded files.

## Main Classes And Responsibilities

- `NexusRedisProperties`: configures Redis enablement, TTLs, and value-size limits.
- `RedisKeyFactory`: builds stable Redis keys and retrieval query hashes.
- `RedisSessionStateService`: stores short-lived session events, session summaries, and query status.
- `RedisRetrievalCacheService`: caches context build results for repeated equivalent queries.
- `RedisToolOutputStore`: stores temporary intermediate outputs with TTL and size limits.
- `InMemorySessionStateService`: local fallback when Redis is disabled.
- `NoOpRetrievalCacheService`: cache fallback when Redis is disabled.
- `NoOpToolOutputStore`: tool-output fallback when Redis is disabled.

The public interfaces from Milestone 6 remain stable:

- `SessionStateService`
- `RetrievalCacheService`
- `ToolOutputStore`

## Redis Key Design

The Redis key format is explicit and human-readable:

```text
session:{sessionId}:recent
session:{sessionId}:summary
retrieval:{queryHash}:candidates
tool:{sessionId}:{toolCallId}:result
query:{traceId}:status
```

For tool outputs, the query pipeline uses `sessionId` when present. If no session ID is supplied, it falls back to `traceId`.

The retrieval cache key uses a SHA-256 hash of a canonical query descriptor that includes:

- normalized question text
- sorted document IDs
- effective `topK`
- effective `contextBudgetChars`
- retrieval settings such as `rrfK`
- context settings such as default and max budget
- cache schema identifier

This prevents cache reuse across materially different retrieval settings.

## Serialization

Redis values are JSON envelopes. Each envelope includes `schemaVersion`.

Examples:

- retrieval cache envelope: `schemaVersion`, `cachedAt`, `context`
- session event envelope: `schemaVersion`, `traceId`, `status`, `detail`, `timestamp`
- tool output envelope: `schemaVersion`, `savedAt`, `valueType`, `value`

The schema version gives the project a way to evolve serialized Redis values later without silently misreading old data.

## TTL Strategy

Defaults:

```text
recent session events: 24 hours
session summary: 7 days
retrieval cache: 30 minutes
tool outputs: 2 hours
query status: 30 minutes
```

Why they differ:

- Recent session events are useful for short local continuity, but they should not live forever.
- Session summaries can last longer because they are smaller and less detailed.
- Retrieval cache entries are short-lived because documents can be re-chunked or re-embedded.
- Tool outputs are temporary intermediate data, not durable workflow records.
- Query status is useful shortly after a request, then becomes noise.

Every Redis write uses a positive TTL. Recent session events use a Redis list plus key expiration, while summary, query status, retrieval cache, and tool-output values are written with expiring `SET` operations. Non-positive Redis TTL and size-limit settings are rejected during configuration binding instead of creating persistent or unbounded keys.

## Value Size Limits

Redis is not used as a raw document store.

The implementation skips values above configured limits:

- `NEXUS_REDIS_MAX_CACHE_ENTRY_BYTES`
- `NEXUS_REDIS_MAX_TOOL_OUTPUT_BYTES`

This avoids putting large raw document content or unbounded parent context blobs into Redis. Context is already budgeted by the context builder, and Redis adds an extra storage bound.

## Failure Behavior

Redis failure should not break query execution.

- Cache read failure becomes a cache miss.
- Cache write failure is logged and ignored.
- Session state write failure is logged and ignored.
- Query status write failure is logged and ignored.
- Tool output read failure returns empty.
- Tool output write failure is logged and ignored.

This design keeps Redis as an optimization. It does not become a hard dependency for serving a query.

When query `debug=true`, the response includes `retrievalCacheStatus`:

- `miss`: the query built fresh context and attempted to cache it.
- `hit`: the query reused cached context.

The field is omitted when `debug=false`.

## How To Configure

Redis is enabled by default for local development:

```text
NEXUS_REDIS_ENABLED=true
REDIS_HOST=localhost
REDIS_PORT=6379
```

Disable Redis-backed query state/cache and use the Milestone 6 fallback implementations:

```text
NEXUS_REDIS_ENABLED=false
```

## How To Run

Start local services:

```bash
docker compose up -d
```

Verify Redis:

```bash
docker compose exec redis redis-cli ping
```

Run the app:

```bash
mvn spring-boot:run
```

Run tests:

```bash
mvn test
```

Run Redis-focused tests:

```bash
mvn test -Dtest='com.nexusagent.query.redis.*Test'
```

## Design Defense

Redis was added after the query API because the query orchestration layer is where session state, retrieval cache, and intermediate outputs naturally belong.

The implementation keeps Redis behind interfaces. That means the query service depends on application concepts, not Redis APIs.

The cache key includes retrieval and context settings because the same user question can produce different context if `documentIds`, `topK`, budgets, or ranking settings change.

The service tolerates Redis outages because Redis is not the source of truth. A failed cache read should not prevent retrieval from PostgreSQL/PgVector, and a failed session write should not prevent a query response.

## Known Limitations

- There is no active retrieval cache invalidation when `force=true` re-chunks a document.
- Cache staleness is controlled by TTL only.
- Redis does not provide durable conversation memory.
- Redis does not store raw documents.
- There are no cache hit/miss metrics yet.
- There are no tenant-aware Redis key prefixes yet.
- Tool outputs are temporary and size-limited.
- Production security, authorization, encryption, and observability are still future work.

## Future Improvements

- Invalidate retrieval cache entries after force re-chunking or re-embedding.
- Add metrics for cache hits, misses, write skips, and Redis failures.
- Add tenant-aware key prefixes.
- Add safer redaction for sensitive tool outputs.
- Tune TTLs and size limits based on measured usage.
