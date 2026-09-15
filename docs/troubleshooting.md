# Troubleshooting

## Learning QA Is Not Ready

- `/api/v1/query/capabilities` returns 404 or lacks `queryScopes`: restart the older Java process after updating the code. Restarting Vite alone cannot load backend classes.
- `liveQueryReady=false`: explicitly enable `NEXUS_ANSWER_PROVIDER=openai` and `NEXUS_EMBEDDINGS_PROVIDER=openai`; keep keys only in private backend configuration. The browser never falls back to a template.
- `LIBRARY_NOT_READY`: no accessible document has complete vectors for the current model. Upload/chunk/embed synthetic documents separately; older local vectors need a confirmed rebuild, not a query retry.
- `LIBRARY_SCOPE_TOO_LARGE`: this learning implementation supports 200 accessible documents in library scope. Select 1–10 documents explicitly; the server does not silently truncate.
- `EMBEDDING_MODEL_MISMATCH`: use the preparation panel's explicitly confirmed rebuild only after approving external text transmission and API usage. Ordinary Pi EMBED_MISSING does not replace another model.
- `QUERY_STREAM_INTERRUPTED`: the answer is unconfirmed. The browser does not repeat the POST because a model call may already have been billed. A manual new question is a new request, not SSE replay.

Full startup and scope rules: [Chinese QA guide](learning/rag-03-learning-frontend.md).

## Model Answer Validation Fails

- `INVALID_MODEL_RESPONSE` with `CITATION_SET_MISMATCH`: model answer citations differ from its declared `usedCitationMarkers`. This occurs in backend answer validation, not browser JSON parsing. The adapter now constrains declarations to current evidence markers and normalizes known comma-separated inline groups; it still rejects genuinely inconsistent references.
- `INVALID_MODEL_RESPONSE` with `INVALID_JSON`: the structured answer text is not valid JSON. Do not bypass validation or display unvalidated output.
- `MODEL_OUTPUT_LIMIT`: the response ended at its output-token limit. Partial text is discarded. The adapter currently requests 2000 output tokens; this budget includes reasoning tokens, so visible answer length alone does not explain it. This was not the cause of the reproduced citation mismatch.
- `MODEL_RESPONSE_INCOMPLETE`: another incomplete response; no partial answer is shown.
- Correlate the UI trace ID with `answer_response_rejected` in server logs. The diagnostic includes a safe reason, status and numeric usage only. Do not enable raw prompt/response or authorization-header logging to debug it.
- After changing Java code, restart the backend. Refreshing Vite does not reload the answer parser. No automatic paid answer retry or template fallback is performed; any manually resubmitted question is a new potentially billed request.

See the [Chinese fix, tests and live-check note](learning/rag-answer-citation-validation-fix.md).

## Live Context Cache

- `retrievalCacheMode=bypassed`: confirm both `NEXUS_REDIS_ENABLED` and `NEXUS_LIVE_CACHE_ENABLED`. Capabilities reports configured mode, not connectivity.
- `versioned_context` means exact-only; `versioned_semantic_context` additionally enables semantic lookup. An older process needs restarting to load the new code/configuration. `NEXUS_SEMANTIC_CACHE_ENABLED=false` disables only semantic reuse.
- A paraphrase can legitimately miss: inspect `semantic_cache_lookup` reason, similarity and threshold in debug mode. `ineligible_query` means the lexical policy could not identify a supported simple intent; `below_threshold` is not a provider error. `invalid_source_evidence` means the original context disappeared, changed or failed evidence checks. Do not lower the threshold simply to produce a green demo.
- Repeated `miss`: check `live_context_cache_lookup` reason and safe Redis read/write logs. Empty results and oversized values are deliberately not cached. New ready documents, identity/settings changes, re-chunking or vector updates change the key.
- `invalid_evidence` / `stale_evidence`: the cached result was rejected and context is rebuilt. Do not disable access/version checks to force hits.
- `DOCUMENT_CHANGED`: evidence/version changed during the request. No automatic model retry; resubmission may incur another model charge.
- `retrieval_revision` missing: the backend is older or V10 has not been applied. Restart the updated backend with Flyway enabled; do not edit an already applied migration.
- Hits still call the answer model and still perform database authorization/version checks. No claim of zero database traffic or zero API usage.
- CI failures and local commands: [CI guide](ci.md). Never paste `.env` or private model output into public Actions logs.

