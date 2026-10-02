#!/usr/bin/env bash
# End-to-end journey across every service, with real tokens and real infrastructure:
#   seller lists a phone (Form.io validates it) -> uploads a photo straight to MinIO -> publishes
#   -> restocks it in inventory -> search finds it in English and Arabic, in stock
#   -> the order service reserves everything -> search shows it sold out.
# Run after: docker compose --profile app up -d --wait
set -euo pipefail

KEYCLOAK=${KEYCLOAK_URL:-http://localhost:8180}/realms/souqly/protocol/openid-connect/token
CATALOG=${CATALOG_URL:-http://localhost:8082}/api/v1
INVENTORY=${INVENTORY_URL:-http://localhost:8081}/api/v1
SEARCH=${SEARCH_URL:-http://localhost:8083}/api/v1/search
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

failures=0
check() { # description, expected, actual
  if [[ $2 == "$3" ]]; then
    printf '  \033[32mok\033[0m   %-66s %s\n' "$1" "$3"
  else
    printf '  \033[31mFAIL\033[0m %-66s expected %s, got %s\n' "$1" "$2" "$3"
    failures=$((failures + 1))
  fi
}
client_token() { curl -sS --fail "$KEYCLOAK" -d grant_type=client_credentials -d "client_id=$1" -d "client_secret=$2" | jq -r .access_token; }
user_token() { curl -sS --fail "$KEYCLOAK" -d grant_type=password -d client_id=souqly-dev-cli -d "username=$1" -d "password=$2" | jq -r .access_token; }
# call METHOD URL TOKEN [BODY] -> prints the status code; the response body lands in $WORK/body
call() {
  local args=(-sS -o "$WORK/body" -w '%{http_code}' -X "$1" "$2" -H "Authorization: Bearer $3")
  [[ $# -ge 4 ]] && args+=(-H 'Content-Type: application/json' -d "$4")
  curl "${args[@]}"
}
body() { jq -r "$1" "$WORK/body"; }
# eventually DESCRIPTION EXPECTED JQ URL: polls a search until the expression matches (indexing is asynchronous)
eventually() {
  local actual=""
  for _ in $(seq 1 60); do
    actual=$(curl -sS "$4" | jq -r "$3")
    [[ $actual == "$2" ]] && break
    sleep 1
  done
  check "$1" "$2" "$actual"
}

echo "Setup: category schemas in Form.io, categories in the catalog"
"$HERE/../formio/bootstrap.sh" | sed 's/^/  /'
OPS=$(client_token souqly-ops souqly-ops-dev-secret)
for category in phones:category-phones:Phones:هواتف fashion:category-fashion:Fashion:أزياء; do
  IFS=: read -r slug form en ar <<<"$category"
  check "category $slug saved" 200 "$(call PUT "$CATALOG/categories/$slug" "$OPS" \
    "{\"name\": {\"en\": \"$en\", \"ar\": \"$ar\"}, \"formPath\": \"$form\"}")"
done

ACME=$(user_token seller-acme seller-password-dev)
ORDERS=$(client_token order-service order-service-dev-secret)
RUN=$(date +%s)
SKU="ACME-E2E-$RUN"
WORD="Nebula$RUN"

echo "Seller lists a phone"
check "listing with an out-of-range screen size is rejected" 422 "$(call POST "$CATALOG/products" "$ACME" "$(jq -n \
  --arg sku "$SKU-bad" '{sku: $sku, category: "phones", title: {en: "Bad"}, description: {en: "Bad"}, brand: "Acme",
  price: {amount: 10, currency: "AED"}, attributes: {storage: "128", color: "black", network: "5g", screenInches: 30}}')")"
check "listing created as a draft" 201 "$(call POST "$CATALOG/products" "$ACME" "$(jq -n --arg sku "$SKU" --arg word "$WORD" \
  '{sku: $sku, category: "phones", title: {en: ($word + " 5G Phone"), ar: "هاتف آيفون نيبولا"},
    description: {en: "Flagship camera, all-day battery", ar: "كاميرا رائدة وبطارية تدوم طوال اليوم"}, brand: "Acme",
    price: {amount: 2499, currency: "AED"},
    attributes: {storage: "256", color: "blue", network: "5g", screenInches: 6.7, dualSim: true}}')")"
PRODUCT=$(body .id)

echo "Seller uploads a photo straight to object storage"
echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==" \
  | base64 -d > "$WORK/photo.png"
SIZE=$(wc -c < "$WORK/photo.png" | tr -d ' ')
check "upload URL issued" 201 "$(call POST "$CATALOG/products/$PRODUCT/images" "$ACME" \
  "{\"contentType\": \"image/png\", \"sizeBytes\": $SIZE}")"
IMAGE=$(body .imageId)
UPLOAD_URL=$(body .uploadUrl)
check "photo PUT straight to MinIO" 200 "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$UPLOAD_URL" \
  -H 'Content-Type: image/png' --data-binary @"$WORK/photo.png")"
check "photo verified and published" 200 "$(call POST "$CATALOG/products/$PRODUCT/images/$IMAGE/complete" "$ACME")"
check "photo served publicly" 200 "$(curl -sS -o /dev/null -w '%{http_code}' "$(body '.images[0].url')")"
check "listing goes live" 200 "$(call POST "$CATALOG/products/$PRODUCT/publish" "$ACME")"

echo "Seller restocks it in inventory"
check "5 units in stock" 200 "$(call POST "$INVENTORY/stock/$SKU/restock" "$ACME" '{"quantity": 5}')"

echo "Buyers find it"
eventually "found by English title with a typo" 1 '.total' "$SEARCH?q=Nebla$RUN&category=phones"
eventually "found by Arabic title spelled without hamza" "$SKU" '.items[0].sku' "$SEARCH?q=%D8%A7%D9%8A%D9%81%D9%88%D9%86%20%D9%86%D9%8A%D8%A8%D9%88%D9%84%D8%A7&category=phones"
eventually "shown in stock" true '.items[0].inStock' "$SEARCH?q=$WORD"
eventually "storage facet offered" "256 GB" '[.facets.attributes[] | select(.name == "storage") | .values[0].label][0]' "$SEARCH?q=$WORD"

echo "The order service sells out the stock"
check "all 5 units reserved" 201 "$(call POST "$INVENTORY/reservations" "$ORDERS" \
  "{\"orderId\": \"order-$RUN\", \"lines\": [{\"sku\": \"$SKU\", \"quantity\": 5}]}")"
eventually "search shows it sold out" false '.items[0].inStock' "$SEARCH?q=$WORD"
eventually "in-stock filter hides it" 0 '.total' "$SEARCH?q=$WORD&inStock=true"

if [[ $failures -gt 0 ]]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "Marketplace journey passed"
