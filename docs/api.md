# API Reference

Base URL for local development:

```text
http://localhost:8080
```

## Enterprise Headers

Most document, retrieval, context, query, and agent endpoints accept:

```text
X-Tenant-Id: tenant-a
X-Actor-Id: actor-1
X-Trace-Id: trace-123
```

`X-Trace-Id` is used by query and agent flows when supplied. Missing tenant/actor headers fall back to `default` and `anonymous` for local demos. This is not production authentication.

The default tenant/actor behavior is for local demos only. A production deployment should require verified identity from OAuth/JWT claims, an API gateway, or another trusted authentication layer before setting tenant and actor context.

## Health

```bash
curl http://localhost:8080/api/v1/health
```

Returns app status and timestamp.

## Documents

Upload a raw document:

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -F "file=@examples/security-handbook.md;type=text/markdown"
```

Upload a private document inside the tenant:

```bash
curl -X POST "http://localhost:8080/api/v1/documents?visibility=PRIVATE" \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -F "file=@examples/security-handbook.md;type=text/markdown"
```

List documents:

```bash
curl "http://localhost:8080/api/v1/documents?limit=50&offset=0"
```

Get one document:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}
```

List ingestion jobs for a document:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/ingestion-jobs \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1"
```

## Chunking

Create chunks idempotently:

```bash
curl -X POST http://localhost:8080/api/v1/documents/{documentId}/chunks
```

Force regeneration:

```bash
curl -X POST "http://localhost:8080/api/v1/documents/{documentId}/chunks?force=true"
```

Inspect chunks:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/chunks
```

## Embeddings

Embed child chunks:

```bash
curl -X POST http://localhost:8080/api/v1/documents/{documentId}/embed
```

Inspect embedding status:

```bash
curl http://localhost:8080/api/v1/documents/{documentId}/embedding-status
```

## Retrieval Debug

```bash
curl -X POST http://localhost:8080/api/v1/retrieval/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "document handling security",
    "documentIds": ["{documentId}"],
    "topK": 5
  }'
```

Returns vector candidates, full-text candidates, and RRF-fused candidates.

## Context Debug

```bash
curl -X POST http://localhost:8080/api/v1/context/debug \
  -H "Content-Type: application/json" \
  -d '{
    "query": "document handling security",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000
  }'
```

Returns reranked candidates, selected child chunks, expanded parent contexts, citations, and final context text.

## Query

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -H "X-Trace-Id: trace-demo-1" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "demo-session",
    "question": "What does the security policy say about document handling?",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": false
  }'
```

`debug=false` returns public response fields: `traceId`, `answer`, and `citations`.

`debug=true` additionally returns context/debug fields, limitations, and retrieval cache status.

## SSE Query

```bash
curl -N -X POST http://localhost:8080/api/v1/query/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "sessionId": "demo-session",
    "question": "What does the security policy say about document handling?",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": false
  }'
```

Events include `received`, `retrieving`, `reranking`, `building_context`, `generating`, `message`, `completed`, and `error`.

This endpoint is `POST` because it accepts a JSON body. Browser `EventSource` normally uses `GET`, so browser-only clients need an adapter or a GET-specific endpoint.

## Agent Query

```bash
curl -X POST http://localhost:8080/api/v1/agent/query \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-Actor-Id: actor-1" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "demo-session",
    "question": "What does the security policy say about document handling?",
    "documentIds": ["{documentId}"],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": true
  }'
```

Returns answer, citations, workflow status, and debug-only plan/execution/critique details.

## Actuator

Safe local health/info endpoints:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/info
```

## Validation Errors

Common bad-request cases:

- Blank `question` or `query`.
- `topK < 1`.
- `contextBudgetChars < 1`.
- Unsupported document type during extraction.
- Empty extracted text.
- Invalid tenant/actor header characters.
- Invalid `visibility` value.

Unexpected errors return a generic server error response instead of leaking low-level details.
