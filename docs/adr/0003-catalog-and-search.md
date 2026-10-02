# ADR 0003: Catalog with Form.io schemas, search as an event-built read model

- Status: Accepted
- Date: 2026-10-02
- Services: `catalog-service`, `search-service`; changes to `inventory-service` and `libs/platform`

## Context

Sellers list very different products. A phone needs storage, colour and network; an abaya needs
size, fit and material. New categories arrive all the time and should not need a code release.
Buyers search in Arabic and English, misspell things, and filter by whatever matters in that
category. Listings are written rarely and read constantly. Product photos come from
untrusted sellers.

The stack mandates MongoDB, Form.io, MinIO, OpenSearch and Kafka.

## Decisions

### 1. Each category's attributes are a Form.io form

A category points at a Form.io form (`infra/formio/forms/*.json`, loaded by
`infra/formio/bootstrap.sh`). Adding an attribute is a form change, reviewed like code, and the
seller portal can render the same form with Form.io's renderer.

**Validation uses Form.io's own engine** through a dry-run submission
(`POST /{form}/submission?dryrun=1`): required fields, ranges, lengths and patterns are enforced
by exactly the rules the browser shows, and nothing is stored in Form.io. Its response also
**drops fields the form does not define**, so a seller cannot smuggle `isFeatured: true` into a
listing.

Testing it turned up two gaps in the community edition, which the catalog closes:

- **Option lists are not enforced on the server.** `onlyAvailableItems` is ignored there, so a
  select accepted `storage: "1024"`. The catalog checks select and radio values against the
  form itself.
- **Values are coerced.** Form.io turned the option `"128"` into the number `128`. The catalog
  maps values back to the form's canonical strings, so stored data matches the schema.

**Facets are declared in the form.** A field with `properties.facet = "true"` becomes a search
filter. The catalog turns facet fields into `{name, label, value, valueLabel}` entries at write
time, so search builds filters for any category without knowing any schema.

### 2. Listings: versions, ETags and ownership

Every listing has a version that rises with each change. It is the HTTP `ETag`, and updates
must send it back in `If-Match`. A missing `If-Match` gets `428`; a stale one gets `412` with the
current version. Two editors (a seller in the portal and their ERP integration, say) can never
silently overwrite each other's changes.

Writes are conditional updates on that version inside a MongoDB transaction that also writes
the outbox event. Sellers can only change their own listings. Drafts and archived listings
return `404` to everyone but their owner, so their existence is not revealed. A listing needs a
verified image before it can go live.

### 3. Images go straight to object storage, then get verified

Proxying uploads through the service would tie up threads and bandwidth for every photo. So:

1. The seller declares type and size and receives a **presigned PUT URL** valid for 5 minutes.
   Content type and content length are part of the signature, so MinIO itself rejects a
   different size (`403`; covered by a test).
2. The browser uploads to a private `uploads/` prefix.
3. On confirmation, the catalog checks the stored size and reads the first 12 bytes to
   **confirm the file really is** a JPEG, PNG or WebP. A file that is not what it claims is
   deleted, and the image is marked rejected.
4. Verified images are copied to `public/` (world-readable, `immutable` cache headers, served
   from a CDN in production).

A bucket lifecycle rule deletes anything left in `uploads/` after a day. Only the S3 API is used,
so the store can be MinIO, AWS S3 or any compatible service.

### 4. Events carry state, keyed for compaction

The catalog publishes the full listing on every change (`catalog.products.v1`, keyed by product).
Inventory now publishes absolute stock levels (`inventory.stock-levels.v1`, keyed by SKU)
through a `StockLedger` that every stock change must go through: it applies the guarded update,
bumps the SKU's version and writes the event in the same transaction. Both topics are
**compacted**, so the latest state of every listing and SKU stays on Kafka indefinitely. Any
consumer can rebuild its view from them, and none ever calls back into the catalog.

Group commit (ADR 0001) emits one stock event per SKU per batch, not per order. A flash sale of
1,000 units produced a few dozen index updates, not 1,000.

### 5. Search is a read model built only from those events

One OpenSearch document per SKU merges the two streams, which never coordinate with each other.
Each half (catalog fields, stock fields) carries its own version, and every write is a
**version-guarded scripted upsert**: an event is applied only if it is newer than what that
half already holds. So:

- events can arrive late, twice or out of order, and stock can arrive before the listing exists;
- a failed batch is simply retried, and a partly applied batch is harmless;
- the index can be rebuilt by replaying the compacted topics from the beginning.

Indexing is batched (bulk API). A record that keeps failing is retried with backoff, then
parked on `<topic>-dlt` with its error, so it never blocks its partition (covered by a test).
The index sits behind an alias (`products` → `products-v1`) with a `strict` mapping.

**Reindexing procedure:** create `products-v2` with the new mapping, run a second consumer group
from the earliest offset into it, wait for lag to reach zero, swap the alias atomically, then
delete `products-v1`.

