# ADR 0002: Identity with Keycloak, public APIs through WSO2 API Manager

- Status: Accepted
- Date: 2026-10-01
- Scope: platform-wide; first applied to `inventory-service`

## Context

Four kinds of callers reach the platform, each with different trust and traffic patterns:

| Caller | Example | Path |
|---|---|---|
| People in a browser | Buyers on the storefront, sellers in the seller portal | Web app, then APIs |
| Seller systems | ACME's ERP pushing stock levels every minute | Public API, machine to machine |
| Internal services | Order service reserving stock at checkout | Inside the cluster |
| Operations | Marketing arming a flash sale, load tests | Back office |

We need one identity provider for all of them, an authorization model that doesn't sprawl as
services are added, and a public API front door with subscription plans and rate limits for
sellers. The stack mandates Keycloak and WSO2 API Manager.

## Decisions

### 1. Business roles in Keycloak, permissions in each service

Services check **permissions**, never job titles. Each service owns a Keycloak client whose
*client roles* are its permissions (`inventory-service`: `stock:read`, `stock:write`,
`stock:write-any`, `flash-sale:manage`, `reservation:write`). Business roles are *composite realm
roles* that bundle permissions:

| Role or account | Inventory permissions |
|---|---|
| `buyer` | none (buyers act through the order service) |
| `seller` | `stock:read`, `stock:write` |
| `admin` | all five |
| `order-service` (service account) | `stock:read`, `reservation:write` |

Adding a role, or changing what sellers may do, is a Keycloak change, not a code change. A
service only reads its own client's roles (`resource_access.<client>.roles`) and ignores realm
roles and other services' permissions.

A useful side effect: Keycloak only adds a service to a token's `aud` claim when the token
carries that service's roles. A buyer token is therefore not even *addressed* to the inventory
service and fails audience validation before any permission check.

The realm is code (`infra/keycloak/souqly-realm.json`): roles, clients, service accounts, a
public PKCE client for the web app, development users, and a user profile in which **only admins
can edit `seller_id`**. Otherwise a seller could edit their own profile and take over another
seller's account.

### 2. Every service validates every token (zero trust)

The gateway validates tokens, and so does each service: signature (realm JWKS), issuer,
expiry and audience, on every request. A request that skips the gateway, or comes from a
compromised neighbour, gets no further than one that went through it. Keys are fetched lazily
from the internal Keycloak URL while the issuer stays the public one, so the service starts
even when Keycloak is briefly down.

Errors use the same RFC 9457 problem format as the rest of the API, with the RFC 6750
`WWW-Authenticate` header. Validation details are never echoed back to the caller.

**Measured cost.** Adding authentication cut flash-sale throughput by about 35% on the shared
4 vCPU dev box. Caching tokens that already passed validation (keyed by the exact token string,
at most 60 seconds, expiry re-checked on every hit, failures never cached) reached a 99.98% hit
rate under load but won back only about 10%. The rest comes from the security filter chain and
from requests that are now ~1.2 KB larger, on a box where the load generator competes for the
same CPUs. The cache stays: it is cheap, tested, and accepts nothing the full check would reject.

| Flash-sale load test | Gate armed | Gate disarmed |
|---|---|---|
| No authentication (phase 1) | 3,900–4,500 req/s | 2,550–2,750 req/s |
| Keycloak JWT on every request | 2,450–2,750 req/s | 1,600–1,780 req/s |
| + validated-token cache | 2,450–3,100 req/s | 1,750–1,880 req/s |

Every run still sold exactly the available units.

### 3. Object-level authorization in the database write

`stock:write` lets a seller restock **their own** SKUs; that is enforced per object (the #1 risk
in the OWASP API Security Top 10, broken object-level authorization). The check is not
"read the owner, then write". The seller ID is part of the filter of the single atomic upsert:

- a new SKU is created with the caller as owner;
- the caller's own SKU matches and is updated;
- a SKU owned by someone else (or by the marketplace) does not match, so the upsert tries to
  insert a duplicate `_id`, and the unique key rejects it with `403 NOT_SKU_OWNER`.