## Optional Agent Harness

For recovery, cancellation-pending, claim fencing and SSE reconnect troubleshooting,
see [the harness guide](agent-harness.md#troubleshooting) and [Chinese Phase 3 review](review/agent-harness-phase-3.md).
RECOVERY_REQUIRED means an uncertain write needs evidence from its exact linked job;
do not clear it by replaying ingestion blindly. Cancelling a stream is not cancelling a run.

## Docker Is Not Running

Symptoms:

- Testcontainers integration tests are skipped.
- `docker compose ps` fails.
- Local dependencies are unreachable.

Fix:

```bash
docker compose up -d
docker compose ps
```

## PostgreSQL Port Is Already Used

Set a different local host port in `.env`:

```text
POSTGRES_PORT=55432
```

Restart:

```bash
docker compose down
docker compose up -d postgres
mvn spring-boot:run
```

Spring Boot reads `.env`, so Flyway and R2DBC use the same port.

## Flyway Connection Refused

Check PostgreSQL health:

```bash
docker compose ps postgres
docker compose exec postgres pg_isready -U nexus -d nexusagent
```

Check `.env`:

```bash
grep '^POSTGRES_' .env
```

## Redis Disabled

Set:

```text
NEXUS_REDIS_ENABLED=false
```

The app uses in-memory/no-op fallback services. Query and agent APIs still work, but cache/state/tool-output behavior is not Redis-backed.

## Redis Keys Are Missing

Run a query with `sessionId` and `debug=true`, then inspect:

```bash
scripts/demo.sh query
scripts/demo.sh redis-keys
```

Redis writes can be skipped if values exceed configured size limits.

## MinIO Console

Open:

```text
http://localhost:9001
```

Default local credentials come from `.env.example`.

## Unsupported Document Type

Current extraction supports plain text and Markdown-like files only. Use:

- `.txt`
- `.md`
- `.markdown`
- `text/plain`
- Markdown content types

PDF and Word files are future work.

## Empty Retrieval Results

Check the document lifecycle:

```bash
scripts/demo.sh upload
scripts/demo.sh chunk
scripts/demo.sh embed
scripts/demo.sh retrieval-debug
```

Retrieval requires chunked and embedded child chunks.

## SSE With Browser Clients

The SSE endpoint is `POST /api/v1/query/stream` because it accepts a JSON request body. Browser `EventSource` normally supports `GET`; use `curl`, `fetch`, a server-side client, or add a GET adapter later.

## Learning Workbench

The separate Agent Harness uses GET `/api/v1/agent/runs/{runId}/events/stream`; its frontend uses fetch to attach tenant/actor headers and a replay cursor. It is not the POST query stream above.

- `/tools` returns 404 after updating code: restart the older Java process so the new observation controller is loaded. Then confirm the originating identity, current document visibility and matching observation/run IDs.
- Port 5173 is occupied: use `npm --prefix frontend run dev -- --port 5174`; the server does not silently switch ports.
- QUEUED without progress: start a matching scripted/Pi worker separately. Vite does not start Java or workers.
- SSE disconnected: the client retries and replays history; it does not mark the backend task failed or automatically approve anything. Use manual resync after repeated connection failure.
- Browser tests cannot find Chrome: install Google Chrome locally. Ordinary UI tests use synthetic HTTP fixtures; the separate `WorkbenchBrowserIT` additionally needs Docker and built worker dependencies.
- Frontend requires Node 22.22.2 or newer. Do not solve frontend configuration by copying root `.env` secrets into public environment variables.

See the [Chinese startup and verification guide](learning-workbench.md).
