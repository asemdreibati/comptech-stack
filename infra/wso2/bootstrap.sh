#!/usr/bin/env bash
# Configures WSO2 API Manager for Souqly through its REST APIs (no clicking in the consoles):
#   1. registers the Souqly Keycloak realm as a key manager
#   2. creates the Starter and Business subscription plans
#   3. imports, deploys and publishes the Inventory API from its OpenAPI definition
#   4. creates the ACME seller application, maps its existing Keycloak client and subscribes it
# Safe to run repeatedly: each step checks whether its result already exists.
#
# Requires curl and jq. Usage:  docker compose --profile app --profile gateway up -d --wait
#                               infra/wso2/bootstrap.sh
set -euo pipefail

APIM_URL=${APIM_URL:-https://localhost:9443}
APIM_USER=${APIM_USER:-admin}
APIM_PASSWORD=${APIM_PASSWORD:-admin}
KEYCLOAK_URL=${KEYCLOAK_URL:-http://localhost:8180}                    # public: the issuer in tokens
KEYCLOAK_INTERNAL_URL=${KEYCLOAK_INTERNAL_URL:-http://keycloak:8080}   # how the gateway reaches it
KEYCLOAK_ADMIN=${KEYCLOAK_ADMIN:-admin}
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-admin}
INVENTORY_BACKEND=${INVENTORY_BACKEND:-http://inventory-service:8081/api/v1}
REALM=souqly
HERE=$(cd "$(dirname "$0")" && pwd)

log() { printf '\033[1m==>\033[0m %s\n' "$*"; }
# The local APIM uses its self-signed default certificate, hence -k.
apim() { curl -sSk --fail-with-body -H "Authorization: Bearer $APIM_TOKEN" "$@"; }
apim_json() { apim -H 'Content-Type: application/json' "$@"; }

log "Waiting for API Manager at $APIM_URL"
for _ in $(seq 1 120); do
  [[ $(curl -sk -o /dev/null -w '%{http_code}' "$APIM_URL/api/am/devportal/v3/apis") == 200 ]] && break
  sleep 5
done

# --- Keycloak --------------------------------------------------------------------------------
# WSO2's Keycloak connector asks for an OAuth scope literally named "default" when it looks up
# clients. Declaring client scopes in the realm export would stop Keycloak from creating its
# built-in scopes, so this one is added through the admin API instead.
log "Adding the 'default' client scope the WSO2 connector requests"
KC_TOKEN=$(curl -sS --fail "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  --data-urlencode "username=$KEYCLOAK_ADMIN" --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD" | jq -r .access_token)
kc() { curl -sS -H "Authorization: Bearer $KC_TOKEN" -H 'Content-Type: application/json' "$@"; }
SCOPE_ID=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/client-scopes" | jq -r '.[] | select(.name == "default") | .id')
if [[ -z $SCOPE_ID ]]; then
  kc --fail "$KEYCLOAK_URL/admin/realms/$REALM/client-scopes" -d '{"name": "default", "protocol": "openid-connect",
    "description": "Requested by the WSO2 API Manager Keycloak connector",
    "attributes": {"include.in.token.scope": "false", "display.on.consent.screen": "false"}}'
  SCOPE_ID=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/client-scopes" | jq -r '.[] | select(.name == "default") | .id')
fi
WSO2_CLIENT=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=wso2-apim" | jq -r '.[0].id')
kc --fail -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/clients/$WSO2_CLIENT/optional-client-scopes/$SCOPE_ID"

# --- API Manager admin token -----------------------------------------------------------------
log "Obtaining an API Manager admin token"
DCR=$(curl -sSk --fail -u "$APIM_USER:$APIM_PASSWORD" -H 'Content-Type: application/json' \
  "$APIM_URL/client-registration/v0.17/register" \
  -d '{"clientName": "souqly_bootstrap", "owner": "admin", "grantType": "password refresh_token", "saasApp": true}')
APIM_TOKEN=$(curl -sSk --fail -u "$(jq -r .clientId <<<"$DCR"):$(jq -r .clientSecret <<<"$DCR")" "$APIM_URL/oauth2/token" \
  -d grant_type=password --data-urlencode "username=$APIM_USER" --data-urlencode "password=$APIM_PASSWORD" \
  --data-urlencode 'scope=apim:admin apim:api_create apim:api_publish apim:api_view apim:subscribe apim:app_manage apim:sub_manage apim:admin_tier_manage apim:keymanagers_manage' \
  | jq -r .access_token)

# --- Key manager -----------------------------------------------------------------------------
if apim "$APIM_URL/api/am/admin/v4/key-managers" | jq -e '.list[] | select(.name == "Keycloak")' >/dev/null; then
  log "Key manager 'Keycloak' already registered"
else
  log "Registering Keycloak as a key manager"
  KC_REALM="$KEYCLOAK_INTERNAL_URL/realms/$REALM"
  jq -n --arg issuer "$KEYCLOAK_URL/realms/$REALM" --arg kc "$KC_REALM" --arg public "$KEYCLOAK_URL/realms/$REALM" '{
    name: "Keycloak",
    displayName: "Keycloak (Souqly realm)",
    type: "KeyCloak",
    description: "Souqly identity provider. The gateway validates Keycloak JWTs locally against the realm JWKS.",
    enabled: true,
    tokenType: "DIRECT",
    issuer: $issuer,
    certificates: {type: "JWKS", value: "\($kc)/protocol/openid-connect/certs"},
    consumerKeyClaim: "azp",
    scopesClaim: "scope",
    enableSelfValidationJWT: true,
    enableTokenGeneration: true,
    enableMapOAuthConsumerApps: true,
    enableOAuthAppCreation: false,
    availableGrantTypes: ["client_credentials", "authorization_code", "refresh_token"],
    clientRegistrationEndpoint: "\($kc)/clients-registrations/openid-connect",
    introspectionEndpoint: "\($kc)/protocol/openid-connect/token/introspect",
    tokenEndpoint: "\($kc)/protocol/openid-connect/token",
    displayTokenEndpoint: "\($public)/protocol/openid-connect/token",
    revokeEndpoint: "\($kc)/protocol/openid-connect/revoke",
    userInfoEndpoint: "\($kc)/protocol/openid-connect/userinfo",
    authorizeEndpoint: "\($public)/protocol/openid-connect/auth",
    additionalProperties: {client_id: "wso2-apim", client_secret: "wso2-apim-dev-secret", self_validate_jwt: true}
  }' | apim_json "$APIM_URL/api/am/admin/v4/key-managers" -d @- >/dev/null
fi

# --- Subscription plans ----------------------------------------------------------------------
create_plan() { # name, description, requests per minute, burst per second, billing plan
  if apim "$APIM_URL/api/am/admin/v4/throttling/policies/subscription" | jq -e --arg n "$1" '.list[] | select(.policyName == $n)' >/dev/null; then
    log "Plan '$1' already exists"
    return
  fi
  log "Creating plan '$1'"
  jq -n --arg name "$1" --arg desc "$2" --argjson rpm "$3" --argjson burst "$4" --arg billing "$5" '{
    policyName: $name, displayName: $name, description: $desc,
    defaultLimit: {type: "REQUESTCOUNTLIMIT", requestCount: {timeUnit: "min", unitTime: 1, requestCount: $rpm}},
    rateLimitCount: $burst, rateLimitTimeUnit: "sec", stopOnQuotaReach: true, billingPlan: $billing
  }' | apim_json "$APIM_URL/api/am/admin/v4/throttling/policies/subscription" -d @- >/dev/null
}
create_plan Starter "Free plan for seller integrations: 60 requests per minute, bursts up to 10 per second." 60 10 FREE
create_plan Business "Paid plan for high-volume sellers: 6000 requests per minute." 6000 200 COMMERCIAL

