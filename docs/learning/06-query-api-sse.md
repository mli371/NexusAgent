# Milestone 6: Query API + SSE

## What This Milestone Solves

Milestone 6 turns the retrieval/context pipeline into a user-facing query API.

Earlier milestones could upload files, chunk text, embed child chunks, retrieve candidates, rerank them, and build citation-aware context. This milestone adds the orchestration layer that a client can call:

```text
question
  -> context construction
  -> local placeholder answer generation
  -> answer + citations + traceId
```

It also adds an SSE endpoint so a client can observe query progress as the backend moves through retrieval, reranking, context construction, and answer generation.

## Main Classes And Responsibilities

- `QueryController`: exposes `POST /api/v1/query` and `POST /api/v1/query/stream`.
- `QueryRequest`: request body with `sessionId`, `question`, optional `documentIds`, optional `topK`, optional `contextBudgetChars`, and optional `debug`.
- `QueryResponse`: response body with `answer`, `citations`, `traceId`, and optional debug fields.
- `QueryStreamEvent`: event payload for SSE responses.
- `QueryOrchestrationService`: coordinates session state, cache lookup, context construction, answer generation, debug response shaping, and timeout fallback.
- `AnswerGenerator`: boundary for answer generation.
- `LocalTemplateAnswerGenerator`: local placeholder generator that creates a simple answer from `finalContextText` and citations. It does not call an external model.
- `SessionStateService`: boundary for query/session lifecycle events.
- `InMemorySessionStateService`: process-local session event recorder for Milestone 6.
- `RetrievalCacheService`: boundary for caching context build results.
- `NoOpRetrievalCacheService`: placeholder cache that always misses and stores nothing.
- `ToolOutputStore`: boundary for intermediate outputs such as built context and generated answer.
- `NoOpToolOutputStore`: placeholder output store that accepts calls but persists nothing.

## Data Flow

For `POST /api/v1/query`:

```text
Client
  -> QueryController
  -> QueryOrchestrationService
  -> SessionStateService.recordStarted
  -> RetrievalCacheService.get
  -> ContextBuilder.build
       -> Hybrid retrieval
       -> Reranking
       -> Parent context expansion
       -> Citation formatting
  -> RetrievalCacheService.put
  -> ToolOutputStore.save(context)
  -> AnswerGenerator.generate
  -> ToolOutputStore.save(answer)
  -> SessionStateService.recordCompleted
  -> QueryResponse
```

For `POST /api/v1/query/stream`, the same core work is exposed through SSE stage events:

```text
received
retrieving
reranking
building_context
generating
message
completed
```

If an error escapes the pipeline, the stream emits an `error` event.

The stream endpoint uses `POST` because the query request is a JSON body. This works with `curl`, `fetch`-style clients, and server-side HTTP clients that can consume `text/event-stream`. Browser `EventSource` normally uses `GET`, so supporting EventSource directly would require a separate GET endpoint or an adapter that maps a stored request to a stream.

## Why This Design Is Reasonable

The controller only handles HTTP routing. The orchestration service owns the application flow, which keeps the endpoint easy to test and explain.

`ContextBuilder` remains the single owner of retrieval, reranking, parent expansion, budget trimming, and citations. The query layer does not duplicate those responsibilities.

`AnswerGenerator` is an interface because answer generation is an integration boundary. Milestone 6 uses a local placeholder generator so local tests and demos do not require external credentials. A later implementation can replace it with a real chat model or answer service.

The state/cache/output interfaces exist now because the query layer is where they belong architecturally. Their Redis-backed implementations are intentionally deferred to Milestone 7, so this milestone does not pretend durable or shared cache behavior exists.

## Debug Behavior

When `debug=false`, the query response returns:

- `traceId`
- `answer`
- `citations`

When `debug=true`, the response also includes:

- `finalContextText`
- `retrievalDebug`
- `contextDebug`
- `limitations`

This keeps the normal API response small while still allowing local inspection of retrieval and context behavior.

Blank `question`, `topK < 1`, and `contextBudgetChars < 1` are rejected with bad-request errors before the query pipeline runs.

## Timeout And Fallback Behavior

The query layer has two configurable timeouts:

- `nexus.query.context-timeout`
- `nexus.query.answer-timeout`

If context construction times out, the service returns an empty local fallback context. The local answer generator then produces a no-context answer.

If answer generation times out, the service returns a local timeout answer.

The no-context answer is explicit: it says there is insufficient retrieved context and does not create fake citations or grounded claims.

This is enough for a local project milestone. A production version would also need better observability, explicit retry policy by error type, cancellation handling, and durable failure tracking.

## Placeholder State And Cache Behavior

Milestone 6 intentionally does not use Redis.

- `InMemorySessionStateService` records session events only inside the current JVM process.
- `NoOpRetrievalCacheService` always misses and does not persist results.
- `NoOpToolOutputStore` does not persist intermediate outputs.

These classes are not fake Redis implementations. Their names, comments, and docs make the limitation explicit.

## API Examples

Query:

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "local-demo-session",
    "question": "What does the security policy say?",
    "documentIds": ["{document-id}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": false
  }'
```

Streaming query:

```bash
curl -N -X POST http://localhost:8080/api/v1/query/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "sessionId": "local-demo-session",
    "question": "What does the security policy say?",
    "documentIds": ["{document-id}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": false
  }'
```

## How To Run

Start dependencies:

```bash
docker compose up -d
```

Run the app:

```bash
mvn spring-boot:run
```

Run tests:

```bash
mvn test
```

Run only Milestone 6 tests:

```bash
mvn test -Dtest='com.nexusagent.query.**.*Test'
```

## Design Defense

The query API is intentionally thin and orchestration-focused. It does not reimplement retrieval or context construction; it composes the already-tested pipeline from Milestones 4 and 5.

The local template answer generator is honest about its limits. It produces a grounded response from retrieved context and citations, but it is not a production-quality answer model.

The SSE endpoint is useful even before real token streaming because it gives clients progress visibility through the query pipeline. The event names match meaningful backend stages rather than hiding all work behind one long HTTP response.

The cache and state abstractions are introduced before Redis because they shape the query orchestration boundary. The implementation remains deliberately simple until Milestone 7.

## Known Limitations

- `LocalTemplateAnswerGenerator` is not a real LLM-backed answer generator.
- The SSE endpoint emits stage events and a final message; it does not stream generated model tokens.
- State is process-local and disappears when the app restarts.
- Retrieval cache and tool output storage are no-op placeholders.
- Redis-backed implementations are added later in Milestone 7.
- Context budget is still character-based, not model-token-based.
- The Plan-Execute-Critique workflow is added later in Milestone 8.
- There is no authentication, authorization, tenant isolation, or production observability yet.

## Future Improvements

- Replace `NoOpRetrievalCacheService` with a Redis-backed cache in Milestone 7.
- Replace `NoOpToolOutputStore` with Redis-backed intermediate output storage.
- Replace `InMemorySessionStateService` with Redis-backed session state.
- Add a real answer provider behind `AnswerGenerator`.
- Add token-aware streaming when a streaming answer provider exists.
- Add metrics and tracing around retrieval, reranking, context building, answer generation, and SSE lifecycle events.
