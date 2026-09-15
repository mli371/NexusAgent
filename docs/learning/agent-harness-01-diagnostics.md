# Agent Harness Phase 1: Read-Only Diagnostics

Historical Phase 1 delivery note. Current recovery, cancellation and SSE behavior is documented in
[Phase 3](agent-harness-03-recovery-events.md); earlier statements about absent recovery apply to Phase 1 only.

## What Changed

Added a separate asynchronous diagnostic API and a TypeScript worker, without modifying ingestion, retrieval, template answer generation, or the original deterministic Plan-Execute-Critique API. The backend accepts a request, stores a run, and returns 202. A worker claims it and uses two read-only tools. Java validates the final report against saved observations.

The Pi adapter has passed one [live OpenAI `gpt-5.6-luna` smoke test](../verification/agent-harness-gpt-5.6-luna.md), including both tools and report persistence. This is integration validation, not a model-quality benchmark. `scripted` mode exercises the real backend using fixed decisions and explicitly labels itself. Automated tests require no model credentials; live smoke tests do. This is the first phase of the approved extension, not completion of all three design phases.

## The Important Separation

**A harness chooses the next step; the application decides what is allowed and what actually happened.**

Pi owns the model/tool-call loop in its process. Java owns document scope, tenant/actor checks, immutable invocation identities, task transitions, budgets, durable observations, and final report acceptance. Do not turn a model response into proof of permission, successful tool execution, or a completed repair.

`inspect_document` and `list_ingestion_jobs` are capability boundaries. Their only model argument is one document UUID from the run's scope. The backend resolves the stored identity rather than accepting a model-supplied tenant. It does not expose SQL, shell commands, raw MinIO locations, chunk text, embeddings, or arbitrary HTTP access.

## Code Reading Map

| Read first | Question the file answers |
| --- | --- |
| `agentrun/api/AgentRunController.java` | What can the caller request, and which headers are mandatory? |
| `agentrun/application/AgentRunService.java` | Who owns a run, when is a claim active, and when may a report complete? |
| `agentrun/repository/AgentRunRepository.java` | How do short transactions enforce claiming, unique invocations, events, and terminal states? |
| `agentrun/tools/AgentToolService.java` | How does reserve/read/save separate database locks from I/O, and how do retries work? |
| `agentrun/tools/DocumentDiagnosticsService.java` | What can be proven from metadata without exposing document content? |
| `agentrun/application/AgentReportValidator.java` | Which report claims are checked against real observations? |
| `workers/pi-worker/src/backend-client.ts` | How does the worker recover a lost HTTP response without duplicating the invocation? |
| `workers/pi-worker/src/worker.ts` | Who renews the lease, handles failure, and disposes each model session? |
| `workers/pi-worker/src/pi-session.ts` | How are tools, credentials, resource loading, rounds, and model deadlines restricted? |
| `workers/pi-worker/src/scripted-session.ts` | What is deterministic test behavior rather than an LLM capability? |

## Walk Through One Run

1. The caller supplies explicit tenant/actor headers, a bounded question, and 1-10 document IDs. Java checks current access and saves a QUEUED run with its document scope and event.
2. Creation idempotency is `(tenant, actor, Idempotency-Key)`. A repeated key/request returns the same run. Changing the request under that key is a conflict.
3. The worker authenticates to the internal claim endpoint with a local shared secret. PostgreSQL atomically assigns a run; only one run can be RUNNING globally. Java generates a random claim token and stores only its hash.
4. In Pi mode, an isolated in-memory session receives fixed instructions, the question, scope, and two tool definitions. Model-visible content does not include worker credentials or claim tokens. In scripted mode, fixed code invokes the same tools.
5. Java checks the claim, run scope, and current document access. It reserves the invocation under the run lock, commits, performs a bounded metadata read, then rechecks and saves the result under a new short transaction.
6. The worker passes a report with saved observation IDs. Java requires both successful tools for every document, matching document identity and inspection condition, bounded fields, and no unfinished calls.
7. The report and terminal event are committed together. The caller polls status and ordered events with current access checks. Disconnecting a reader does not cancel a run.

## Database And Redis

V7 creates `agent_runs`, `agent_run_documents`, `agent_tool_calls`, and `agent_run_events`. These are durable workflow records in PostgreSQL, not Redis entries with TTLs. The existing Redis `ToolOutputStore` remains appropriate for temporary outputs in the older deterministic workflow; it is not reused as authoritative diagnostic evidence because eviction must not erase the record used to validate a report.

The claim transaction uses `FOR UPDATE SKIP LOCKED` plus a partial unique index for the single RUNNING slot. Tool reservations and completions lock their run row. No transaction spans a model request or external dependency call. Flyway remains blocking JDBC at startup; runtime persistence is R2DBC. The existing MinIO/file-hashing `boundedElastic` isolation is unchanged.

## Failure Reasoning