# --- Inventory API ---------------------------------------------------------------------------
API_ID=$(apim "$APIM_URL/api/am/publisher/v4/apis?query=name:SouqlyInventory" | jq -r '.list[0].id // empty')
if [[ -n $API_ID ]]; then
  log "Inventory API already exists ($API_ID)"
else
  log "Importing the Inventory API from its OpenAPI definition"
  PROPS=$(jq -cn --arg backend "$INVENTORY_BACKEND" '{
    name: "SouqlyInventory", context: "/inventory", version: "v1", visibility: "PUBLIC",
    policies: ["Starter", "Business"], keyManagers: ["Keycloak"],
    securityScheme: ["oauth2", "oauth_basic_auth_api_key_mandatory"],
    endpointConfig: {endpoint_type: "http",
      production_endpoints: {url: $backend}, sandbox_endpoints: {url: $backend}}
  }')
  API_ID=$(apim "$APIM_URL/api/am/publisher/v4/apis/import-openapi" \
    -F "file=@$HERE/inventory-api.openapi.yaml" -F "additionalProperties=$PROPS" | jq -r .id)
  REVISION=$(apim_json "$APIM_URL/api/am/publisher/v4/apis/$API_ID/revisions" -d '{"description": "Initial release"}' | jq -r .id)
  apim_json "$APIM_URL/api/am/publisher/v4/apis/$API_ID/deploy-revision?revisionId=$REVISION" \
    -d '[{"name": "Default", "vhost": "localhost", "displayOnDevportal": true}]' >/dev/null
  apim -X POST "$APIM_URL/api/am/publisher/v4/apis/change-lifecycle?apiId=$API_ID&action=Publish" >/dev/null
  log "Published at https://localhost:8243/inventory/v1"
