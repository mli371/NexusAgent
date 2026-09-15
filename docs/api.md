# API Reference

Base URL for local development:

```text
http://localhost:8080
```

## Enterprise Headers

Exception for the optional Agent Harness: `/api/v1/agent/runs` and its read/approval/cancel/stream routes require both tenant and actor headers explicitly. There is no missing-header fallback for those new APIs. See [Agent Harness API and internal protocol](agent-harness.md#public-api). The earlier APIs below retain their local-demo defaults.

Most document, retrieval, context, query, and agent endpoints accept:

```text
X-Tenant-Id: tenant-a
X-Actor-Id: actor-1
X-Trace-Id: trace-123
```

`X-Trace-Id` is used by query and agent flows when supplied. Missing tenant/actor headers fall back to `default` and `anonymous` for local demos. This is not production authentication.

The default tenant/actor behavior is for local demos only. A production deployment should require verified identity from OAuth/JWT claims, an API gateway, or another trusted authentication layer before setting tenant and actor context.

## Health

```bash
curl http://localhost:8080/api/v1/health
```

Returns app status and timestamp.

## Documents

Upload a raw document:

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -F "file=@examples/security-handbook.md;type=text/markdown"
```

Upload a private document inside the tenant:

```bash
curl -X POST "http://localhost:8080/api/v1/documents?visibility=PRIVATE" \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -F "file=@examples/security-handbook.md;type=text/markdown"
```

List documents:

```bash
curl "http://localhost:8080/api/v1/documents?limit=50&offset=0"
```

Get one document:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}
```

List ingestion jobs for a document:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/ingestion-jobs \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1"
```

## Chunking

Create chunks idempotently:

```bash
curl -X POST http://localhost:8080/api/v1/documents/{documentId}/chunks
```

Force regeneration:

```bash
curl -X POST "http://localhost:8080/api/v1/documents/{documentId}/chunks?force=true"
```

Inspect chunks:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/chunks
```

## Embeddings

Embed child chunks:

```bash
curl -X POST http://localhost:8080/api/v1/documents/{documentId}/embed
```

Inspect embedding status:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/embedding-status
```

## Retrieval Debug

```bash
curl -X POST http://localhost:8080/api/v1/retrieval/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "document handling security",
    "documentIds": ["{documentId}"],
    "topK": 5
  }'
```

Returns vector candidates, full-text candidates, and RRF-fused candidates.

## Context Debug

```bash
curl -X POST http://localhost:8080/api/v1/context/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "document handling security",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000
  }'
```

Returns reranked candidates, selected child chunks, expanded parent contexts, citations, and final context text.

The current allocator is `child-first-v1`: deduplicate parents using their highest-ranked child, reserve each complete child before any expansion, then share remaining body characters across child-centered parent windows. If a child cannot fit, skip it and continue considering later candidates. No citation represents a partially included child. An extremely small positive budget can therefore produce empty evidence even with retrieval candidates.

`debugMetadata` includes `allocationStrategy`, `representativeChildCount`, `reservedChildChars`, `trimmedParentCount`, and `allocations` in addition to existing counts/budgets. Each allocation records document/filename/parent/child IDs, `rerankedRank`, `childChars`, `parentChars`, `allocatedChars`, `parentTruncated`, and one status: `INCLUDED`, `DUPLICATE_PARENT`, or `CHILD_EXCEEDS_REMAINING_BUDGET`. These describe this candidate set, not library-wide recall. Live query exposes them only inside debug `contextDebug`; stage summaries contain counts, not evidence text.

## Query

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -H "X-Trace-Id: trace-demo-1" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "demo-session",
    "question": "What does the security policy say about document handling?",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": false
  }'
```

`debug=false` returns public response fields: `traceId`, `answer`, and `citations`.

`debug=true` additionally returns context/debug fields, limitations, and retrieval cache status.

## SSE Query

```bash
curl -N -X POST http://localhost:8080/api/v1/query/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "sessionId": "demo-session",
    "question": "What does the security policy say about document handling?",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": false
  }'
```