- **Same invocation, lost response:** retrieve or replay the saved result. Do not create another call just because the network response was lost.
- **Saved transient read failure:** one linked retry with a new invocation ID is allowed. A second retry or retry chain is rejected. All reservations count against the Java tool budget.
- **Invalid report:** at most one model repair round within the same total budget; otherwise fail. No fabricated observations and no Pi-to-scripted success fallback.
- **Worker lost:** expire the lease and mark FAILED; unfinished tool rows become `RUN_TERMINATED`. Phase 1 does not resume the run.
- **Active deadline reached:** heartbeats cannot keep the run alive forever. A fresh request is required after terminal failure.
- **Database unavailable:** do not accept an unpersisted task or authorize work from a Redis copy. Safe 503 responses and later lease finalization replace any false success claim.
- **Access revoked:** new tool calls and report/event reads are rejected. This is still header-based demo authorization, not verified authentication.

Lease cleanup is triggered by claim polling or public run reads, not a background sweeper. Model cancellation is cooperative; a remote provider may continue charging. Success means a structurally valid diagnostic report was stored, not that a document was repaired or every explanation is true.

## Tests And Local Commands

See [the setup guide](../agent-harness.md) for separate backend/worker startup and sample curl requests.

```bash
npm --prefix workers/pi-worker ci --ignore-scripts
npm --prefix workers/pi-worker test
mvn -Dapi.version=1.44 clean test
bash -n scripts/agent-demo.sh
make -n agent-run-demo DOC_ID=REPLACE_WITH_DOCUMENT_UUID
```

The Docker API override is test-client compatibility for newer daemons. Java unit tests check report provenance and safe error handling. PostgreSQL integration covers V7, concurrent claims, idempotency, access revocation, leases/deadlines, tool timeouts/retries, budgets, real chunk/embedding diagnostics, and cross-process scripted execution with Redis disabled. Worker tests cover response loss, explicit configuration, disabled ambient resources, bounded scripted reports, report repair, failures, and disposal.

During full-suite validation an existing retrieval test exposed a setup race: `zipWith` subscribed the full-text reader before the other branch finished inserting fixtures. The test now waits for setup before subscribing both readers. No retrieval implementation was changed. This is a useful Reactor lesson: `zip` coordinates results, not side-effect ordering; put setup before it with `then`/`flatMap`.

## Interview Defense

**Why add this if you already have an agent endpoint?** The original endpoint has a deterministic Plan-Execute-Critique flow inside one query. This extension introduces a separate run lifecycle, durable tool evidence, and an optional model-driven tool-selection loop. I do not claim that the old endpoint became autonomous or that the whole application now uses a live LLM.

**Why Java plus TypeScript?** Java already owns business rules, data, and reactive persistence. The TypeScript worker reuses the actual Pi SDK rather than recreating an agent loop. The cost is another process and a versioned HTTP contract; its boundary is small and covered by cross-process tests.

**Is this exactly-once execution?** No. We make creation and saved tool invocations idempotent and fence inactive claims. Tools are read-only in this phase. This reduces duplicate work after transport loss but does not establish global exactly-once semantics or solve ambiguous business mutations.

**Does your validator prove groundedness?** It proves that referenced observations were really saved for accessible, scoped documents and that reported condition labels match those observations. It does not prove natural-language explanation quality or diagnose unknown historical failures.

**What does Pi provide, and what did you implement?** Pi provides the model/tool loop and SDK session handling. I implemented the task protocol, access enforcement, durable evidence and events, lease/deadline handling, bounded tool dispatcher, report validation, and scripted test driver. SDK capabilities are not automatically application security guarantees.

**60-second explanation:**

> I extended an existing Java knowledge backend with a small, read-only diagnostic harness. A caller creates a durable run, and a separate worker uses two scoped tools to inspect document processing status and recent jobs. Java remains authoritative for tenant and owner checks, task transitions, tool budgets, saved observations, and report validation. PostgreSQL stores the run lifecycle independently of Redis. Invocation IDs let us recover lost responses without blindly duplicating calls, and leases fence lost workers. The worker has no shell or mutation tools. Scripted tests run without a model, and a live GPT-5.6 Luna smoke test exercised both tools and produced a validated report. Human approval, mutation execution, and recovery are deliberately later phases.

## Limitations And Review Checklist

- Verify the separation between transport credentials and model input, and between model suggestions and Java-enforced authority.
- Understand creation idempotency, invocation replay, linked retry, claim lease, and active deadline as different mechanisms.
- Understand why new run state is durable PostgreSQL data while the old temporary tool store can remain Redis-backed.
- Reports and questions are potentially sensitive, bounded persisted data; no automatic retention policy exists yet. Tools expose no raw document text or raw historical exceptions.
- No queue admission control, verified auth, process sandbox, automatic recovery, cancellation, approval execution, or new SSE is implemented.
- No retrieval tuning, embedding/reranker replacement, or production answer-quality claim is included.
- Review this phase before beginning the approval phase; one successful live smoke test does not replace that review.
