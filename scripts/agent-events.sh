#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 1 || "$#" -gt 2 || ! "$1" =~ ^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$ ]]; then
  printf 'Usage: bash scripts/agent-events.sh RUN_UUID [LAST_EVENT_ID]\n' >&2
  exit 2
fi
if [[ "$#" -eq 2 && ! "$2" =~ ^[0-9]+$ ]]; then
  printf 'LAST_EVENT_ID must be a non-negative event sequence.\n' >&2
  exit 2
fi

headers=(-H "X-Tenant-Id: ${TENANT_ID:-default}" -H "X-Actor-Id: ${ACTOR_ID:-anonymous}" -H 'Accept: text/event-stream')
if [[ "$#" -eq 2 ]]; then headers+=(-H "Last-Event-ID: $2"); fi
# The client disconnects on Ctrl-C; cancelling the run requires a separate explicit API call.
curl --no-buffer --fail-with-body --silent --show-error --connect-timeout 10 \
  "${BASE_URL:-http://localhost:8080}/api/v1/agent/runs/$1/events/stream" "${headers[@]}"