Events include `received`, `retrieving`, `reranking`, `building_context`, `generating`, `message`, `completed`, and `error`.

This endpoint is `POST` because it accepts a JSON body. Browser `EventSource` normally uses `GET`, so browser-only clients need an adapter or a GET-specific endpoint.

## Agent Query

```bash
curl -X POST http://localhost:8080/api/v1/agent/query \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "demo-session",
    "question": "What does the security policy say about document handling?",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": true
  }'
```

Returns answer, citations, workflow status, and debug-only plan/execution/critique details.

## Optional Diagnostic Runs

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/api/v1/agent/runs` | 202 with persisted run identity; optional `Idempotency-Key` |
| GET | `/api/v1/agent/runs/{runId}` | Current status and accepted report, scoped to creating tenant/actor |
| GET | `/api/v1/agent/runs/{runId}/events?afterSequence=0` | Ordered durable events with cursor; not SSE |
| POST | `/api/v1/agent/runs/{runId}/approvals/{approvalId}` | `{ "decision": "APPROVE" }` or `REJECT`; originating tenant/actor only; repeated identical decision is idempotent |
| POST | `/api/v1/agent/runs/{runId}/cancel` | Idempotent cancellation request; inspect status and cancellationPending, not just HTTP 200 |
| GET | `/api/v1/agent/runs/{runId}/events/stream` | SSE from durable event log; Last-Event-ID takes precedence over afterSequence |
| GET | `/api/v1/agent/runs/{runId}/tools?limit=25&offset=0` | Paginated tool summaries; limit 1..50; non-negative offset |
| GET | `/api/v1/agent/runs/{runId}/tools/{observationId}` | Allowlisted, bounded input/output for one observation in this run |

Disabled by default. Request is `{ "question": "Inspect processing status", "documentIds": ["<document UUID>"] }`. Modes are explicitly `scripted` or `pi`. This is separate from the deterministic `/api/v1/agent/query` API. Run GET includes `approvals`, `actionResults`, and `pendingApproval` while waiting. Approval queues execution; it does not promise action success. Default expiry is 30 minutes. Stale actions become FAILED/DOCUMENT_STATE_CHANGED; already completed work is SKIPPED. See [setup and approval examples](agent-harness.md).

Phase 3 adds `recoveryCount`, `cancellationRequested` and `cancellationPending` to run views.
Unknown writes remain RECOVERY_REQUIRED until their exact job/current state can confirm an outcome.
No public force-recovery endpoint exists. GET SSE emits persisted sequence IDs, safe event payloads,
runId and traceId; terminal history closes the stream. Invalid/future cursors return 400 before streaming.
After headers, failures emit a sanitized error event without an ID and close the stream. Native browser
EventSource cannot attach our demo identity headers; use curl/fetch. See [Chinese commands and semantics](review/agent-harness-phase-3.md).

The learning workbench adds `question` and `documentIds` to the owned run GET response for restoring a task view. Run GET and successful tool-observation responses use `Cache-Control: no-store`. All diagnostic-run APIs require explicit tenant and actor headers, unlike legacy demo endpoints with defaults. Ownership and current visibility of every scoped document are checked before returning observations. A foreign run/actor, revoked document, or observation belonging to a different run returns 404.

Tool summary responses include `schemaVersion`, `runId`, `traceId`, `tools`, `hasMore`, and `nextOffset`. Each summary includes `observationId`, `invocationId`, `toolName`, `attempt`, `retryOf`, `status`, `createdAt`, and `finishedAt`. Summary SQL does not fetch input/output JSON.

Tool details add `arguments`, `result`, `payloadOmitted`, and `omittedFields`. Only explicit fields from `inspect_document`, `list_ingestion_jobs`, and `propose_retry` are projected. No raw documents, chunks, embeddings, claim tokens, worker tokens, arbitrary nested payloads or exception messages are added. There are at most ten job summaries; strings over 1024 characters are omitted; a detail exceeding 32 KiB omits its payloads as whole JSON values rather than cutting invalid JSON. This is a reviewed field projection, not general-purpose DLP: user/model-supplied allowed strings may still contain sensitive data.

```bash
curl --fail-with-body "http://localhost:8080/api/v1/agent/runs/$RUN_ID/tools?limit=25&offset=0" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
curl --fail-with-body "http://localhost:8080/api/v1/agent/runs/$RUN_ID/tools/$OBSERVATION_ID" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

