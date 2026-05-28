#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DOC_FILE="${DOC_FILE:-examples/security-handbook.md}"
DOC_ID_FILE="${DOC_ID_FILE:-.demo-document-id}"
QUERY="${QUERY:-What does the security policy say about document handling?}"

usage() {
  cat <<'USAGE'
Usage: scripts/demo.sh <command>

Commands:
  start-stack      Start PostgreSQL, Redis, and MinIO with Docker Compose
  health           Check application health
  upload           Upload DOC_FILE and save the returned document id
  chunk            Extract and chunk the saved or provided document id
  embed            Embed child chunks for the saved or provided document id
  retrieval-debug  Run hybrid retrieval debug
  context-debug    Build citation-aware context
  query            Run the normal query API
  query-sse        Run the SSE query API
  agent-query      Run the Plan-Execute-Critique API
  redis-keys       Inspect local Redis keys and TTLs
  all              Run health, upload, chunk, embed, retrieval-debug, context-debug, query, agent-query

Environment:
  BASE_URL=http://localhost:8080
  DOC_FILE=examples/security-handbook.md
  DOC_ID=<existing document id>
  QUERY="What does the security policy say about document handling?"
USAGE
}

doc_id() {
  if [[ -n "${DOC_ID:-}" ]]; then
    printf '%s' "$DOC_ID"
    return
  fi
  if [[ -f "$DOC_ID_FILE" ]]; then
    tr -d '[:space:]' < "$DOC_ID_FILE"
    return
  fi
  printf 'No document id found. Run scripts/demo.sh upload or set DOC_ID.\n' >&2
  exit 1
}

query_body() {
  python3 - "$@" <<'PY'
import json
import sys

doc_id = sys.argv[1]
query = sys.argv[2]
debug = sys.argv[3].lower() == "true"
print(json.dumps({
    "sessionId": "demo-session",
    "question": query,
    "query": query,
    "documentIds": [doc_id],
    "topK": 5,
    "contextBudgetChars": 2000,
    "debug": debug,
}))
PY
}

debug_body() {
  python3 - "$@" <<'PY'
import json
import sys

doc_id = sys.argv[1]
query = sys.argv[2]
print(json.dumps({
    "query": query,
    "documentIds": [doc_id],
    "topK": 5,
    "contextBudgetChars": 2000,
}))
PY
}

post_json() {
  local path="$1"
  local body="$2"
  curl -sS -X POST "$BASE_URL$path" \
    -H "Content-Type: application/json" \
    -d "$body"
  printf '\n'
}

cmd="${1:-}"
case "$cmd" in
  start-stack)
    docker compose up -d
    docker compose ps
    ;;
  health)
    curl -sS "$BASE_URL/api/v1/health"
    printf '\n'
    ;;
  upload)
    response="$(curl -sS -X POST "$BASE_URL/api/v1/documents" -F "file=@${DOC_FILE};type=text/markdown")"
    printf '%s\n' "$response"
    printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' > "$DOC_ID_FILE"
    printf 'Saved document id to %s\n' "$DOC_ID_FILE"
    ;;
  chunk)
    curl -sS -X POST "$BASE_URL/api/v1/documents/$(doc_id)/chunks"
    printf '\n'
    ;;
  embed)
    curl -sS -X POST "$BASE_URL/api/v1/documents/$(doc_id)/embed"
    printf '\n'
    ;;
  retrieval-debug)
    post_json "/api/v1/retrieval/debug" "$(debug_body "$(doc_id)" "$QUERY")"
    ;;
  context-debug)
    post_json "/api/v1/context/debug" "$(debug_body "$(doc_id)" "$QUERY")"
    ;;
  query)
    post_json "/api/v1/query" "$(query_body "$(doc_id)" "$QUERY" true)"
    ;;
  query-sse)
    curl -N -X POST "$BASE_URL/api/v1/query/stream" \
      -H "Content-Type: application/json" \
      -H "Accept: text/event-stream" \
      -d "$(query_body "$(doc_id)" "$QUERY" false)"
    ;;
  agent-query)
    post_json "/api/v1/agent/query" "$(query_body "$(doc_id)" "$QUERY" true)"
    ;;
  redis-keys)
    docker compose exec redis redis-cli --scan
    printf '\nTTL examples:\n'
    docker compose exec redis sh -c 'for key in $(redis-cli --scan | head -20); do printf "%s " "$key"; redis-cli ttl "$key"; done'
    ;;
  all)
    "$0" health
    "$0" upload
    "$0" chunk
    "$0" embed
    "$0" retrieval-debug
    "$0" context-debug
    "$0" query
    "$0" agent-query
    ;;
  *)
    usage
    exit 1
    ;;
esac