There is no window between check and write. A token with seller permissions but no `seller_id`
fails closed (`403 SELLER_IDENTITY_REQUIRED`) instead of being treated as unrestricted. Bypassing
ownership requires the explicit `stock:write-any` permission.

### 4. WSO2 API Manager for north-south traffic only

The gateway carries **external** traffic: seller integrations, partners, and later the web apps.
It owns what a product team should not rebuild: a developer portal, subscription plans, per-
application rate limits, API versions and lifecycle, and usage analytics.

Internal service-to-service calls (the order service reserving stock) go **directly** over the
cluster network with their own client-credential tokens. Routing them through the public gateway
would add a hop to every checkout and couple internal latency to the public tier. The public API
definition (`infra/wso2/inventory-api.openapi.yaml`) therefore leaves reservations out, and
calling them through the gateway returns 404.

How WSO2 and Keycloak fit together:

- Keycloak is registered as a **third-party key manager**. The gateway validates Keycloak JWTs
  itself against the realm's JWKS (no per-request call to Keycloak).
- A seller's existing Keycloak client is **mapped** to a WSO2 application. The gateway matches
  the token's `azp` claim to the application, checks it is subscribed to the API, and applies that
  subscription's plan: **Starter** (60/min, bursts of 10/s) or **Business** (6,000/min).
- The gateway **forwards the original token** (`enable_outbound_auth_header = true`), so the
  service makes its own decisions with the caller's real identity: a seller's `seller_id` reaches
  the ownership check intact.

So the gateway answers "is this a known, subscribed application within its quota?" and the
service answers "may this caller do this to this object?".

### 5. Configuration as code, tested in CI

- `infra/keycloak/souqly-realm.json` is imported on start.
- `infra/wso2/bootstrap.sh` configures API Manager through its REST APIs: key manager, plans,
  API import, revision, deployment and publishing, the seller application, key mapping and
  subscription. It is safe to run repeatedly.
- `infra/wso2/smoke-test.sh` checks the result end to end with real tokens.
- CI starts the whole platform from scratch, bootstraps it and runs the smoke test on every push.

Test coverage in the service: an authorization matrix (`SecurityIT`, 17 cases) and real-Keycloak
tests (`KeycloakIT`) proving that a token with the wrong audience and a token with an edited
payload are both rejected.

## Alternatives considered

- **Opaque tokens with introspection.** Revocation is immediate, but every request costs a call
  to Keycloak. Rejected for latency and for coupling availability to Keycloak. Instead, access
  tokens live 5 minutes.
- **Authorization only at the gateway.** Simpler services, but one misrouted request or
  compromised pod bypasses everything. Rejected (zero trust).
- **OAuth scopes instead of client roles.** Scopes describe what a *client* may request, not what
  a *user* holds. Client roles map cleanly from business roles and give per-service audiences for
  free.
- **Spring Cloud Gateway.** Lighter and code-first, but has no developer portal, subscription
  plans or monetisation. WSO2 is also the mandated stack.

## Consequences and known limitations

- **Development secrets live in the realm file and bootstrap script.** In production, clients and
  secrets are provisioned through the Keycloak admin API from a secrets vault and rotated. The
  `souqly-dev-cli` password-grant client never exists outside local development.
- **Single-node, development-grade infrastructure.** Keycloak runs `start-dev` (H2), and WSO2 uses
  H2 and its self-signed certificate. Production uses PostgreSQL, TLS everywhere, and WSO2's
  distributed deployment (separate control plane and gateways, with a traffic manager for
  cluster-wide rate limits).
- **Revocation lag.** A revoked or disabled account keeps working until its token expires (at
  most 5 minutes). The validation cache never extends that.
- **Connector workaround.** WSO2's Keycloak connector requests an OAuth scope named `default`.
  The bootstrap creates it through the Keycloak admin API, because declaring client scopes in the
  realm export would suppress Keycloak's built-in scopes.
- **Internal callers are not rate limited.** Only gateway traffic is. If an internal client ever
  misbehaves, a Redis token bucket per client ID in the services is the next step.
- **Seller onboarding is scripted for one seller.** In production, sellers self-serve in the
  developer portal, and keys are created through dynamic client registration against Keycloak.
