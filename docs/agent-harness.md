# Agent Harness: Diagnostics, Approval and Recovery

Implemented extension; ready for local review. The original retrieval and deterministic query APIs are unchanged. This is not a replacement for RAG and does not make the existing answer generator model-backed.

**Validation boundary:** scripted tests exercise Java HTTP, approvals, worker interruption, recovery and real PostgreSQL ingestion, with extraction supplied as a fixture. SSE tests exercise replay/live events and access revocation over HTTP. Pi isolation and its approval-pause loop are tested offline. The earlier [live OpenAI `gpt-5.6-luna` smoke](verification/agent-harness-gpt-5.6-luna.md) covered read-only Phase 1 only. Phases 2/3 have not been live-tested with a real model or production data. These checks do not establish model quality or production readiness. Start Phase 3 with [the Chinese review document](review/agent-harness-phase-3.md).

## What Runs Where

```mermaid
sequenceDiagram
  participant U as Caller
  participant J as Java backend
  participant DB as PostgreSQL
  participant W as TypeScript worker
  participant P as Pi + configured model
  U->>J: Create diagnostic run + explicit tenant/actor
  J->>DB: Validate document access, save QUEUED + event
  J-->>U: 202 + runId
  W->>J: Claim using worker credential
  J->>DB: Atomic RUNNING transition + lease
  J-->>W: Scoped assignment + per-claim token
  W->>P: Fixed prompt, question, scoped tool schemas (pi mode only)
  P-->>W: Choose inspect_document / list_ingestion_jobs
  W->>J: Tool request + stable invocationId
  J->>DB: Recheck access; persist invocation and safe observation
  J-->>W: Saved observationId + bounded metadata
  W->>J: Submit structured report referencing observations
  J->>DB: Validate references and conditions; persist terminal event
  U->>J: Poll run / ordered events
  J-->>U: Status, execution mode, validated report
```

In `scripted` mode, fixed code calls both tools for every document instead of invoking Pi. It produces deterministic explanations from the real metadata responses, not fabricated database results. Both modes use the same Java protocol and validation.

## Local Setup

Prerequisites: the existing Java/Maven/Docker stack plus Node **22.19 or newer** and npm. Pi dependencies are pinned at `0.85.1` in the worker manifest and lockfile. Use the pinned SDK instead of a global Pi installation.

1. Keep your existing `.env`. For a new checkout only, copy `.env.example` to `.env`. Add these values to the ignored local file:

```dotenv
NEXUS_AGENT_ENABLED=true
NEXUS_AGENT_WORKER_MODE=scripted
NEXUS_AGENT_API_URL=http://localhost:8080
NEXUS_AGENT_WORKER_TOKEN=<your-generated-private-token>
```

Generate the token with `openssl rand -hex 32`; replace the placeholder locally. Never commit it or include it in screenshots. `.env.example` intentionally has no usable worker credential. Use simple `KEY=value` entries; neither process requires shell-sourcing this file. Java imports `.env` via Spring; the compiled Node worker explicitly reads the repository-root `.env`. Environment variables override file values in both processes. Changing mode/provider requires restarting both.

2. Start dependencies and Java in the first terminal, from the repository root:

```bash
docker compose up -d
docker compose ps
mvn spring-boot:run
```

Flyway applies migrations through V9 automatically. The application still needs PostgreSQL at its configured host/port. Worker execution itself needs neither MinIO access nor Redis, but uploading the demo file requires MinIO.

3. Install, test, and start the worker in a second terminal:

```bash
npm --prefix workers/pi-worker ci --ignore-scripts
npm --prefix workers/pi-worker test
npm --prefix workers/pi-worker start
```

`test` builds TypeScript. After subsequent source edits, run `npm --prefix workers/pi-worker run build` before `start`. `start -- --once` performs one claim cycle, including execution if a run is available. It is not a daemon health check or proof that a queued task succeeded; inspect the persisted status.

4. Upload a non-sensitive sample and diagnose it in a third terminal:

```bash
bash scripts/demo.sh upload
DOC_ID=$(tr -d '[:space:]' < .demo-document-id)
bash scripts/agent-demo.sh "$DOC_ID"
```

