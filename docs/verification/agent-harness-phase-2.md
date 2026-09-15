# Agent Harness Phase 2 Verification

Date: 2026-09-11. Status: PASS for the checks below, awaiting owner review.

## Commands and Results

| Check | Result |
| --- | --- |
| `npm --prefix workers/pi-worker test` | 22 passed, 0 failed/skipped |
| `mvn -q -Dapi.version=1.44 clean test` | 168 tests, 0 failures/errors/skips |
| `bash -n scripts/agent-demo.sh` | Passed |
| `make -n agent-worker agent-run-demo DOC_ID=<synthetic UUID>` | Resolves to worker start and polling demo |
| `git diff --check` | Passed |
| Ignore rules for `.env`, worker dependencies/build | Confirmed |

Runtime: JDK 21 with Java release 17 compilation, Maven 3.9.9, Node 26.5.1,
PostgreSQL 16/PgVector and the repository's Redis Testcontainers tests. This is not
a claim that the suite was executed on a JDK 17 runtime. All eight Flyway migrations
were applied in disposable test databases. The user's local database was not migrated
or reset as part of this validation.

## Tested Boundaries

- Model-free Node worker against Java HTTP and real PostgreSQL: propose CHUNK,
  pause, explicit test-client approval, execute, propose EMBED_MISSING, pause,
  separately approve, execute, reinspect and produce a HEALTHY report.
- Real chunking and embedding repositories/services; the extraction boundary
  supplies synthetic text instead of reading MinIO in the new harness tests.
- No ingestion before approval; no force/replacement action is available.
- Originating tenant/actor only; permissions are checked again after approval.
- Reject/expire, repeated identical decision, conflicting decision, lost proposal
  response, concurrent approval and concurrent execution requests.
- Changed document hash/chunk identities and already completed work.
- Unsupported type, unknown/empty-text failures, active jobs, transient category
  and model-mismatched embeddings.
- Execution/job reservation rollback before I/O; business failure persistence;
  successful writes plus failed bookkeeping remain UNKNOWN, not falsely rolled back.
- Worker lease lost during ingestion blocks replay and preserves the exact job link.
- Cumulative round/tool budget and idempotent model-round reservation.
- Actual Pi SDK tool loop with mocked provider output: stops after proposal,
  disposes the session, no additional model request.
- Full regression suite for prior retrieval/query/Redis/enterprise behavior.

## Limits of This Evidence

No real model request or new paid API call was made. The earlier Luna smoke covers
read-only Phase 1 only. No live Phase 2 MinIO/model end-to-end acceptance, load test,
production identity/security review, crash recovery, SSE replay or frontend test
is claimed. These tests demonstrate protocol and failure handling, not model
reasoning quality, global exactly-once writes or production tenant isolation.

Manual acceptance commands are in [the approval demo](../agent-harness.md#human-approval-demo-phase-2).
Run that with synthetic documents, review each proposal, and approve each separately.
Do not paste credentials, real business documents or full logs into public issues.
