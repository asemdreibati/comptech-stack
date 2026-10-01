#!/usr/bin/env bash
# End-to-end checks through the WSO2 gateway, with real Keycloak tokens. Run after bootstrap.sh.
# Fails on the first check that does not hold.
set -euo pipefail

GATEWAY=${GATEWAY:-https://localhost:8243/inventory/v1}
SERVICE=${SERVICE:-http://localhost:8081/api/v1}
TOKEN_URL=${KEYCLOAK_URL:-http://localhost:8180}/realms/souqly/protocol/openid-connect/token

failures=0
check() { # description, expected status, actual status
  if [[ $2 == "$3" ]]; then
    printf '  \033[32mok\033[0m   %-62s %s\n' "$1" "$3"
  else
    printf '  \033[31mFAIL\033[0m %-62s expected %s, got %s\n' "$1" "$2" "$3"
    failures=$((failures + 1))
  fi
}
token() { curl -sS --fail "$TOKEN_URL" -d grant_type=client_credentials -d "client_id=$1" -d "client_secret=$2" | jq -r .access_token; }
status() { curl -sk -o /dev/null -w '%{http_code}' "$@"; }

ACME=$(token seller-acme-integration seller-acme-dev-secret)
OPS=$(token souqly-ops souqly-ops-dev-secret)
ORDERS=$(token order-service order-service-dev-secret)
SKU="ACME-SMOKE-$(date +%s)"
JSON=(-H 'Content-Type: application/json')

# The API is published asynchronously: until the gateway has loaded it, unknown paths are 404.
for _ in $(seq 1 30); do
  [[ $(status "$GATEWAY/stock/$SKU") == 401 ]] && break
  sleep 2
done

echo "Gateway: authentication and subscriptions"
check "no token is rejected at the gateway" 401 "$(status "$GATEWAY/stock/$SKU")"
check "client without a subscription is rejected" 403 "$(status "$GATEWAY/stock/$SKU" -H "Authorization: Bearer $ORDERS")"
check "internal reservations API is not routed" 404 \
  "$(status -X POST "$GATEWAY/reservations" -H "Authorization: Bearer $ACME" "${JSON[@]}" -d '{}')"

echo "Service behind the gateway: permissions and ownership"
check "seller restocks a new SKU (becomes its owner)" 200 \
  "$(status -X POST "$GATEWAY/stock/$SKU/restock" -H "Authorization: Bearer $ACME" "${JSON[@]}" -d '{"quantity": 25}')"
owner=$(curl -sk "$GATEWAY/stock/$SKU" -H "Authorization: Bearer $ACME" | jq -r .sellerId)
check "seller identity reached the service through the gateway" acme "$owner"
check "seller cannot arm a flash sale" 403 "$(status -X PUT "$GATEWAY/flash-sales/$SKU" -H "Authorization: Bearer $ACME")"
status -X POST "$SERVICE/stock/HOUSE-$SKU/restock" -H "Authorization: Bearer $OPS" "${JSON[@]}" -d '{"quantity": 5}' >/dev/null
check "seller cannot restock marketplace-owned stock" 403 \
  "$(status -X POST "$GATEWAY/stock/HOUSE-$SKU/restock" -H "Authorization: Bearer $ACME" "${JSON[@]}" -d '{"quantity": 1}')"
check "service rejects calls that skip authentication" 401 "$(status "$SERVICE/stock/$SKU")"

echo "Gateway: Starter plan rate limit"
throttled=0
for _ in $(seq 1 40); do
  [[ $(status "$GATEWAY/stock/$SKU" -H "Authorization: Bearer $ACME") == 429 ]] && throttled=$((throttled + 1))
done
check "burst of 40 calls is throttled (some 429s)" yes "$([[ $throttled -gt 0 ]] && echo yes || echo no)"

if [[ $failures -gt 0 ]]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All gateway checks passed"
