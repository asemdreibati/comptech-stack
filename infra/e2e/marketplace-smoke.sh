#!/usr/bin/env bash
# End-to-end journey across every service, with real tokens and real infrastructure:
#   a new seller applies (KYC form, documents to MinIO) -> compliance approves -> their next sign-in
#   carries a seller identity -> they list a phone (Form.io validates it), upload a photo, publish and
#   restock it -> search finds it in English and Arabic -> a buyer checks out (declined card, retry,
#   out of stock, success) -> search shows it sold out -> the buyer returns one unit -> the seller
#   approves, the warehouse receives and inspects it -> the buyer is refunded.
# Run after: docker compose --profile app up -d --wait
set -euo pipefail

KEYCLOAK_BASE=${KEYCLOAK_URL:-http://localhost:8180}
KEYCLOAK=$KEYCLOAK_BASE/realms/souqly/protocol/openid-connect/token
CATALOG=${CATALOG_URL:-http://localhost:8082}/api/v1
INVENTORY=${INVENTORY_URL:-http://localhost:8081}/api/v1
SEARCH=${SEARCH_URL:-http://localhost:8083}/api/v1/search
ORDERS=${ORDER_URL:-http://localhost:8084}/api/v1/orders
SELLERS=${SELLER_URL:-http://localhost:8085}/api/v1
RETURNS=${RETURNS_URL:-http://localhost:8086}/api/v1
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
# call METHOD URL TOKEN [BODY [CURL ARGS...]] -> prints the status code; the response body lands in $WORK/body
call() {
  local args=(-sS -o "$WORK/body" -w '%{http_code}' -X "$1" "$2" -H "Authorization: Bearer $3")
  [[ $# -ge 4 ]] && args+=(-H 'Content-Type: application/json' -d "$4" "${@:5}")
  curl "${args[@]}"
}
# claim TOKEN JQ -> reads a claim from a JWT's payload (base64url, unpadded)
claim() {
  local payload
  payload=$(cut -d. -f2 <<<"$1" | tr '_-' '/+')
  while (( ${#payload} % 4 )); do payload="$payload="; done
  base64 -d <<<"$payload" | jq -r "$2"
}
# checkout KEY SKU QUANTITY PAYMENT_METHOD -> status code; the order lands in $WORK/body.
# Retries while the order service has not yet seen the listing (its price book follows catalog events).
checkout() {
  local code=""
  for _ in $(seq 1 30); do
    code=$(curl -sS -o "$WORK/body" -w '%{http_code}' -X POST "$ORDERS" -H "Authorization: Bearer $BUYER" \
      -H "Idempotency-Key: $1" -H 'Content-Type: application/json' \
      -d "{\"items\": [{\"sku\": \"$2\", \"quantity\": $3}], \"paymentMethod\": \"$4\"}")
    [[ $code == 422 && $(body .code) == PRODUCT_UNAVAILABLE ]] || break
    sleep 1
  done
  echo "$code"
}
body() { jq -r "$1" "$WORK/body"; }
# upload_document APPLICATION TYPE FILE CONTENT_TYPE: signed upload URL, PUT straight to MinIO, verify
upload_document() {
  local size
  size=$(wc -c < "$3" | tr -d ' ')
  call POST "$SELLERS/applications/$1/documents" "$APPLICANT" \
    "{\"type\": \"$2\", \"contentType\": \"$4\", \"sizeBytes\": $size}" >/dev/null
  local document url
  document=$(body .document.id)
  url=$(body .uploadUrl)
  curl -sS -o /dev/null --fail -X PUT "$url" -H "Content-Type: $4" --data-binary @"$3"
  call POST "$SELLERS/applications/$1/documents/$document/complete" "$APPLICANT"
}
# eventually DESCRIPTION EXPECTED JQ URL [CURL ARGS...]: polls until the expression matches, for
# anything that arrives through events (search indexing, inventory learning a listing's seller)
eventually() {
  local actual=""
  for _ in $(seq 1 60); do
    actual=$(curl -sS "$4" "${@:5}" | jq -r "$3")
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
BUYER=$(user_token buyer buyer-password-dev)
RUN=$(date +%s)
HANDLE="nebula-$RUN"
SKU="NEBULA-E2E-$RUN"
WORD="Nebula$RUN"

echo "A new seller applies"
ADMIN=$(curl -sS --fail "$KEYCLOAK_BASE/realms/master/protocol/openid-connect/token" -d grant_type=password \
  -d client_id=admin-cli -d "username=${KEYCLOAK_ADMIN:-admin}" -d "password=${KEYCLOAK_ADMIN_PASSWORD:-admin}" \
  | jq -r .access_token)
kc_admin() { curl -sS --fail -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' "$@"; }
kc_admin -X POST "$KEYCLOAK_BASE/admin/realms/souqly/users" -d "$(jq -n --arg u "applicant-$RUN" '{username: $u,
  email: ($u + "@nebula.example"), emailVerified: true, firstName: "Nadia", lastName: "Nebula", enabled: true,
  credentials: [{type: "password", value: "applicant-password-dev", temporary: false}]}')"
APPLICANT_ID=$(kc_admin "$KEYCLOAK_BASE/admin/realms/souqly/users?username=applicant-$RUN&exact=true" | jq -r '.[0].id')
kc_admin -X POST "$KEYCLOAK_BASE/admin/realms/souqly/users/$APPLICANT_ID/role-mappings/realm" \
  -d "[$(kc_admin "$KEYCLOAK_BASE/admin/realms/souqly/roles/buyer")]"
APPLICANT=$(user_token "applicant-$RUN" applicant-password-dev)
KYC=$(jq -n --arg run "$RUN" '{legalName: "Nebula Gadgets Trading LLC", businessType: "company",
  tradeLicenceNumber: ("CN-" + $run), country: "AE", expectedMonthlyOrders: 300,
  address: "Office 12, Business Bay, Dubai", phone: "+971501234567",
  iban: ("AE07033" + $run + "0000000")}')
check "an IBAN with spaces is rejected by the KYC form" 422 "$(call POST "$SELLERS/applications" "$APPLICANT" \
  "$(jq -n --arg h "$HANDLE" --argjson kyc "$KYC" '{sellerId: $h, kyc: ($kyc + {iban: "AE07 0331"})}')")"
check "a seller ID that is already taken is refused" 409 "$(call POST "$SELLERS/applications" "$APPLICANT" \
  "$(jq -n --argjson kyc "$KYC" '{sellerId: "acme", kyc: $kyc}')")"
check "application started" 201 "$(call POST "$SELLERS/applications" "$APPLICANT" \
  "$(jq -n --arg h "$HANDLE" --argjson kyc "$KYC" '{sellerId: $h, kyc: $kyc}')")"
APPLICATION=$(body .id)
printf '%%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%%%EOF\n' > "$WORK/document.pdf"
printf '#!/bin/sh\necho owned\n' > "$WORK/script.pdf"
check "a script disguised as a PDF is rejected" 422 "$(upload_document "$APPLICATION" TRADE_LICENCE "$WORK/script.pdf" application/pdf)"
for type in TRADE_LICENCE OWNER_ID BANK_LETTER; do
  check "$type uploaded straight to MinIO and verified" 200 \
    "$(upload_document "$APPLICATION" "$type" "$WORK/document.pdf" application/pdf)"
done
check "application submitted for review" 202 "$(call POST "$SELLERS/applications/$APPLICATION/submit" "$APPLICANT")"

echo "Compliance reviews it"
COMPLIANCE=$(user_token compliance compliance-password-dev)
TASK=""
for _ in $(seq 1 60); do
  TASK=$(curl -sS "$SELLERS/reviews" -H "Authorization: Bearer $COMPLIANCE" \
    | jq -r --arg a "$APPLICATION" '.[] | select(.applicationId == $a) | .taskId')
  [[ -n $TASK ]] && break
  sleep 1
done
check "the application reaches the compliance queue" yes "$([[ -n $TASK ]] && echo yes || echo no)"
check "the applicant cannot review" 403 "$(call GET "$SELLERS/reviews" "$APPLICANT")"
check "compliance approves" 200 "$(call POST "$SELLERS/reviews/$TASK/decision" "$COMPLIANCE" '{"decision": "APPROVE"}')"
eventually "the application is approved" APPROVED .status "$SELLERS/applications/$APPLICATION" \
  -H "Authorization: Bearer $APPLICANT"
SELLER=$(user_token "applicant-$RUN" applicant-password-dev)
check "their next sign-in carries the seller ID" "$HANDLE" "$(claim "$SELLER" .seller_id)"

echo "Seller lists a phone"
check "listing with an out-of-range screen size is rejected" 422 "$(call POST "$CATALOG/products" "$SELLER" "$(jq -n \
  --arg sku "$SKU-bad" '{sku: $sku, category: "phones", title: {en: "Bad"}, description: {en: "Bad"}, brand: "Nebula",
  price: {amount: 10, currency: "AED"}, attributes: {storage: "128", color: "black", network: "5g", screenInches: 30}}')")"
check "listing created as a draft" 201 "$(call POST "$CATALOG/products" "$SELLER" "$(jq -n --arg sku "$SKU" --arg word "$WORD" \
  '{sku: $sku, category: "phones", title: {en: ($word + " 5G Phone"), ar: "هاتف آيفون نيبولا"},
    description: {en: "Flagship camera, all-day battery", ar: "كاميرا رائدة وبطارية تدوم طوال اليوم"}, brand: "Nebula",
    price: {amount: 2499, currency: "AED"},
    attributes: {storage: "256", color: "blue", network: "5g", screenInches: 6.7, dualSim: true}}')")"
PRODUCT=$(body .id)

echo "Seller uploads a photo straight to object storage"
echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==" \
  | base64 -d > "$WORK/photo.png"
SIZE=$(wc -c < "$WORK/photo.png" | tr -d ' ')
check "upload URL issued" 201 "$(call POST "$CATALOG/products/$PRODUCT/images" "$SELLER" \
  "{\"contentType\": \"image/png\", \"sizeBytes\": $SIZE}")"
IMAGE=$(body .imageId)
UPLOAD_URL=$(body .uploadUrl)
check "photo PUT straight to MinIO" 200 "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$UPLOAD_URL" \
  -H 'Content-Type: image/png' --data-binary @"$WORK/photo.png")"
check "photo verified and published" 200 "$(call POST "$CATALOG/products/$PRODUCT/images/$IMAGE/complete" "$SELLER")"
check "photo served publicly" 200 "$(curl -sS -o /dev/null -w '%{http_code}' "$(body '.images[0].url')")"
check "listing goes live" 200 "$(call POST "$CATALOG/products/$PRODUCT/publish" "$SELLER")"

echo "Seller restocks it in inventory"
eventually "inventory learns the seller from the catalog, before any restock" "$HANDLE" .sellerId \
  "$INVENTORY/stock/$SKU" -H "Authorization: Bearer $SELLER"
check "5 units in stock" 200 "$(call POST "$INVENTORY/stock/$SKU/restock" "$SELLER" '{"quantity": 5}')"

echo "Buyers find it"
eventually "found by English title with a typo" 1 '.total' "$SEARCH?q=Nebla$RUN&category=phones"
eventually "found by Arabic title spelled without hamza" "$SKU" '.items[0].sku' "$SEARCH?q=%D8%A7%D9%8A%D9%81%D9%88%D9%86%20%D9%86%D9%8A%D8%A8%D9%88%D9%84%D8%A7&category=phones"
eventually "shown in stock" true '.items[0].inStock' "$SEARCH?q=$WORD"
eventually "storage facet offered" "256 GB" '[.facets.attributes[] | select(.name == "storage") | .values[0].label][0]' "$SEARCH?q=$WORD"

echo "A buyer checks out"
available() { curl -sS "$INVENTORY/stock/$SKU" -H "Authorization: Bearer $SELLER" | jq -r .available; }
check "declined card: order created" 201 "$(checkout "declined-$RUN" "$SKU" 1 pm_card_chargeDeclined)"
check "  ...and cancelled with the reason" "CANCELLED PAYMENT_DECLINED" "$(body '.status + " " + .failure.code')"
check "  ...and its stock released" 5 "$(available)"
check "order for 3 units" 201 "$(checkout "first-$RUN" "$SKU" 3 pm_card_visa)"
check "  ...reserved, paid and confirmed" "PLACED STOCK_RESERVED PAID CONFIRMED" "$(body '[.history[].status] | join(" ")')"
check "  ...at the catalog price" "7497" "$(body .total)"
FIRST=$(body .id)
check "retried checkout returns the same order" 200 "$(checkout "first-$RUN" "$SKU" 3 pm_card_visa)"
check "  ...not a second one" "$FIRST" "$(body .id)"
check "order for 5 more is rejected: only 2 left" "REJECTED OUT_OF_STOCK" \
  "$(checkout "greedy-$RUN" "$SKU" 5 pm_card_visa >/dev/null; body '.status + " " + .failure.code')"
check "order for the last 2 units" CONFIRMED "$(checkout "last-$RUN" "$SKU" 2 pm_card_visa >/dev/null; body .status)"
check "buyer reads their order" 200 "$(call GET "$ORDERS/$FIRST" "$BUYER")"
check "a seller's token is not addressed to the order service" 401 "$(call GET "$ORDERS/$FIRST" "$ACME")"
check "stock is sold out" 0 "$(available)"
eventually "search shows it sold out" false '.items[0].inStock' "$SEARCH?q=$WORD"
eventually "in-stock filter hides it" 0 '.total' "$SEARCH?q=$WORD&inStock=true"

echo "The buyer returns one unit"
for _ in $(seq 1 30); do
  code=$(call POST "$RETURNS/returns" "$BUYER" "$(jq -n --arg o "$FIRST" --arg s "$SKU" \
    '{orderId: $o, reason: "CHANGED_MIND", comment: "Ordered one too many", items: [{sku: $s, quantity: 1}]}')" \
    -H "Idempotency-Key: return-$RUN")
  # The returns service learns about orders from events; retry until it has this one.
  [[ $code == 404 ]] || break
  sleep 1
done
check "return requested" 202 "$code"
RETURN=$(body .id)
check "  ...for the price paid (in fils)" 249900 "$(body '.refundAmount * 100 | round')"
check "returning more than was bought is refused" 409 "$(call POST "$RETURNS/returns" "$BUYER" \
  "$(jq -n --arg o "$FIRST" --arg s "$SKU" '{orderId: $o, reason: "DAMAGED", items: [{sku: $s, quantity: 3}]}')" \
  -H "Idempotency-Key: greedy-return-$RUN")"
eventually "the return policy sends it to the seller" AWAITING_SELLER .status "$RETURNS/returns/$RETURN" \
  -H "Authorization: Bearer $BUYER"
check "another seller cannot decide it" 404 "$(call POST "$RETURNS/returns/$RETURN/seller-decision" "$ACME" \
  '{"decision": "APPROVE"}')"
check "the seller approves" 200 "$(call POST "$RETURNS/returns/$RETURN/seller-decision" "$SELLER" '{"decision": "APPROVE"}')"
WAREHOUSE=$(user_token warehouse warehouse-password-dev)
for _ in $(seq 1 30); do
  code=$(call POST "$RETURNS/returns/$RETURN/received" "$WAREHOUSE")
  [[ $code == 200 ]] && break
  sleep 1
done
check "the warehouse receives the parcel" 200 "$code"
eventually "it waits for inspection" RECEIVED .status "$RETURNS/returns/$RETURN" -H "Authorization: Bearer $BUYER"
check "inspection passes" 200 "$(call POST "$RETURNS/returns/$RETURN/inspection" "$WAREHOUSE" '{"result": "PASS"}')"
eventually "the buyer is refunded" REFUNDED .status "$RETURNS/returns/$RETURN" -H "Authorization: Bearer $BUYER"
check "  ...through the payment provider" "re_return-$RETURN" "$(call GET "$RETURNS/returns/$RETURN" "$BUYER" >/dev/null; body .refundId)"

if [[ $failures -gt 0 ]]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "Marketplace journey passed"
