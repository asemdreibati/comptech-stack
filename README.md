# Souqly

A multi-seller e-commerce marketplace backend, built the way large marketplaces build theirs:
event-driven services, correctness under concurrency, zero-trust security, and tests and load
tests that prove it.

[![CI](https://github.com/asemdreibati/comptech-stack/actions/workflows/ci.yml/badge.svg)](https://github.com/asemdreibati/comptech-stack/actions/workflows/ci.yml)

> **Status:** phases 1–5 of 9 are done.
> **Phase 1:** an inventory service that cannot oversell during flash sales.
> **Phase 2:** Keycloak identity, per-object authorization, and a WSO2 API gateway.
> **Phase 3:** a catalog whose category schemas live in Form.io, verified image uploads to MinIO,
> and Arabic/English search on OpenSearch built purely from events.
> **Phase 4:** checkout as a saga that never double-charges, never loses stock, and finishes
> what a crashed instance started.
> **Phase 5:** seller onboarding (KYC) and returns as BPMN workflows with SLA timers, four-eyes
> approval and disputes, on the Camunda 7 engine (CIB seven).
> Next: trust and recommendations with Neo4j ([roadmap](docs/roadmap.md)).

## Architecture today

```mermaid
flowchart LR
    subgraph clients[Clients]
        ERP[Seller ERP]
        WEB[Storefront / seller portal]
    end
    GW[WSO2 API Manager<br/>Inventory · Catalog · Storefront APIs]
    ERP -->|Keycloak JWT| GW
    WEB -->|public search| GW
    WEB -.->|presigned upload| MINIO[(MinIO)]
    GW --> INV[inventory-service]
    GW --> CAT[catalog-service]
    GW --> SRCH[search-service]
    WEB -->|buyer JWT, checkout| ORD[order-service<br/>checkout saga]
    WEB -->|apply, review| SEL[seller-service<br/>KYC workflow]
    WEB -->|return, decide, inspect| RET[returns-service<br/>returns workflow]
    SEL -->|grant seller role| KC
    SEL -.->|KYC documents| MINIO
    SEL -->|KYC form| FIO
    RET -->|partial refund| PSP
    SEL & RET --> PG[(PostgreSQL<br/>CIB seven engines)]
    ORD -->|service token: reserve, confirm, release| INV
    ORD -->|charge, refund<br/>Idempotency-Key| PSP[Payment provider]
    CAT -->|dry-run validation| FIO[Form.io<br/>category schemas]
    CAT --> MINIO
    INV --> R[(Redis)]
    INV & CAT & ORD --> M[(MongoDB)]
    INV -->|stock levels| K[[Kafka<br/>compacted topics]]
    CAT -->|listing snapshots| K
    ORD -->|order events| K
    SEL -->|application events| K
    RET -->|return events| K
    K -->|listings| ORD & INV
    K -->|orders| RET
    K --> SRCH --> OS[(OpenSearch)]
    KC[(Keycloak)] -. JWKS .-> GW & INV & CAT & ORD & SEL & RET
```

Every service validates every token itself, publishes through a transactional outbox, and shares
one platform library (`libs/platform`) for security, events and transactions.

## Phase 1: a flash sale that cannot oversell

20,000 people try to buy the same 1,000 phones in the same second. The system must sell
**exactly** 1,000, turn the other 19,000 away fast, and survive retries, abandoned carts and crashes.

| `load-tests/flash-sale.js` (20,000 buyers, 1,000 units) | Units sold | Errors | Throughput | p95 |
|---|---|---|---|---|
| First design: per-SKU locking, one commit per order | 1,000 | **7.1%** | ~880 req/s | 2.1 s |
| + group commit | 1,000 | 0 | 2,550–2,750 req/s | 210–230 ms |
| + Redis admission gate | 1,000 | 0 | **3,900–4,500 req/s** | 170–225 ms |
| + Keycloak JWT on every request (phase 2) | 1,000 | 0 | 2,450–3,100 req/s | 230–300 ms |
| + stock-level events for search (phase 3) | 1,000 | 0 | 2,370–3,080 req/s | 240–340 ms |

Measured on a shared 4 vCPU dev box with the load generator on it, so read these as relative
gains, not production capacity. How it works: guarded MongoDB transactions, a Redis Lua gate that
fails open, group commit (flat combining), idempotent order IDs, and a transactional outbox to
Kafka. See [ADR 0001](docs/adr/0001-inventory-reservations.md).

## Phase 2: identity, authorization and the gateway

- **Roles in Keycloak, permissions in services.** Business roles are composites of each service's
  own permissions; the realm is code (`infra/keycloak/souqly-realm.json`).
- **Zero trust.** Every service validates signature, issuer, expiry and audience, even behind
  the gateway.
- **Object-level authorization.** Sellers can only touch their own SKUs and listings. The owner
  check is part of the database write itself (no check-then-act race), and it fails closed.
- **WSO2 API Manager** with Keycloak as its key manager: subscription plans for seller
  integrations, and internal APIs simply not published. Configured as code and tested in CI.

See [ADR 0002](docs/adr/0002-identity-and-api-gateway.md).

## Phase 3: catalog and search

- **Category schemas are Form.io forms** (`infra/formio/forms`), so a new attribute is a form
  change, not a release. Listings are validated by Form.io's own engine (dry run), and fields the
  form does not define are stripped. Testing showed the community edition does not enforce
  dropdown options on the server, so the catalog closes that gap.
- **Facets are declared in the form** (`properties.facet`). Search builds filters for any
  category without knowing its schema.
- **Optimistic concurrency over HTTP:** `ETag` on every listing, `If-Match` required on update
  (`428` without it, `412` when stale).
- **Images go browser → MinIO directly** through presigned URLs that sign the type and size.
  The catalog then checks the file's real size and first bytes before promoting it to the public
  area. A script disguised as a PNG is deleted.
- **Search is a read model built only from compacted Kafka topics.** One document per SKU merges
  listing snapshots and stock levels using version-guarded upserts. Late, duplicate or replayed
  events change nothing, so batches retry safely, poison messages go to a dead-letter topic, and
  the index can be rebuilt from the topics.
- **Arabic and English search:** Arabic normalisation and stemming (`ايفون` finds `آيفون`), typo
  tolerance, autocomplete in both languages, and **disjunctive facets** (choosing 128 GB still
  shows how many 256 GB phones exist).

See [ADR 0003](docs/adr/0003-catalog-and-search.md).

## Phase 4: checkout that is safe to crash and retry

Checkout spans three systems with no shared transaction: orders, inventory and a payment
provider. It runs as an orchestrated saga: reserve stock, charge, confirm the reservation. Each
result is persisted before the next step.

- **Every hop is idempotent.** Buyers send an `Idempotency-Key` (bound to a fingerprint of the
  basket), reservations are keyed by order ID, and the PSP gets `order-<id>` and
  `refund-order-<id>` keys. A retried checkout returns the same order, and a retried charge never
  charges twice.
- **Unknown is not failed.** A PSP timeout is retried with the same key until the PSP gives a
  definite answer; it is never taken for a decline. Steps that keep failing go to
  `NEEDS_ATTENTION` for a person, rather than being guessed.
- **Compensation:** a declined card releases the stock; a reservation that expired during
  payment triggers a refund.
- **Crash recovery:** one instance advances an order under a lease. A sweeper on every instance
  finishes orders whose instance died, from the persisted status.
- **Prices from events:** checkout reads a local price book built from catalog events, so it
  works while the catalog is down.
- **Inventory now takes SKU ownership from catalog listings.**

See [ADR 0004](docs/adr/0004-checkout-saga.md).

## Phase 5: workflows with people in them

Two processes that take days and involve people run as BPMN models on **CIB seven**, the
Apache-licensed continuation of Camunda 7, embedded in each service on PostgreSQL. The models are
in `services/*/src/main/resources/processes` and open as diagrams in Camunda Modeler.

**Seller onboarding.** An applicant fills in a Form.io KYC form and uploads documents straight to
a private bucket. The workflow then:

- screens for other applicants with the same bank account or licence, and sets a risk tier in a
  DMN table;
- gives compliance a 48-hour SLA that escalates to leads without taking the task away;
- lets reviewers ask for more information, with a 14-day deadline;
- requires a **second, different reviewer** for high-risk approvals;
- on approval, **grants the seller role and `seller_id` in Keycloak**, so the applicant's next
  sign-in can list products.

**Returns.** A DMN policy approves small or seller-fault returns; otherwise the seller decides
within 2 days, and silence approves. The buyer can dispute a rejection, and operations arbitrate.
An approved return waits for the parcel (or cancels after 14 days). The warehouse inspects it, and
passing items are refunded partially and idempotently through the PSP. A row lock stops concurrent
requests from returning more than was bought.

**One guarantee for both:** each status change is an asynchronous job that also publishes its
event, so the engine's job table works as the outbox. A step that keeps failing (Keycloak, the
PSP) becomes an incident for operations, not a guess. Events carry bank and phone details only
as keyed hashes.

See [ADR 0005](docs/adr/0005-workflows-onboarding-and-returns.md).

## How it is verified

| Layer | What runs |
|---|---|
| Unit and integration tests | 104 tests on real MongoDB, PostgreSQL, Redis, Kafka, Keycloak, Form.io, MinIO and OpenSearch in Testcontainers (WireMock only for the payment provider and other services' HTTP APIs): concurrency races, authorization matrix, tampered tokens, ETag conflicts, disguised uploads, out-of-order events, Arabic matching, dead-lettering, PSP timeouts, compensation, crash recovery, workflow timers, four-eyes approval, incidents and their recovery |
| Marketplace journey | `infra/e2e/marketplace-smoke.sh`: a new user applies to sell, compliance approves, and they sign in as a seller; they list, upload, publish and restock a phone; search finds it in both languages; a buyer is declined (stock returns), checks out, retries safely, is refused more than is left, and buys the rest; the buyer returns one unit, the seller approves, the warehouse inspects it and the buyer is refunded |
| Gateway | `infra/wso2/smoke-test.sh`: 15 checks across the Inventory, Catalog and Storefront APIs (auth, subscriptions, ownership, public search, rate limits) |
| Load | Flash-sale test fails the build on a single oversold or undersold unit |
| CI | All of the above on every push, the platform started from scratch |

Operational basics everywhere: RFC 9457 errors with stable codes, Prometheus metrics, ECS JSON
logs, liveness and readiness probes (readiness tracks each service's critical dependency),
layered non-root images, and graceful shutdown.

## Run it

Requirements: Java 21, Docker, `curl` and `jq`. About 8 GB of RAM for the platform, plus 2 GB for
the gateway.

```bash
./mvnw package -DskipTests
docker compose --profile app up -d --build --wait   # all infrastructure + all services + a fake PSP
infra/e2e/marketplace-smoke.sh                      # loads the category forms, then the full journey
```

Add the API gateway:

```bash
docker compose --profile app --profile gateway up -d --wait
infra/wso2/bootstrap.sh && infra/wso2/smoke-test.sh
curl -k 'https://localhost:8243/storefront/v1/search?q=phone&f.storage=256&inStock=true'
```

| What | Where |
|---|---|
| Inventory, catalog, search, order, seller, returns APIs | http://localhost:8081, :8082, :8083, :8084, :8085, :8086 (each has `/swagger-ui.html`) |
| Fake payment provider | http://localhost:12111 ([infra/psp](infra/psp/README.md)) |
| Gateway | https://localhost:8243/{inventory,catalog,storefront}/v1 · portals at https://localhost:9443/publisher, `/devportal` (admin/admin) |
| Keycloak | http://localhost:8180 (admin/admin) |
| Form.io | http://localhost:3001 (admin@souqly.dev / formio-admin-dev) |
| MinIO console | http://localhost:9001 (souqly / souqly-minio-dev-secret) |
| OpenSearch | http://localhost:9200 |
| PostgreSQL (workflow services) | localhost:5432 (souqly / souqly-postgres-dev-secret) |

Development accounts (`souqly-dev-cli` client): `buyer`, `seller-acme`, `seller-globex`, `compliance`,
`warehouse`, `admin`.
Service accounts: `order-service`, `souqly-ops`, `seller-acme-integration`. All secrets are
development-only.

## Repository layout

```
libs/platform                Shared: Keycloak security, outbox, Mongo transactions, Form.io, object storage, PSP client
services/inventory-service   Reservations, flash-sale gate, stock ledger
services/catalog-service     Categories (Form.io), listings, verified image uploads (MinIO)
services/search-service      OpenSearch read model, search and autocomplete API
services/order-service       Checkout saga, price book, order history
services/seller-service      Seller onboarding: KYC applications and their review workflow
services/returns-service     Returns: policy, seller decisions, disputes, inspection, refunds
infra/keycloak               Realm: roles, permissions, clients, service accounts
infra/formio                 Category and seller KYC forms, and their bootstrap
infra/wso2                   Gateway config, public API definitions, bootstrap and smoke test
infra/psp                    Fake Stripe-style payment provider (WireMock)
infra/postgres               Databases of the workflow services
infra/e2e                    Cross-service journey test
load-tests/                  k6 scenarios
docs/adr/                    Architecture decision records
```

## Tech

Java 21 (virtual threads) · Spring Boot 4.1 · Spring Security 7 · Keycloak 26 · WSO2 API Manager
4.5 · MongoDB 8 · PostgreSQL 17 · CIB seven 2.2 (Camunda 7 BPMN/DMN engine) · Redis 7 · Apache Kafka 4 ·
Form.io · MinIO (S3 API, AWS SDK v2) · OpenSearch 3 · Flyway · Micrometer + Prometheus ·
Testcontainers · WireMock · k6 · GitHub Actions. Neo4j joins in phase 6 ([roadmap](docs/roadmap.md)).
