#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 1 || "$#" -gt 10 ]]; then
  printf 'Usage: bash scripts/agent-demo.sh DOCUMENT_ID [DOCUMENT_ID ...] (1-10 documents)\n' >&2
  exit 2
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-default}"
ACTOR_ID="${ACTOR_ID:-anonymous}"
QUESTION="${QUESTION:-Inspect processing status and explain what remains incomplete.}"

body="$(node --input-type=module - "$QUESTION" "$@" <<'JS'
const [question, ...documentIds] = process.argv.slice(2);
if (documentIds.some(id => !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(id))) {
  throw new Error("Use full document UUIDs returned by the upload API");
}
console.log(JSON.stringify({ question, documentIds }));
JS
)"

headers=(-H "X-Tenant-Id: $TENANT_ID" -H "X-Actor-Id: $ACTOR_ID")
created="$(curl --fail-with-body --silent --show-error --max-time 20 \
  -X POST "$BASE_URL/api/v1/agent/runs" "${headers[@]}" \
  -H 'Content-Type: application/json' -d "$body")"
run_id="$(printf '%s' "$created" | node --input-type=module -e '
  let text = ""; for await (const part of process.stdin) text += part;
  const {runId} = JSON.parse(text);
  if (typeof runId !== "string") throw new Error("No runId in creation response");
  console.log(runId);
')"
printf 'Created run %s; waiting for the separately started worker.\n' "$run_id"

for ((attempt=0; attempt<240; attempt++)); do
  result="$(curl --fail-with-body --silent --show-error --max-time 20 \
    "$BASE_URL/api/v1/agent/runs/$run_id" "${headers[@]}")"
  status="$(printf '%s' "$result" | node --input-type=module -e '
    let text = ""; for await (const part of process.stdin) text += part;
    console.log(JSON.parse(text).status);
  ')"
  if [[ "$status" == WAITING_APPROVAL ]]; then
    printf '%s\n' "$result"
    printf 'Paused for your decision. Review pendingApproval, then use the approval commands in docs/agent-harness.md. No automatic approval.\n'
    exit 0
  fi
  if [[ "$status" == RECOVERY_REQUIRED ]]; then
    printf '%s\n' "$result"
    printf 'Write outcome needs reconciliation with its exact ingestion job. Do not resubmit or force retry; see docs/review/agent-harness-phase-3.md.\n' >&2
    exit 1
  fi
  if [[ "$status" == SUCCEEDED || "$status" == FAILED || "$status" == CANCELLED ]]; then
    printf '%s\n' "$result"
    curl --fail-with-body --silent --show-error --max-time 20 \
      "$BASE_URL/api/v1/agent/runs/$run_id/events?afterSequence=0" "${headers[@]}"
    printf '\n'
    [[ "$status" == SUCCEEDED ]]
    exit
  fi
  sleep 1
done

printf 'Demo polling stopped; inspect run %s. A QUEUED run requires a matching worker.\n' "$run_id" >&2
exit 1
