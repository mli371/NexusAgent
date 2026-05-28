# Milestone 8: Plan-Execute-Critique Workflow

## What This Milestone Solves

Milestone 8 adds a small workflow layer on top of the existing query pipeline.

The goal is not to build a full autonomous agent platform. The goal is to show a clear, deterministic orchestration pattern:

```text
plan -> execute -> critique
```

This gives the backend a structured way to decide what should happen, run the existing retrieval/query pipeline, and check whether the result is acceptable.

## Main Classes And Responsibilities

- `AgentQueryController`: exposes `POST /api/v1/agent/query`.
- `AgentOrchestrator`: owns the deterministic Plan-Execute-Critique flow.
- `Plan`: records the planner name, rationale, and ordered plan steps.
- `PlanStep`: records a single ordered workflow step.
- `PlanAction`: enumerates supported actions.
- `ExecutionResult`: records what ran, whether retrieval/fallback was used, cache status when available, citation count, and execution notes.
- `CritiqueResult`: records deterministic critique outcome and findings.
- `AgentWorkflowStatus`: records the final workflow status returned to the client.

Supported plan actions:

- `RETRIEVE_CONTEXT`
- `GENERATE_ANSWER`
- `FALLBACK_INSUFFICIENT_CONTEXT`

## Data Flow

```text
POST /api/v1/agent/query
  -> validate question/topK/contextBudgetChars
  -> create deterministic plan
  -> store plan through ToolOutputStore
  -> execute plan
       -> fallback answer for low-information requests
       -> direct local answer for simple conversational requests
       -> existing QueryOrchestrationService for retrieval-backed requests
  -> critique answer and citations
  -> store execution and critique through ToolOutputStore
  -> return answer, citations, workflowStatus, and optional debug internals
```

For retrieval-backed requests, `AgentOrchestrator` delegates to `QueryOrchestrationService`. That means Milestone 8 reuses the same context construction, answer generation, Redis retrieval cache, session state, and tool-output behavior from previous milestones.

## Why This Design Is Reasonable

The project already has a working query pipeline. Rebuilding retrieval or answer generation inside the workflow would create duplicate behavior and make the system harder to explain.

Instead, the workflow layer does only three things:

- chooses a small plan with deterministic rules
- delegates execution to the existing pipeline when retrieval is needed
- critiques the result with simple citation-grounding checks

This is interview-friendly because the boundaries are clear and the implementation is testable.

## Critique Behavior

The critique step is deterministic.

It checks:

- whether a fallback was used
- whether retrieval was used
- whether the answer reports insufficient context
- whether retrieval returned citations
- whether the answer references at least one citation marker that actually exists in the returned citation list

Possible critique outcomes:

- `PASS`
- `MISSING_CONTEXT`
- `MISSING_CITATIONS`
- `FALLBACK_USED`

This is not an LLM-as-judge implementation. It does not verify factual correctness. It only checks basic grounding signals that are useful for this MVP.

## Debug Behavior

With `debug=false`, the agent response returns public fields:

- `traceId`
- `answer`
- `citations`
- `workflowStatus`

With `debug=true`, it also returns:

- `plan`
- `executionResult`
- `critiqueResult`
- `queryDebug` when the existing query pipeline was used

This keeps normal responses small while still making the workflow inspectable during local demos and interviews.

## Redis And Tool Outputs

Milestone 8 uses the existing `ToolOutputStore` interface.

When Redis is enabled, the workflow can store:

- `agent-plan`
- `agent-execution`
- `agent-critique`

These values remain short-lived and size-limited. The workflow stores summaries of plan, execution, and critique state. It does not store raw uploaded documents or unbounded parent context blobs in workflow outputs. Redis is still not the source of truth.

## How To Run

Start local services:

```bash
docker compose up -d
```

Run the app:

```bash
mvn spring-boot:run
```

Run a workflow query after uploading, chunking, and embedding a supported document:

```bash
curl -X POST http://localhost:8080/api/v1/agent/query \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "local-demo-session",
    "question": "What does the security policy say?",
    "documentIds": ["{document-id}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": true
  }'
```

Run tests:

```bash
mvn test
```

Run only agent workflow tests:

```bash
mvn test -Dtest='com.nexusagent.agent.**.*Test'
```

## Design Defense

The Plan-Execute-Critique workflow is intentionally minimal.

In an interview, explain it like this:

> I added a deterministic workflow layer over the existing query pipeline. The planner decides whether to retrieve context, return a direct local response, or use a fallback. The executor reuses the already-tested query orchestration for retrieval-backed answers. The critique step checks whether retrieved answers include citations and whether fallback was needed. This gives the project an agent-like workflow shape without pretending it is a full autonomous platform.

Key trade-offs:

- The planner is deterministic, not model-generated.
- The critique step checks citation grounding signals, not factual correctness.
- The executor reuses the query pipeline instead of duplicating retrieval logic.
- Workflow internals are debug-only by default.
- Redis stores temporary workflow outputs only when enabled.

## Known Limitations

- No production LLM is called.
- The planner is simple and rule-based.
- The critique step is not a learned judge.
- The workflow is single-request and does not support long-running background execution.
- There is no agent SSE endpoint yet.
- There is no complex tool graph or multi-agent coordination.
- There is no durable workflow audit table in PostgreSQL.
- This is not production agent infrastructure.

## Future Improvements

- Add workflow metrics and structured audit events.
- Add stronger grounding checks if a real answer generator is introduced.
- Add a streaming agent endpoint if the workflow gains longer-running steps.
- Add richer planning policies only after evaluation coverage exists.
- Add tenant-aware authorization and access checks before exposing private enterprise documents.
