# Agent Harness Phase 2: Human Approval and Bounded Retry

Historical Phase 2 delivery note. Phase 3 replaces FAILED/ACTION_OUTCOME_UNKNOWN handling for new
uncertain writes with RECOVERY_REQUIRED and adds bounded recovery/cancellation/SSE. See the
[Chinese Phase 3 learning note](agent-harness-03-recovery-events.md); old FAILED rows are not rewritten.

## What Changed

Phase 1 could inspect processing metadata and suggest actions. Phase 2 can request
human approval for one ordinary CHUNK or EMBED_MISSING operation, pause, execute
the stored approved action in Java, and inspect the outcome in a fresh session.
The original RAG/query/Plan-Execute-Critique APIs are not converted into this harness.

The key distinction: **a proposal is not permission, and approval is not success**.
A model's explanation cannot authorize a database write. An APPROVED record may
still have a FAILED, SKIPPED or UNKNOWN execution result.

## Code Reading Order

1. `agentrun/approval/RetryPolicy.java`: canonical actions, eligibility, fingerprints.
2. `ApprovalService.java`: proposal validation, actor-bound decisions, replay.
3. `ApprovalRepository.java`: pending proposal, immutable decision, execution record,
   expiry and bounded continuation projection.
4. `ApprovedRetryService.java`: transactional reservation, business execution
   outside the transaction, conservative outcome bookkeeping.
5. `enterprise/ingestion/IngestionJobService.java`: typed failure boundary, existing
   versus newly created job, preserving an original failure if status storage fails.
6. `workers/pi-worker/src/worker.ts`: execute stored approval before starting the
   next diagnostic session, release the session on WAITING_APPROVAL.
7. `pi-session.ts`: only three custom model tools; reserve model rounds before I/O;
   abort the Pi loop after a saved proposal, without an additional model request.
8. `V8__agent_approvals_and_retries.sql` and `AgentApprovalIntegrationTest.java`.

## Lifecycle

```text
QUEUED -> RUNNING -> inspect + read jobs
                  -> propose_retry -> WAITING_APPROVAL (Pi session disposed)
                  -> human APPROVE -> QUEUED
                  -> fresh claim -> Java rechecks stored action + current scope
                  -> reserve execution and exact RUNNING job in one transaction
                  -> execute ordinary ingestion outside that transaction
                  -> save outcome -> fresh Pi/scripted session -> reinspect
                  -> report, or request a separate next approval

human REJECT / pending approval expires -> CANCELLED, no dispatch
worker lost during reserved action -> FAILED/ACTION_OUTCOME_UNKNOWN, no replay
```

The internal execution command accepts an approval ID, not replacement action
arguments. It is not a registered Pi tool. The model cannot select tenant/actor,
force=true, a different embedding model, arbitrary SQL or an executable command.

## Data and Transaction Boundaries

- `agent_approvals` stores action, canonical arguments/hash, state fingerprint,
  requester, decision actor/status and 30-minute expiry. One pending proposal/run.
- `agent_action_executions` has a unique approval link. Double-clicking APPROVE or
  re-delivering an execution request cannot create a second dispatch for it.
- `ingestion_jobs.agent_execution_id` is unique and set **during job creation**.
  `trace_id` follows the original run through ingestion and its audit event.
  Do not find the "latest job" after execution: another request could own that job.
- `agent_model_rounds` deduplicates reservation IDs. Run round/tool counters survive
  pauses; default total limits remain 12 model requests and 30 tools.
- Proposal and decision updates hold the run row lock only for bounded database
  operations. The action reservation, job insertion and event share a transaction.
  MinIO extraction and embedding I/O do not run inside that transaction.
- Existing blocking MinIO/extraction code remains on its existing boundedElastic
  boundary. This patch adds no blocking wait inside the request event loop.

The protocol supplies at most 32 KiB of versioned continuation evidence: latest
successful read observations per document/tool, approval decisions and execution
outcomes. Question and document scope are supplied separately. It does not persist
or restore chain-of-thought. Oversized/unavailable continuation fails clearly.
Both read tools must run again in the current claim before report completion.

## Eligibility and Stale State

Ordinary CHUNK is allowed only when chunks are missing and a configured extractor
supports the document. EMBED_MISSING requires child chunks, incomplete coverage
and compatible provider/model/dimension. Observed running jobs block proposals
and execution. The most recent relevant failed job permits retry only with a
recognized TRANSIENT_DEPENDENCY code. Historical free-text failures remain UNKNOWN;
the application does not guess permission by matching their messages.

Typed codes now identify UNSUPPORTED_TYPE, EMPTY_TEXT and CHUNKING_REQUIRED at
their source. Bounded cause traversal recognizes timeout/transient database
exceptions; unrecognized dependency errors remain UNKNOWN. This is conservative,
not an exhaustive error taxonomy.

Fingerprints include persisted document identity/content hash, parent/child IDs,
job identity/state, embedding IDs/provider/model/dimension and canonical action.
Aggregated identifiers are SHA-256 hashed in PostgreSQL; the canonical metadata
projection is hashed in Java. Raw chunk text is never sent to Pi.

