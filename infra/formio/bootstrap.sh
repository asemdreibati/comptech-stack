#!/usr/bin/env bash
# Creates or updates the Souqly category forms in Form.io from infra/formio/forms/*.json.
# Category forms are code: admins may prototype in the Form.io builder, but what ships is reviewed
# JSON. Safe to run repeatedly.  Requires curl and jq.
set -euo pipefail

FORMIO_URL=${FORMIO_URL:-http://localhost:3001}
FORMIO_EMAIL=${FORMIO_EMAIL:-admin@souqly.dev}
FORMIO_PASSWORD=${FORMIO_PASSWORD:-formio-admin-dev}
HERE=$(cd "$(dirname "$0")" && pwd)

for _ in $(seq 1 60); do
  # Form.io CE answers 400 on /health; /access returns 200 once it is ready.
  curl -sf -o /dev/null "$FORMIO_URL/access" && break
  sleep 2
done

TOKEN=$(curl -sS -D - -o /dev/null "$FORMIO_URL/admin/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$FORMIO_EMAIL" --arg p "$FORMIO_PASSWORD" '{data: {email: $e, password: $p}}')" \
  | awk 'tolower($1) == "x-jwt-token:" {print $2}' | tr -d '\r')
[[ -n $TOKEN ]] || { echo "Form.io login failed" >&2; exit 1; }

existing=$(mktemp)
trap 'rm -f "$existing"' EXIT
for file in "$HERE"/forms/*.json; do
  path=$(jq -r .path "$file")
  # A missing form is answered with plain-text "Not found", so go by the status code.
  status=$(curl -sS -o "$existing" -w '%{http_code}' "$FORMIO_URL/$path" -H "x-jwt-token: $TOKEN")
  if [[ $status == 200 ]]; then
    id=$(jq -r ._id "$existing")
    curl -sS --fail -X PUT "$FORMIO_URL/form/$id" -H "x-jwt-token: $TOKEN" -H 'Content-Type: application/json' \
      -d @"$file" >/dev/null
    echo "updated  $path"
  else
    curl -sS --fail "$FORMIO_URL/form" -H "x-jwt-token: $TOKEN" -H 'Content-Type: application/json' \
      -d @"$file" >/dev/null
    echo "created  $path"
  fi
done