The existing upload demo uses `default` / `anonymous`; the new demo sends those headers explicitly. For other existing documents use `TENANT_ID=tenant-a ACTOR_ID=alice bash scripts/agent-demo.sh "$DOC_ID"` with the same identity used to upload. The script creates a new diagnostic run, polls until terminal (bounded polling), and prints the report plus events. It exits nonzero on a failed run or polling exhaustion. It never starts a worker or changes document state.

Expected fresh text-file result: `executionMode=scripted`, `status=SUCCEEDED`, one finding with `condition=CHUNKING_REQUIRED`, and references to the two saved observations. Exact UUIDs and timestamps vary. `SUCCEEDED` means the diagnosis completed, not that the document is healthy or repaired.

To compare states manually, run the existing `bash scripts/demo.sh chunk` and `bash scripts/demo.sh embed`, then create another diagnostic run. After chunking only, expect `EMBEDDING_INCOMPLETE`; after complete matching embeddings, expect `HEALTHY`. These are explicit user-initiated ingestion commands, not agent actions. Unsupported PDF/Word stays `UNSUPPORTED_TYPE`. Historical failed jobs expose `errorCategory=UNKNOWN`; the agent cannot infer the cause from redacted errors.

## Configure Pi

Set `NEXUS_AGENT_WORKER_MODE=pi` in the shared local configuration and supply:

```dotenv
NEXUS_LLM_PROVIDER=<provider-in-the-pinned-Pi-registry>
NEXUS_LLM_MODEL=<model-id-in-that-provider>
NEXUS_LLM_API_KEY=<your-private-key>
```

Select a model that supports tool calls. Provider/model IDs must exist in the pinned SDK registry; a key alone does not select a model. Unsupported configuration stops the worker clearly. A custom API base URL, provider discovery UI, and a separate reranker service are not part of Phase 1. This diagnostic task needs neither semantic retrieval nor a reranker. A new provider with an incompatible endpoint would require an explicit adapter/configuration change later.

Restart Java and the worker, submit a **new** run, and inspect its mode, two tool observations, and validated report. Run mode/provider/model are captured on creation; changing configuration does not convert older queued scripted runs to Pi runs. Queue cleanup/reclassification is not implemented.

The SDK receives an in-memory credential store and an explicit runtime key. No personal Pi login, workspace extensions, skills, templates, context files, shell tool, file tools, or persisted model sessions are loaded. Only the question, scoped IDs, and bounded tool metadata are sent to the model. The question itself can contain sensitive data; use non-sensitive fixtures until provider/privacy policies are reviewed. Tokens and raw provider responses are not logged by our worker.

Live smoke acceptance: `executionMode=pi`, actual calls to both diagnostic tools, valid observation references for each document, and `SUCCEEDED` with no claimed mutation. Record provider/model and pass/fail, but never the key. A model failure must remain failure; there is no fallback from Pi to scripted success.

## Public API

All run routes require both identity headers. The server chooses execution mode and allowed tools. Callers cannot supply a system prompt, tool list, provider, claim token, or tenant override in the body.