If a necessary action has changed relevant state, execution becomes
FAILED/DOCUMENT_STATE_CHANGED without a new job. If another caller already did
the work, Java records SKIPPED/ALREADY_COMPLETE, not a newly successful repair.
An existing APPROVED decision is retained as history in either case.

## Failure Semantics

| Failure | Behavior |
| --- | --- |
| Wrong tenant/actor or revoked document access | Generic 404; no dispatch |
| Unsupported/unknown failure or incompatible embeddings | Saved non-retryable proposal error; no approval |
| Duplicate proposal delivery | Same invocation returns same saved approval |
| Duplicate identical decision | Saved decision; no second execution row |
| Conflicting/expired decision | 409; pending expiry cancels on next poll/read |
| Job reservation transaction fails | No business I/O; reservation rolls back |
| Known business failure with recorded FAILED job | FAILED action with safe category; old approval is consumed |
| Business writes succeed but job bookkeeping fails | Conservatively UNKNOWN; writes may exist; no automatic retry |
| Lease lost or client disconnect during mutation | May leave a RUNNING job; UNKNOWN after lease processing; never redispatch blindly |
| Read response lost | Recover saved observation using same invocation ID |
| Model response/round limit reached | Fail explicitly; no scripted success substitution |

The worker's HTTP re-delivery of an approved command is not a business retry:
Java only dispatches PENDING once. A saved RUNNING execution is observed, never
started again. A new intentional business retry requires a new proposal/decision.
No generic Reactor retry or timeout wraps the business mutation publisher.

## Tests and Demo

```bash
npm --prefix workers/pi-worker test
mvn -Dapi.version=1.44 clean test
bash -n scripts/agent-demo.sh
make -n agent-worker agent-run-demo DOC_ID=REPLACE_WITH_DOCUMENT_UUID
git diff --check
```

Docker must be running. The Docker API override is for newer daemons with this
Testcontainers version. No model key is needed. Build the worker before Java tests.
See [manual approval commands and expected outcomes](../agent-harness.md#human-approval-demo-phase-2).

Integration tests use real PostgreSQL, repositories, chunking and deterministic
embedding services. Extraction returns synthetic text from a test fixture instead
of fetching MinIO. A real Node process performs CHUNK proposal/pause, approval,
EMBED_MISSING proposal/pause, approval and the final HEALTHY diagnostic report.
An offline test runs the actual Pi SDK loop with mocked provider output and verifies
it stops at proposal without another model request. This is not a live model test.

## Interview Defense

**Why not just ask the model to confirm first?** Natural language is not an
authorization boundary. Java requires an actor-owned decision for immutable
arguments and rechecks access/current document state before dispatch.

**Why two records for approval and execution?** They represent different facts:
what a person authorized, and what actually happened. Approval does not prove
success; a changed document or failed dependency can prevent the action.

**Does idempotency mean exactly-once execution?** No. Unique records prevent
duplicate dispatch for an approval, but infrastructure failures can leave an
unknown outcome. We block replay and preserve the linked job for investigation.

**Why not put the whole workflow in one transaction?** A transaction spanning a
human decision or external I/O would hold locks/connections for too long and still
cannot atomically roll back an external service. We commit intent, perform work,
then record its outcome, with explicit ambiguity handling.

**How does the agent continue after approval?** It starts a new in-memory Pi
session with bounded saved observations, decisions and Java-confirmed outcomes.
Round/tool budgets remain cumulative; it reinspects before reporting. This is
planned continuation, not recovery of hidden model reasoning.

**What is not secure yet?** End-user tenant/actor headers are not verified
identity. We need real authentication and authorization before a public deployment.
SDK tool restrictions are not an OS sandbox or complete tenant-isolation guarantee.

60-second pitch: "I extended a Java knowledge backend with a small agent harness
for diagnosing document-processing failures. Pi can inspect metadata and propose
two bounded ingestion actions. Java owns authorization, durable state and tool
execution. A proposed write pauses the session until the originating actor approves
the exact operation. We fingerprint document state, reject stale proposals and link
the execution to its ingestion job at creation time. Repeated decisions do not
dispatch duplicate work. After approval a fresh session receives saved evidence
and reinspects the result. If a write's outcome is ambiguous, we mark it unknown
and block automatic replay. This is a tested human-in-the-loop slice, not an
autonomous repair platform or production authentication system."

## Known Limitations and Next Phase

No crash recovery, reconciliation API, active cancellation or event SSE yet.
Those remain Phase 3. There is no frontend/login in this phase; discuss them after
the three backend phases. Public legacy ingestion calls can race the harness;
observed job/fingerprint checks are not global locking or an atomic auth snapshot.
Object content is not rehashed at approval time. Error classification is deliberately
conservative. Reports' free-text explanations can still be wrong. Input/reason/report
text is bounded, but can be sensitive and has no automatic retention policy.
The earlier live model smoke test covers only Phase 1, not these approved mutations.
