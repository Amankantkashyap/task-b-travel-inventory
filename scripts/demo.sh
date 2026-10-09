#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
SEEDS=fixtures/task-b/seed-payloads.json
DEMO_REFERENCE="demo-$(date +%s)-$$"
TEMP_FILES=$(mktemp -d)
trap 'rm -rf "$TEMP_FILES"' EXIT
command -v jq >/dev/null
curl -fsS "$BASE/health" | jq -e '.status == "UP"' >/dev/null
request() {
  local expected=$1 method=$2 path=$3 body=${4:-}
  local actual
  if [ -n "$body" ]; then
    actual=$(curl -sS -o "$TEMP_FILES/response.json" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' -d "$body" "$BASE$path")
  else
    actual=$(curl -sS -o "$TEMP_FILES/response.json" -w '%{http_code}' -X "$method" "$BASE$path")
  fi
  if [ "$actual" != "$expected" ]; then
    printf 'Expected %s, received %s for %s %s\n' "$expected" "$actual" "$method" "$path" >&2
    cat "$TEMP_FILES/response.json" >&2
    exit 1
  fi
  printf '%s %s → %s\n' "$method" "$path" "$actual" >&2
}
ids=()
for kind in flight hotel rail trip_fee agent_call_fee; do
  body=$(jq --arg kind "$kind" --arg ref "$DEMO_REFERENCE-$kind" '.[$kind] | .external_id=$ref' "$SEEDS")
  request 201 POST /transactions "$body"
  ids+=("$(jq -r '.id' "$TEMP_FILES/response.json")")
done
flight=${ids[0]}
body=$(jq --arg ref "$DEMO_REFERENCE-flight" '.flight | .external_id=$ref' "$SEEDS")
request 409 POST /transactions "$body"
jq -e --arg id "$flight" '.id == $id' "$TEMP_FILES/response.json" >/dev/null
request 200 GET '/transactions?limit=100&offset=0'
request 200 GET "/transactions/$flight"
request 200 PATCH "/transactions/$flight" '{"total":220.00}'
body=$(printf '%s\n' "${ids[@]}" | jq -R . | jq -s '{transaction_ids:.}')
request 201 POST /invoices "$body"
invoice=$(jq -r '.id' "$TEMP_FILES/response.json")
jq -e '.totals.grand_total == 685.70 and (.transactions | length) == 5' "$TEMP_FILES/response.json" >/dev/null
request 200 GET "/invoices/$invoice"
request 200 GET '/invoices?limit=10&offset=0'
request 409 PATCH "/transactions/$flight" '{"total":999.00}'
request 409 DELETE "/transactions/$flight"
request 200 PATCH "/invoices/$invoice" "$(jq -n --arg id "$flight" '{transaction_id:$id}')"
request 204 DELETE "/invoices/$invoice"
for id in "${ids[@]}"; do
  request 200 GET "/transactions/$id"
  request 204 DELETE "/transactions/$id"
done
printf 'Verified trip total, duplicate identity, invoice guards, replacement, deletion, and transaction preservation. Demo records cleaned up.\n'