fi

# --- Seller application ----------------------------------------------------------------------
APP_ID=$(apim "$APIM_URL/api/am/devportal/v3/applications?query=acme-erp" | jq -r '.list[] | select(.name == "acme-erp") | .applicationId')
if [[ -z $APP_ID ]]; then
  log "Creating application 'acme-erp' for the ACME seller integration"
  APP_ID=$(apim_json "$APIM_URL/api/am/devportal/v3/applications" -d '{"name": "acme-erp", "throttlingPolicy": "Unlimited",
    "description": "ACME seller ERP integration (Keycloak client seller-acme-integration)", "tokenType": "JWT"}' | jq -r .applicationId)
fi
if apim "$APIM_URL/api/am/devportal/v3/applications/$APP_ID/oauth-keys" | jq -e '.list[] | select(.keyManager == "Keycloak")' >/dev/null; then
  log "Keycloak client already mapped to 'acme-erp'"
else
  log "Mapping Keycloak client 'seller-acme-integration' to 'acme-erp'"
  apim_json "$APIM_URL/api/am/devportal/v3/applications/$APP_ID/map-keys" -d '{"consumerKey": "seller-acme-integration",
    "consumerSecret": "seller-acme-dev-secret", "keyType": "PRODUCTION", "keyManager": "Keycloak"}' >/dev/null
fi
if apim "$APIM_URL/api/am/devportal/v3/subscriptions?applicationId=$APP_ID" | jq -e --arg api "$API_ID" '.list[] | select(.apiId == $api)' >/dev/null; then
  log "'acme-erp' already subscribed to the Inventory API"
else
  log "Subscribing 'acme-erp' to the Inventory API on the Starter plan"
  apim_json "$APIM_URL/api/am/devportal/v3/subscriptions" \
    -d "{\"applicationId\": \"$APP_ID\", \"apiId\": \"$API_ID\", \"throttlingPolicy\": \"Starter\"}" >/dev/null
fi

log "Done. Try it:"
cat <<EOF
  TOKEN=\$(curl -s $KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token -d grant_type=client_credentials \\
    -d client_id=seller-acme-integration -d client_secret=seller-acme-dev-secret | jq -r .access_token)
  curl -k -X POST https://localhost:8243/inventory/v1/stock/ACME-001/restock \\
    -H "Authorization: Bearer \$TOKEN" -H 'Content-Type: application/json' -d '{"quantity": 10}'
EOF
