# Agent Harness Phase 2: Approval and Bounded Retry

Status: implemented and verified on 2026-09-11; waiting for project-owner review.

## Scope and Boundaries

Implement the approved harness design's second phase only. Java owns immutable
proposals, actor-bound decisions, state checks and ingestion execution. Pi may
propose CHUNK (force=false) or EMBED_MISSING, never approve or execute a model tool.
The existing read-only scripted diagnostic remains the default; the deterministic
approval demo uses the explicit question prefix `Approve processing:`. This is a
test fixture convention, not natural-language authorization.

Frontend chat, synthetic-document upload UI and login are deferred until after
the three backend harness phases. Crash recovery, active cancellation and SSE
replay remain Phase 3. No production authentication or security claims.

## Implementation Steps

1. Add V8: approval/execution records, typed job errors, exact job linkage and
   persistent model-round accounting. Preserve applied migrations.
2. Add proposal policy and actor-owned decisions with 30-minute expiry,
   state fingerprints, one pending proposal and idempotent decisions.
3. Dispatch an approved immutable action outside transactions; create its linked
   RUNNING job inside the reservation transaction. Recheck access and state.
   Record ambiguous outcomes conservatively and never automatically replay writes.
4. Pause and dispose the worker session; resume with bounded versioned observations,
   decisions and server-confirmed outcomes. Keep cumulative round/tool budgets.
5. Add PostgreSQL/HTTP/worker tests for decisions, scope, stale state, duplication,
   real business execution, job linkage, failure boundaries and planned continuation.
6. Update demo, architecture/API/schema/limitations and learning/interview material.

## Definition of Done

- One explicit decision per immutable action; no side effects before approval.
- CHUNK then EMBED_MISSING can complete as two separately approved continuations.
- Rejection/expiry cancels; changed state invalidates; completed work is skipped.
- No second dispatch from duplicate decisions or duplicate execution requests.
- No database transaction spans ingestion I/O or a model call.
- Lease loss during a reserved action produces ACTION_OUTCOME_UNKNOWN, not replay.
- Automated tests require no model key; compile/build and full suites pass.
- Document limits and provide manual approval commands; stop for user review.

## Verification Result

- Java full suite: 168 tests, 0 failures/errors/skips; JDK 21 compiling release 17.
- Worker suite: 22 tests passed, including the real Pi SDK pause loop with offline
  model output. No real model API was called for Phase 2 validation.
- PostgreSQL/Flyway V1-V8, two separate approvals, real chunk/embedding persistence,
  exact job linkage, stale/unauthorized/expired/duplicate requests and unknown
  action outcomes were exercised.
- `bash -n scripts/agent-demo.sh`, Makefile dry-run and `git diff --check` passed.
- See [verification record](../../verification/agent-harness-phase-2.md).
- No commit, Phase 3 implementation, frontend or login was performed.