```bash
curl --fail-with-body -X POST http://localhost:8080/api/v1/agent/runs \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous' \
  -H 'Idempotency-Key: diagnostic-example-1' \
  -H 'X-Trace-Id: diagnostic-trace-1' \
  -d '{"question":"Inspect these documents.","documentIds":["REPLACE_WITH_DOCUMENT_UUID"]}'

curl --fail-with-body http://localhost:8080/api/v1/agent/runs/REPLACE_WITH_RUN_UUID \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'

curl --fail-with-body 'http://localhost:8080/api/v1/agent/runs/REPLACE_WITH_RUN_UUID/events?afterSequence=0' \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

- Create returns 202 with `runId`, `traceId`, `status`, `executionMode`, `provider`, `model`, timestamps, and `statusUrl`.
- `question`: nonblank, up to 2,000 UTF-16 characters. `documentIds`: 1-10 UUIDs, deduplicated and sorted. Request body: at most 8 KiB serialized UTF-8.
- Optional idempotency key is scoped by tenant and actor. Same key + normalized request returns the original run; different request returns 409. Omit the key or use a new key for a fresh diagnosis, including after failure.
- Run/event reads require the creating actor and tenant and recheck access to **all** selected documents. Missing/inaccessible resources return 404. `PRIVATE` still requires document ownership. Revocation hides old reports/events; there is no public tool-result endpoint.
- Events: sequence-ordered pages of at most 100 with `nextSequence` and `hasMore`. Advance using that cursor. Event types are `queued`, `started`, `tool_started`, `tool_completed`, `completed`, `failed`. Trace and execution mode are on the page envelope. No token or raw question appears in events.
- Validation: 400; conflict/inactive claim: 409; oversized bounded JSON: 413; unavailable database: sanitized 503. Polling a FAILED run returns HTTP 200 with `errorCode` because the GET succeeded.
- Phase 2 adds approval; Phase 3 adds POST `/{runId}/cancel` and GET `/{runId}/events/stream` under `/api/v1/agent/runs`. Recovery is lifecycle maintenance, not a public force-retry endpoint. Existing `/api/v1/agent/query` remains deterministic Plan-Execute-Critique.

### Internal Worker Protocol

| Method and path under `/internal/agent-worker` | Purpose |
| --- | --- |
| `POST /claims` | Shared `X-Worker-Token`; 204 when idle, otherwise assignment and claim token |
| `POST /runs/{id}/heartbeat` | `X-Claim-Token`; renew lease within active deadline |
| `POST /runs/{id}/tools` | Version 1, invocation UUID, tool name, `arguments.documentId`, optional `retryOf` |
| `GET /runs/{id}/tools/{invocationId}` | Read saved result after transport interruption |
| `POST /runs/{id}/complete` | Submit versioned report directly; 204 on accepted/identical repeated completion |
| `POST /runs/{id}/fail` | `{ "code": "MODEL_ERROR" }` or another allowlisted failure code |
| `POST /runs/{id}/approved-retries/{approvalId}` | Worker transport command; Java resolves the approved immutable action; never a model tool |
| `POST /runs/{id}/model-rounds/{reservationId}` | Reserve one model request before calling the provider; retries reuse the UUID |

Keep internal routes on a trusted local network. They currently share the application's listener; path naming is not network isolation. The worker shared secret grants queue access across tenants and needs protection. Each claim uses a new random token; only its SHA-256 hash is persisted. Models never receive transport credentials. PostgreSQL, not the worker, resolves the stored run tenant/actor and scope for tool execution.

## State, Limits, And Failures

`QUEUED -> RUNNING -> SUCCEEDED | FAILED`, with approval transitions `RUNNING -> WAITING_APPROVAL -> QUEUED`, or `CANCELLED` after rejection/expiry. Phase 3 permits bounded read-only recovery to QUEUED and uncertain writes to RECOVERY_REQUIRED. A partial unique index allows only one RUNNING run globally, not a global lock on old I/O still completing under RECOVERY_REQUIRED. Claiming uses a short transaction, row lock and `SKIP LOCKED`. Waiting releases the active slot. A lease is not a security sandbox or an exactly-once guarantee.

Tool execution reserves a unique `(run_id, invocation_id)` in a transaction, releases the lock, performs the bounded read, then rechecks the active claim/access and saves the result in a new transaction. No model request or slow external I/O holds a database transaction open. The worker serializes tools. Reactor/R2DBC provide nonblocking database operations; Node performs model I/O in its separate process. Existing MinIO/hash isolation is unchanged.

| Limit | Default / behavior |
| --- | --- |
| Lease / active deadline | 45s / 180s; heartbeat about every 10s; heartbeat cannot extend the deadline |
| Tool read | 10s; timeout must be shorter than the lease |
| Model response | 60s cooperative abort; 2,000 output tokens requested |
| Model requests / tools | 12 / 30 per run, including repair/retries where invoked; Java authoritatively counts tool reservations |
| Read retry | One new invocation linked by `retryOf` for a saved transient failure; no retry chains |
| Provider retry | Pi error classification, at most one retry, with provider-level retries disabled; no success substitution |
| JSON tool/report/event payload | 8 KiB / 32 KiB / 2 KiB |
| Events | 500 admission budget; checks keep lifecycle headroom, but final cancellation/reconciliation bookkeeping is not a hard SQL quota; no heartbeat event growth |
| Job history | Latest ten rows, `truncated` flag |
| Approval lifetime | 30 minutes; `NEXUS_AGENT_APPROVAL_TTL`, positive and at most 24 hours |
| Continuation snapshot | Version 1, at most 32 KiB; latest successful read observations per document/tool, decisions and action results |
| Automatic recoveries | 3; NEXUS_AGENT_MAX_RECOVERIES, 0-10; cumulative budgets do not reset |
| SSE page / poll | 100 events / 1s; NEXUS_AGENT_EVENT_POLL_INTERVAL, 100ms-30s; idle comments are not persisted |

Deadlines and model round/output limits bound work, not monetary charges; a remote provider may continue after local cancellation. The trusted worker reserves each model round in Java before provider dispatch. Java keeps cumulative round/tool counters across approval pauses. A reservation can conservatively consume budget even if the provider never receives the request. Provider usage/cost is not a durable ledger.

Response loss differs from re-execution: a tool request retry keeps its invocation ID and looks up/replays the persisted result. Only an explicitly saved transient read failure permits a linked new invocation. Completion is idempotent only for the same JSON report; changing it after success returns 409. A report cannot complete while a tool is still RUNNING.

Expired leases are handled **lazily on public run reads or worker claim polling**, in maintenance batches of at most 100 runs. No traffic means delayed maintenance. A lost read-only lease requeues within recovery/tool/model budgets; exhausting the active deadline fails instead of renewing indefinitely. A reserved uncertain write enters RECOVERY_REQUIRED and is reconciled only from its exact linked job and current document state. A still-running/missing job cannot prove completion or rollback. Closing old tools marks them FAILED/RUN_TERMINATED, not proof the underlying read never finished. PostgreSQL unavailability stops authorization/execution; a later maintenance pass evaluates the saved state. Redis unavailability has no effect on core run storage.

Report validation requires one finding per requested document, successful observations from both read tools in the current claim, matching document IDs and exact condition labels. After approved execution the worker must reinspect, not present old observations as new evidence. Validation checks provenance and structure, **not** free-text factual correctness. `report.findings[].proposedNextAction` remains advice; only `propose_retry` creates an approval. Public `actionResults` come from Java records, never model assertions. Concurrent manual ingestion can still make observations stale.

## Tests And Review

```bash
npm --prefix workers/pi-worker ci --ignore-scripts
npm --prefix workers/pi-worker test
mvn -Dapi.version=1.44 clean test
bash -n scripts/agent-demo.sh
bash -n scripts/agent-events.sh
make -n agent-worker agent-run-demo agent-run-events DOC_ID=REPLACE_WITH_DOCUMENT_UUID RUN_ID=REPLACE_WITH_RUN_UUID
git diff --check
```

Docker must be running for PostgreSQL/Redis integration tests. Existing Testcontainers settings skip such tests without Docker; skipped is not passed. Build the worker first or the Java cross-process smoke test is explicitly skipped. No external key is required. The `api.version` override is needed with newer Docker daemons rejecting the old test client's default; it can be omitted on compatible daemons.

Read code in this order: `AgentRunController`, `AgentRunService`, `AgentRunRepository`, `AgentToolService`, `DocumentDiagnosticsService`, `AgentReportValidator`, then worker `backend-client.ts`, `worker.ts`, `scripted-session.ts`, and `pi-session.ts`.

Remaining limits: no queue admission/retention policy, verified identity, TLS deployment, dedicated internal port, general operator recovery workflow or distributed exactly-once execution. Cancellation does not promise rollback, remote provider cancellation or stopped billing. Questions, reasons and reports are bounded but potentially sensitive. Foreign keys preserve provenance; coordinated retention/deletion is deferred. Diagnostics do not read raw document text or establish semantic retrieval quality. Approved CHUNK reads MinIO through the existing extraction service. Fingerprints use persisted content identity and do not rehash the object at approval time. Concurrent legacy ingestion routes are not globally serialized.

## Human Approval Demo (Phase 2)

Keep your real `.env` private. For repeatable, model-free review, override only the
worker mode in the environment of **both** processes; restart any old Java/worker
process first. Follow the existing [local setup](#local-setup) for credentials,
Docker and dependencies. Do not print the `.env` contents.

```bash
docker compose up -d
NEXUS_AGENT_ENABLED=true NEXUS_AGENT_WORKER_MODE=scripted mvn spring-boot:run
# Separate terminal, same private worker token loaded from .env:
NEXUS_AGENT_WORKER_MODE=scripted npm --prefix workers/pi-worker start
```

Upload one synthetic text/Markdown file with the existing endpoint, but do not
manually chunk/embed it. Use matching explicit identity for all requests:

```bash
curl --fail-with-body -X POST http://localhost:8080/api/v1/documents \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous' \
  -F 'file=@examples/security-handbook.md;type=text/markdown'