### 6. Search behaviour

- **Arabic:** normalisation (`أ إ آ` → `ا`, `ة` → `ه`, `ى` → `ي`), stop words and stemming, so `ايفون`
  finds `آيفون` and `الهاتف` finds `هاتف`. **English:** light stemming and possessives.
- **Typos:** `fuzziness: AUTO` with the first letter fixed (`phoen` finds `phone`).
- **Autocomplete:** edge n-grams in both languages.
- **Disjunctive facets:** selected filters go into `post_filter`, and every facet is counted
  with all selections except its own. After choosing 128 GB, the storage facet still shows how
  many 256 GB phones there are, while the colour facet shows only colours available in 128 GB.
- Brand, price range, in-stock and category filters; sorting by relevance, price or newest;
  pagination stops at 10,000 results; responses can be cached for 30 seconds.

### 7. Gateway surface

Two new APIs are published through WSO2:

- **Catalog API** for seller integrations: OAuth, subscription plans, forwarded token.
  Category administration is not exposed.
- **Storefront API** for public search: no token; an API-wide limit (10K per minute) replaces
  per-subscriber plans.

### 8. A shared platform library

The second and third services needed the same security baseline, outbox and MongoDB
transaction handling. Rather than copying them, they moved to `libs/platform` and are applied
through Spring Boot auto-configuration. A service now declares only its own endpoint rules.

## Verification

- **Catalog:** 13 integration tests against real MongoDB, Kafka, Form.io and MinIO. They cover
  form validation, stripped and disallowed values, ETag conflicts, ownership, draft visibility,
  the full upload flow, a script disguised as a PNG, and a size mismatch refused by MinIO.
- **Search:** 9 integration tests against real Kafka and OpenSearch. They cover Arabic and English
  matching, typos, visibility by status, late events, stock arriving first, disjunctive facets,
  price filtering and sorting, autocomplete in both languages, and dead-lettering.
- **Inventory:** stock-level events are tested for absolute values and increasing versions.
- **`infra/e2e/marketplace-smoke.sh` walks one product through every service**: a seller lists a
  phone, uploads a photo straight to MinIO, publishes and restocks it. Search finds it by an
  English typo and an Arabic spelling variant, in stock. The order service reserves all units,
  and search shows it sold out. CI runs this, plus 15 gateway checks, on every push.

**Flash-sale load test after this phase.** Shared 4 vCPU box, now also running OpenSearch,
Form.io, MinIO and two more services; three warm runs per mode:

| | Phase 2 | Phase 3 |
|---|---|---|
| Gate armed | 2,450–3,100 req/s | 2,370–3,080 req/s |
| Gate disarmed | 1,750–1,880 req/s | 1,510–1,570 req/s |

Every run still sold exactly 1,000 units. The disarmed path lost about 12%: each batch
transaction now also writes a stock-level event, and more containers compete for the same CPUs.

## Alternatives considered

- **JSON Schema validated in the service.** No extra server, but two validation engines (browser
  and server) drift apart, and non-developers cannot edit schemas. Form.io is also the
  mandated stack.
- **Uploading through the service.** Simpler permissions, but every photo would hold a thread and
  double the bandwidth. Presigned URLs plus verification keep the service out of the data path
  without trusting the client.
- **Search reading the catalog synchronously.** Always fresh, but it couples search
  availability and latency to the catalog. Events make search independent and rebuildable.
- **Change data capture (Debezium) instead of an outbox.** No outbox code, but events would
  mirror database rows rather than a deliberate contract, and Kafka Connect is more
  infrastructure. The outbox already existed in the platform library.
- **The official `opensearch-java` client.** It is built on Jackson 2, while the services run
  Jackson 3. Queries are written as JSON over the REST API instead, which also keeps them
  identical to the OpenSearch documentation.
- **Elasticsearch.** Equivalent here; OpenSearch was chosen for its Apache 2.0 licence.

## Consequences and known limitations

- **MinIO images.** MinIO stopped publishing community images in October 2025. Local development
  uses the frozen `bitnamilegacy/minio` build. Production needs MinIO's commercial edition or
  another S3-compatible store; no code changes either way.
- **Form.io community edition.** The catalog uses Form.io's root account. Production should use
  a dedicated account that can only create submissions on category forms. `/health` answers
  400, so health checks use `/access`.
- **Two owners for a SKU.** A listing's seller is set by the catalog; stock ownership is set by
  the first restock in inventory. Next step: inventory consumes `ProductChanged` and assigns
  the owner from the listing.
- **Eventual consistency.** A change reaches search in about 1–2 seconds (outbox poll plus index
  refresh). Search is not the source of truth for prices or stock at checkout.
- **Facet labels are English only.** Arabic labels need Form.io's translations.
- **Reindexing is a documented procedure, not yet automated.**
