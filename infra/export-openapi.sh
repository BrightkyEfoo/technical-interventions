#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
output=$(mktemp)
trap 'rm -f "$output"' EXIT
curl --silent --show-error --fail "${SERVICEOPS_OPENAPI_URL:-http://127.0.0.1:8080/v3/api-docs.yaml}" > "$output"
grep -q '/api/v1/requests/{id}/resolve:' "$output"
grep -q '/api/v1/notifications:' "$output"
cp "$output" contracts/openapi.yaml