export DOC_ID='REPLACE_WITH_RETURNED_DOCUMENT_UUID'
QUESTION='Approve processing: inspect this document and propose missing processing.' \
  bash scripts/agent-demo.sh "$DOC_ID"
```

`Approve processing:` is an explicit scripted-fixture convention, not permission
to write. In Pi mode, request inspection and proposed retries in normal language;
the same Java policy and human decision still apply. No blanket batch approval.

The demo prints WAITING_APPROVAL and `pendingApproval` then exits. Review the
document ID, action, canonical arguments, reason and expiry. Choose one decision:

```bash
export RUN_ID='REPLACE_WITH_RUN_UUID'
export APPROVAL_ID='REPLACE_WITH_PENDING_APPROVAL_UUID'
# Run this only after reviewing and approving this specific action:
curl --fail-with-body -X POST "http://localhost:8080/api/v1/agent/runs/$RUN_ID/approvals/$APPROVAL_ID" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous' -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE"}'
# Alternatively send decision REJECT; do not send both decisions.
curl --fail-with-body "http://localhost:8080/api/v1/agent/runs/$RUN_ID" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
curl --fail-with-body "http://localhost:8080/api/v1/documents/$DOC_ID/ingestion-jobs" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

Expected: first approval executes CHUNK with `force=false`. The resumed worker
observes missing embeddings and requests a **new** approval for EMBED_MISSING.
Review that new ID separately, then repeat the decision/poll commands. After both
successful actions, expect a SUCCEEDED run, HEALTHY report finding, two linked
ingestion jobs and two server-confirmed action results. A SUCCEEDED diagnostic run
can also report unresolved failures; it is not a promise that all documents are healthy.

