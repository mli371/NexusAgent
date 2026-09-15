# NexusAgent Pi Worker

Separate TypeScript process for the optional diagnostic/approval harness. Full setup, protocol, tests, limitations and human-approval demo are in [the harness guide](../../docs/agent-harness.md).

```bash
npm ci --ignore-scripts
npm test
npm start
```

Node >=22.19 is required. Build output and dependencies are ignored by Git. `npm start -- --once` performs one claim cycle. Runtime configuration comes from environment variables or the repository-root ignored `.env`, never personal Pi credentials. `scripted` mode is explicit and model-free; `pi` mode uses the pinned SDK and requires explicit provider/model/key settings.

The model has exactly three custom tools: two metadata reads and `propose_retry`.
It has no shell/file tools, extensions, skills or execution/approval tool. After a
human approves, worker transport requests Java execution by stored approval ID
before starting a fresh Pi session. That transport command is not model-visible.
Sessions are disposed when waiting; bounded saved observations/decisions/outcomes
seed continuation. Java owns authorization, evidence, action state and budgets.
Do not treat SDK resource isolation as an OS sandbox.

Phase 3 uses a fresh session for recovered claims too. A completed/uncertain action
in continuation is not a pending dispatch. The worker stops on cancelled/inactive
claims or RECOVERY_REQUIRED without replacing the durable status with WORKER_ERROR.
Graceful shutdown leaves the lease to expire; Java decides recovery. Provider-side
work may continue after local abort. See [Chinese Phase 3 review](../../docs/review/agent-harness-phase-3.md).

Live provider smoke: PASS for read-only Phase 1 with OpenAI `gpt-5.6-luna`;
[evidence](../../docs/verification/agent-harness-gpt-5.6-luna.md) does not cover Phase 2
mutations or Phase 3 model-driven recovery. Tests use scripted HTTP/PostgreSQL execution,
actual worker process interruption and an offline Pi SDK loop with mocked model output.
None establishes production model quality.