See [learning workbench](learning-workbench.md). No filesystem-reading API or worker-protocol change was added.

## Real Provider Preparation (Phase 1)

`GET /api/v1/query/capabilities` returns the active answer generator, answer model, embedding provider/model/dimension, adapter availability and `liveQueryReady`. Readiness means the live pipeline is configured, not that credentials/account/model availability have been probed or that every document is embedded. It is false in default offline mode. No key, provider base URL or environment dump is returned.

Embedding status adds `matchingChildChunkCount` and `mismatchedChildChunkCount`. `embeddedChildChunkCount` counts all existing vectors; `missingChildChunkCount` counts absent vectors. `complete` requires a nonempty child set with every vector matching the active provider/model/dimension.

Ordinary `POST /api/v1/documents/{id}/embed` fills missing rows but returns 409 `EMBEDDING_MODEL_MISMATCH` if existing vectors use another model. For an explicitly confirmed replacement:

```bash
# Modifies vectors and, with provider=openai, sends child text to OpenAI and incurs usage.
curl --fail-with-body -X POST "http://localhost:8080/api/v1/documents/$DOC_ID/embed?replaceExisting=true" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

Replacement creates a synchronous REEMBED job, keeps chunk IDs/text/indexes/offsets unchanged, and atomically commits generated vectors after an access and child-snapshot recheck. It is limited to 256 children and 1,000,000 total Java characters. Changing chunks during generation returns 409 `DOCUMENT_CHANGED`. Pi's approved EMBED_MISSING path never sets replaceExisting.

Provider failures use safe error bodies with `code` and `traceId`: `MODEL_REQUEST_REJECTED` / `INVALID_MODEL_RESPONSE` / `MODEL_OUTPUT_LIMIT` / `MODEL_RESPONSE_INCOMPLETE` / `MODEL_CONNECTION_OR_RESPONSE_ERROR` (502), `MODEL_RATE_LIMITED` / `MODEL_UNAVAILABLE` (503), and `MODEL_TIMEOUT` (504). `MODEL_OUTPUT_LIMIT` identifies a Responses output-token-limit termination; `MODEL_RESPONSE_INCOMPLETE` identifies other incomplete responses. Neither returns a partial answer or automatically retries. Other answer validation failures expose an application-owned reason such as `CITATION_SET_MISMATCH` or `INVALID_JSON`, never raw model text. Existing local-demo header defaults still apply to document endpoints; they are not production authentication.

See the [Phase 1 learning note](learning/rag-01-real-providers.md) for configuration and limitations.

## Live Query REST/SSE (Phases 2–3)

The subsequent page-local follow-up extension adds optional `history` to the request below. It does not add a new endpoint or server-side chat persistence. Each entry is `{ "question": "previous standalone question", "answer": "bounded answer excerpt", "answerTruncated": false }`, ordered oldest first. At most three entries are accepted; each question must be 1–2000 characters and each answer at most 1000 characters. Missing/null history means empty. Wrong field types, unknown entry fields (including role), null entries and excessive lengths/counts are rejected with 400 before model work. `answerTruncated` is optional and defaults to false. Nonempty history in offline mode returns 400 `MULTI_TURN_UNAVAILABLE`.

After normal access/readiness preflight, history triggers one structured resolution-model call. No history skips it. `unchanged` or `rewritten` results supply the effective question used by both caches, retrieval and final answer generation. `needs_clarification` is a normal 200 response with that `answerStatus`, a short clarification in `answer`, empty citations/context, `retrievalCacheStatus=bypassed` when debug is enabled, and all downstream stages skipped. A resolver refusal also skips downstream work and uses `answerStatus=refused`. Scope counts still describe the preflight ready-document snapshot, not retrieval actually executed on these terminal paths. Historical answers are untrusted reference hints; they are not inserted into the evidence prompt or persisted as conversation memory.

With debug enabled, `queryResolution` adds `originalQuestion`, nullable `resolvedQuestion`, `status`, `reasonCode`, `historyTurnsUsed`, `modelCalled`, nullable `resolverModel`, and `resolverVersion`. It contains no history excerpts or hidden reasoning. It is absent with debug=false. Capabilities now include `pageFollowUpSupported` and `maxHistoryTurns=3`; the learning frontend requires support before enabling submission. This flag is configuration, not a model-quality or API-availability probe.

Explicitly configure `NEXUS_ANSWER_PROVIDER=openai` and `NEXUS_EMBEDDINGS_PROVIDER=openai` to route only `/api/v1/query` and `/api/v1/query/stream` through `LiveQueryService`. `/api/v1/agent/query` stays the earlier deterministic example; Pi `/agent/runs` remains document management. Neither participates in live QA. The earlier Query examples in this document describe **default offline mode** unless this mode is enabled.

Request fields: `sessionId`, `question`, `scope`, `documentIds`, `topK`, `contextBudgetChars`, `debug`. Live mode accepts 1–2000 question characters. `scope=library` requires absent/empty documentIds and resolves an access-filtered, current-model-ready snapshot; unready documents are excluded from both vector and full-text search. More than 200 accessible documents returns 400 `LIBRARY_SCOPE_TOO_LARGE`; zero ready documents returns 409 `LIBRARY_NOT_READY`, both before any model call. This is not silent pagination. `scope=documents` requires 1–10 non-null IDs, all accessible and ready; duplicate IDs collapse but the input list itself cannot exceed ten. Omitted scope infers documents for a non-empty ID list, otherwise library. Unknown scopes or contradictory scope/IDs return 400.

Defaults are topK=10 and parent-text budget=4000 (the learning UI starts at topK=5). Allowed configured maxima are 50/12000; live mode rejects out-of-range values instead of clamping. Session/trace IDs accept at most 120 letters/digits or `. _ : -`. Missing session/trace IDs get a request UUID; missing identity headers still mean default/anonymous for local demo only. The old offline pipeline retains its earlier semantics; the new UI requires live mode and both scopes in capabilities.

Public response: `traceId`, `answer`, `citations`, `answerStatus` (`answered`, `insufficient_context`, `refused`, `needs_clarification`), `answerProvider`, `answerModel`, `scope`. Scope includes `mode`, `accessibleDocumentCount`, `searchedDocumentCount`, `excludedDocumentCount`, `exclusions` (reason counts: CHUNKING_REQUIRED / EMBEDDING_INCOMPLETE / EMBEDDING_MODEL_MISMATCH). In documents mode counts refer to the selected scope; inaccessible documents never enter counts. Provider/model describe the configured answer backend; empty-context abstention or question clarification can skip that backend entirely (visible as a skipped stage in debug). Only citations actually used in the validated answer are returned. The model does not supply document/chunk UUIDs or filenames.

The answer schema limits `usedCitationMarkers` to the current context's exact bracketed markers. Inline comma-separated groups such as `[C1, C2]` or `[C1，C2]` are canonicalized to `[C1][C2]` only when all members are allowed. Inline and declared marker sets must still match; bare IDs, duplicate declarations, ranges and unknown references are not repaired or silently dropped. This is reference consistency validation, not factual entailment checking. Safe answer logs contain trace ID, application-owned rejection reason, whitelisted response status and numeric token counts, not model output or evidence.

With debug=true: additionally `finalContextText`, `retrievalDebug`, `contextDebug`, `limitations`, `retrievalCacheStatus` (`hit` for exact, `semantic_hit`, `miss`, `bypassed`), `stages`. Evidence and scores remain server-derived; on cache hits they originate from the validated cache entry, not newly run retrieval. On a semantic hit, historical distances/ranks/reranker scores belong to the source query even though the response's query field is restored to the current question. These fields are absent when debug=false. Parent body budget excludes citation formatting overhead; complete external question/context input is independently capped at 64 KiB UTF-8.

Live SSE is POST/fetch/curl, not browser GET EventSource. Events:

```text
received
stage (access_check running/succeeded; embedding_readiness running/succeeded)
stage (query_resolution running/succeeded, or skipped with no_history)
stage (cache_lookup running/succeeded, or skipped when disabled)
stage (eligible semantic path: query_embedding -> semantic_cache_lookup)
stage (cache miss: vector_search in parallel with full_text_search)
stage (rrf_fusion -> reranking -> child_selection -> parent_expansion -> context_building)
stage (answer_generation running/succeeded, or skipped if no evidence)
stage (citation_validation running/succeeded)
message (entire validated answer)
completed (QueryResponse)
```

Each envelope contains `type` and the same `traceId`. A stage envelope adds `stage: {sequence, stage, attempt, status, timestamp, durationMs, summary?}`. Sequence is request-local and increasing; attempt=1 describes the business stage, not internal embedding HTTP retries. Failures/cancellations are observed at actual boundaries. Concurrent search completion order is not guaranteed. `summary` is debug-only, capped at 16 KiB and contains counts/status/model, not raw evidence. Rank/preview details arrive only in the final authorized debug response. Maximum 48 stage records and 64 replay-buffer events per request; this is ephemeral buffering, not durable SSE replay.

Input/access/readiness errors occur before any SSE body. They return HTTP 400/404/409 with safe error code/trace ID. After streaming starts, failure yields `error` with safe `message`, `code`, `traceId`, and no `message`/`completed` answer events. Never automatically repeat a POST after disconnect; use a new explicit request. Cancellation propagates to local I/O but cannot guarantee provider cancellation or zero usage.

Resolution failures use `INVALID_QUERY_REWRITE_RESPONSE` (502), `QUERY_REWRITE_REJECTED` (502), `QUERY_REWRITE_UNAVAILABLE` (503) or `QUERY_REWRITE_TIMEOUT` (504). They never silently use the original incomplete question or fall back to a template. `NEXUS_QUERY_REWRITE_TIMEOUT` defaults to 20s and must be positive and at most 60s. Resolver input JSON is bounded to 64 KiB UTF-8, output to 1200 model tokens. There is no automatic paid retry. Structured output constrains format, not semantic correctness. Clarification is `message` then `completed`, not an error event.

Error codes: `INVALID_QUERY` (400), `DOCUMENT_NOT_ACCESSIBLE` (404), `CHUNKING_REQUIRED`, `EMBEDDING_INCOMPLETE`, `EMBEDDING_MODEL_MISMATCH`, `DOCUMENT_CHANGED` (409), provider codes above (502/503/504), `QUERY_TIMEOUT` (504), `QUERY_UNAVAILABLE` (503). Provider errors never become successful template responses. Empty context skips the answer call; a context-stage timeout is an error, not empty evidence.

Live context caching is enabled when both Redis and `NEXUS_LIVE_CACHE_ENABLED` are enabled. `retrieval:live-context-v1:{sha256}:context` keys include identity, exact trimmed query, request scope, sorted resolved document IDs/revisions, effective topK/budget, embedding identity and ranking/context version. Default TTL is 15 minutes, value cap 128 KiB, Redis operation timeout 500ms. Values contain schema version, key, timestamp and bounded context/retrieval data; question fields are blank in storage and restored from the current request. No answer, current trace, session history, or raw upload is stored in this cache. Hits still recheck current access, evidence and revisions, and still generate an answer. Empty or oversized results are not cached. Redis failure becomes a miss or ignored write; access denial does not. Old version entries expire without reuse.

On exact hit, query_embedding, vector_search, full_text_search, rrf_fusion, reranking, child_selection, parent_expansion and context_building emit `skipped` with debug summary `reason=cache_reuse`, duration 0 (not measured execution). semantic_cache_lookup is skipped with `exact_cache_hit`. On semantic hit query_embedding actually executes; only the seven retrieval/context stages are skipped. Disabled or lexically ineligible semantic queries skip semantic_cache_lookup and retain the previous concurrent query-embedding/vector and full-text path. cache_lookup reports exact hit/miss; semantic_cache_lookup reports its own hit/miss, reason, threshold, candidateCount and optional similarity. No cached stage events are replayed.

The coverage-first policy version participates in both exact keys and semantic scopes. Live and offline context envelopes now require schemaVersion=2; the offline key descriptor is also versioned. Old allocations expire under their original TTL and are not reused or silently relabeled. No Redis-wide deletion is required. Cached allocation diagnostics describe the source context; they do not mean selection ran again for the current request.

For follow-ups, every question-derived cache input above uses the resolved standalone question, not the short original phrase. History is neither serialized into the context cache nor itself a key component: different histories resolving to the same question can reuse context in the same access/version/settings scope. Different resolved questions have different exact keys. The bounded semantic policy can still allow approximate reuse; resolution does not prove semantic equivalence. A context hit saves retrieval work, not the follow-up resolution or final answer call.

`NEXUS_SEMANTIC_CACHE_ENABLED=true` adds a bounded source ZSET per access/version/settings scope, not a new vector database. Default max entries is 100, source JSON cap 16 KiB, and similarity threshold 0.96. Scope includes tenant/actor, resolved document revisions, model, request parameters and algorithm/policy version, but not question text. The bucket contains query vectors, hashed question/constraint features and pointers/fingerprints to exact contexts; no question plaintext or duplicated parent body. Sources expire no later than their original contexts. Only successful fresh context writes seed sources; semantic hits never promote into exact keys or new sources. Redis faults degrade to normal retrieval with the already generated question vector. Lexical guards reject number/year, recognized negation and classified-intent conflicts; unknown or complex queries bypass semantic reuse. This does not prove semantic equivalence or context sufficiency.

Capabilities advertise `retrievalCacheMode=versioned_semantic_context` when both caches are configured, `versioned_context` for exact-only, or `bypassed` when context caching is disabled. This is configuration, not a Redis connectivity probe. Changes to documents during a request can still return `DOCUMENT_CHANGED` rather than automatically retrying a potentially billed answer. See [semantic cache configuration and validation](learning/semantic-context-cache.md).

Session/status/tool storage uses a `live-` SHA-256 scope of length-prefixed tenant, actor and session/trace identifier. Public trace IDs remain in HTTP/SSE/audit and bounded tool summaries. Redis envelope identifiers are scoped storage IDs, not necessarily the public trace ID. State, tool and audit failures/timeouts are best-effort (default 2s per operation), never grounds for retrying a paid answer.

Capabilities additionally advertise `queryScopes: ["library", "documents"]` and `maxLibraryDocuments: 200`. Scope resolution is request-local: newly ready/uploaded documents join the next request; selected evidence is rechecked before egress and final output. This is not a serializable authorization snapshot.

Manual activation, browser workflow and both curl examples: [Chinese Phase 3 learning note](learning/rag-03-learning-frontend.md). The original Phase 3 validation was offline; a later [bounded live citation-fix check](learning/rag-answer-citation-validation-fix.md) documents the separately authorized real requests and their limitations.

## Actuator Health

Safe local health/info endpoints:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/info
```

## Validation Errors

Common bad-request cases:

- Blank `question` or `query`.
- `topK < 1`.
- `contextBudgetChars < 1`.
- Unsupported document type during extraction.
- Empty extracted text.
- Invalid tenant/actor header characters.
- Invalid `visibility` value.

Unexpected errors return a generic server error response instead of leaking low-level details.