### Approval and Execution Rules

- The originating tenant/actor alone can decide. Headers remain spoofable local
  identity, not production authentication. Worker/claim tokens cannot approve.
- Java admits only CHUNK without force and EMBED_MISSING without replacement.
  Supported extraction, running jobs, current model metadata and typed failure
  category are checked at proposal and execution. Existing UNKNOWN/EMPTY_TEXT/
  UNSUPPORTED failures require investigation; recognized transient failures may
  be proposed. No error-message substring classification.
- The fingerprint hashes canonical action, document metadata/content identity,
  parent/child IDs, job state and embedding coverage/model metadata. Changed state
  gives FAILED/DOCUMENT_STATE_CHANGED with no new job; already completed work is
  SKIPPED/ALREADY_COMPLETE without regenerated chunk IDs.
- Approval is an immutable decision, execution is a separate outcome. Identical
  repeated decisions return the saved decision; conflicting ones return 409.
  Unique approval/execution and execution/job links suppress duplicate dispatch.
- Job creation, execution reservation and `action_started` event commit together.
  Ingestion I/O then runs without that transaction. Status bookkeeping is separate;
  an error does not imply business writes were rolled back.
- An HTTP interruption may leave a RUNNING job. The same execution request can
  read its status under an active claim but cannot redispatch it. Lost lease while
  reserved becomes RECOVERY_REQUIRED/ACTION_OUTCOME_UNKNOWN with an UNKNOWN execution.
  Phase 3 reconciles exact job/current state and can resume diagnosis, never that
  consumed write. Legacy Phase 2 FAILED records are not automatically rewritten.
