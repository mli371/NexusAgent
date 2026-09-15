# Agent Harness Live Smoke: GPT-5.6 Luna

Result: **PASS**, observed on 2026-09-11 at approximately 06:42 UTC (September 10 in Los Angeles).

## Configuration And Scope

- Provider/model: `openai` / `gpt-5.6-luna` via the Responses API and pinned Pi SDK `0.85.1`.
- Execution mode: `pi`, not `scripted`.
- Credentials: private local `.env`; no key or worker credential is recorded here.
- Fixture: the repository's non-sensitive security handbook, uploaded into a new test-only tenant/actor scope.
- Model-visible data: the diagnostic question, selected document UUID, tool schemas, and bounded metadata observations. No document body or raw job errors were sent to the model.
- Existing tool, report, lease, and execution budgets were retained. No application code change was required for this model selection.

## Observed Result

1. Java accepted a new run as `pi` with model `gpt-5.6-luna`.
2. The real model selected `inspect_document` and `list_ingestion_jobs`.
3. Java executed and persisted both tool observations with `SUCCEEDED` status.
4. The model returned a structured report referencing both saved observation IDs.
5. Java accepted the report and persisted the run as `SUCCEEDED`.
6. Ordered events were `queued`, `started`, `tool_started`, `tool_completed`, `tool_started`, `tool_completed`, `completed`.
7. Independent PostgreSQL reads confirmed `tool_count=2` and two successful tool records.
8. The document remained `STORED`, with zero child chunks and zero ingestion jobs. The agent suggested `CHUNK` as advice but performed no ingestion mutation.

The finding was `CHUNKING_REQUIRED`: a supported uploaded document had no parent/child chunks or embeddings, and the recent job list was empty. This matched the fixture's actual state. There was no fallback to a scripted report.

## What This Does Not Establish

This is one successful live integration smoke test, not a model-quality evaluation, benchmark, availability guarantee, or proof that all failure/recovery paths work with a live provider. Token usage/cost is not yet persisted by the harness, so no measured cost is claimed. Account model access can vary.

The existing RAG `/query` and deterministic `/agent/query` endpoints are unchanged and still use local/template behavior. Human approval, mutation execution, crash recovery, and new run SSE remain unimplemented.

## Reproduce

Use the [harness setup and demo](../agent-harness.md) with `NEXUS_AGENT_WORKER_MODE=pi`, `NEXUS_LLM_PROVIDER=openai`, and `NEXUS_LLM_MODEL=gpt-5.6-luna`. Supply the key only in ignored local configuration. Upload a fresh non-sensitive text fixture, start the worker, and run `bash scripts/agent-demo.sh "$DOC_ID"` with its original tenant/actor headers. Inspect the persisted mode, report references, tool events, and unchanged document state.

Each run may incur API charges. Do not replace a failed Pi run with scripted success. Temporary test Java/worker processes were stopped after this validation; local Compose dependencies and test records were retained.

Model ID and function-calling support were checked against [official OpenAI documentation](https://developers.openai.com/api/docs/models/gpt-5.6-luna).
