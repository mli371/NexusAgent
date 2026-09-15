# Agent Harness Phase 1 Implementation Plan

Status: Phase 1 implemented and verified; awaiting owner review. The approved design is `2026-09-10-agent-harness-design.md`. Phases 2-3 have not started.

Scope: Read-only document diagnostics. No approvals, mutation tools, automatic recovery, or new SSE endpoint in this delivery.

1. [x] Add run/document/tool/event migrations and strict protocol/configuration types.
2. [x] Implement transactional run claiming, lease expiry, tool result replay, tenant checks, and report validation.
3. [x] Add public and worker routes with safe error responses.
4. [x] Implement a pinned Pi worker and a clearly labeled scripted mode using the same protocol.
5. [x] Verify Java, PostgreSQL, worker, and end-to-end failure cases.
6. [x] Update README/demo/API docs and write the learning note, interview defense, and limitations.

The owner subsequently supplied local credentials and selected OpenAI `gpt-5.6-luna`. One live smoke test passed; see [evidence and limits](../../verification/agent-harness-gpt-5.6-luna.md). Never substitute scripted success for live-model validation.

## Verification Evidence

Verified locally on 2026-09-10 with JDK 21 compiling the existing Java 17 release target, Node 26.5.1, and Docker/Testcontainers. Java 17 runtime and Node 22 runtime were not separately exercised in this environment.

| Check | Result |
| --- | --- |
| `mvn -Dapi.version=1.44 clean test` | 149 tests, zero failures/errors/skips, including real PostgreSQL and Redis integration tests |
| `npm --prefix workers/pi-worker test` | 14 tests, zero failures/skips; TypeScript build passes |
| `AgentRunIntegrationTest` | 11 tests including worker HTTP/PostgreSQL smoke and the actual demo script |
| `bash -n scripts/demo.sh scripts/agent-demo.sh` | Passed |
| `make -n agent-worker agent-run-demo DOC_ID=<UUID>` | Correct worker/demo commands |
| `git diff --check` | Passed |
| Live Pi/provider tool calls | PASS with OpenAI `gpt-5.6-luna`; both tools and a validated report persisted, document unchanged |

The Docker API override makes the older test client compatible with the local Docker daemon. An existing retrieval integration test subscribed its full-text read before fixture insertion completed; only its setup ordering was corrected. No retrieval application logic changed.

Review entry points: [implementation guide](../../agent-harness.md), [learning note](../../learning/agent-harness-01-diagnostics.md), `AgentRunService`, `AgentToolService`, `AgentReportValidator`, and worker `pi-session.ts`. No staging, commit, or push was performed. Local OpenAI configuration was subsequently updated at the owner's request; the supplied API key was not displayed or committed.