- Pending approvals expire lazily on public run reads or worker claim polling.
  No poll means expiry is reflected on the next access. Approval waiting does not
  consume the active deadline; each continuation gets a new active window but
  the same cumulative round/tool budgets.
- `approval_requested`, `approval_approved`, `action_started`, `action_completed`
  and `cancelled` extend the durable event log. No raw document text or reasoning
  traces are stored. Ingestion job/audit writes use the original run trace ID.

Read Phase 2 code in this order: `RetryPolicy`, `ApprovalService`,
`ApprovalRepository`, `ApprovedRetryService`, then worker `executeRun`/`PiSession`.
See [learning note and interview defense](learning/agent-harness-02-approval-retry.md).

## Recovery, Cancellation and SSE (Phase 3)

Detailed review, state table and manual commands are in [Chinese](review/agent-harness-phase-3.md).
The [learning note](learning/agent-harness-03-recovery-events.md) explains the trade-offs.
`cancellationRequested` and `cancellationPending` distinguish intent from completion.
Completed writes remain recorded even if their run is cancelled.

```bash
export RUN_ID='REPLACE_WITH_RUN_UUID'
bash scripts/agent-events.sh "$RUN_ID"
# Reconnect after the last event processed by the client, for example ID 5:
bash scripts/agent-events.sh "$RUN_ID" 5
# Separate explicit cancellation; SSE disconnect does not imply cancellation:
curl --fail-with-body -X POST "http://localhost:8080/api/v1/agent/runs/$RUN_ID/cancel" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

SSE uses persisted sequence IDs. Last-Event-ID takes precedence over afterSequence;
invalid/future cursors return 400. Replay and live updates read the same table, with
access checked before opening and on every page. Terminal history closes normally.
After headers, errors emit a sanitized event without an ID and close the stream.
Native EventSource cannot set the required demo headers: use curl/fetch. Clients
should save their last processed cursor and deduplicate `(runId, sequence)`.
New durable event types: recovery_queued, recovery_required, action_reconciled,
cancel_requested. The final cancellation event remains cancelled; idle comments
do not advance history.

## Troubleshooting

For browser-based upload, approvals, real execution nodes and bounded tool details, use the [learning workbench](learning-workbench.md). It reuses this protocol and leaves policy/dispatch in Java. Static source explanations are not full traces. The read-only `/tools` projection requires restarting an older Java process; it adds no migration or model calls.

- `AGENT_RUNS_DISABLED`: enable the feature and restart Java; do not confuse it with the older `/agent/query` route.
- Java startup rejects the worker token: use a private token of 32-512 characters in both processes.
- `CONFIGURATION_ERROR` in worker: check mode, API origin, provider/model membership, explicit key, and matching Java configuration. Do not paste credentials into an issue.
- Run remains QUEUED: start a matching worker; check whether another run holds the global active slot. A stopped worker's active run expires on polling after its lease.
- `recovery_queued` / recoveryCount increasing: investigate worker health. The same run can resume within cumulative budgets; do not create duplicate jobs to clear its status.
- `RECOVERY_REQUIRED`: inspect actionResults and the exact linked job. RUNNING/missing/inconsistent job evidence needs investigation, not blind replay. There is no force-success endpoint.
- `RECOVERY_EXHAUSTED` / `RUN_DEADLINE`: continuation stopped at its bound. Review evidence before creating another run.
- SSE error or reconnect 404: verify original identity/current access. Retain the last successfully processed durable event ID; an error event has no ID.
- `MODEL_ERROR` / `INVALID_REPORT`: inspect safe run/events metadata. The adapter never silently switches to scripted mode.
- 404 with a known run/document: use the original tenant/actor and check current PRIVATE ownership. Do not remove scope checks to make the demo pass.
- Docker tests skipped or API-version error: start Docker and rerun with `-Dapi.version=1.44`; check the test report skip count.
