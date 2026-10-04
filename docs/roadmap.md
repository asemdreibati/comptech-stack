# Roadmap

Souqly is built one slice at a time. Each phase ships a working, tested, load-tested service and
an ADR that explains its hardest decision.

| # | Phase | Stack it exercises | Status |
|---|---|---|---|
| 1 | **Inventory and flash sales**: reservations, expiry, Redis admission gate, group commit, transactional outbox | Spring Boot, MongoDB, Redis, Kafka | ✅ Done |
| 2 | **Identity and API gateway**: roles mapped to per-service permissions, service-to-service tokens, seller ownership checks, subscription plans and rate limits for seller integrations | Keycloak, WSO2 API Manager | ✅ Done |
| 3 | **Catalog and search**: category schemas in Form.io, verified direct-to-storage image uploads, Arabic/English search with typo tolerance and disjunctive facets, built from catalog and stock events | MongoDB, Form.io, MinIO, OpenSearch, Kafka | ✅ Done |
| 4 | **Orders and payments**: checkout saga (reserve, pay, confirm) with compensation, idempotent payments and crash recovery; price book from catalog events; inventory takes SKU ownership from catalog events. Avro with Schema Registry is deferred (see ADR 0004) | Spring Boot, Kafka, MongoDB, Keycloak (service accounts) | ✅ Done |
| 5 | **Seller onboarding and returns**: KYC form and documents, risk rules, review SLA with escalation, four-eyes approval, Keycloak provisioning; returns with policy, seller deadline, disputes, parcel receipt, inspection and partial refunds | CIB seven (Camunda 7 engine), Form.io, MinIO, PostgreSQL | ✅ Done |
| 6 | **Trust and recommendations**: linked seller accounts (shared bank, address or device), "customers also bought" | Neo4j, Kafka | Next |
| 7 | **Storefront web app**: buyer site and seller portal | Next.js (frontend), the APIs above | Planned |
| 8 | **Operations**: tracing across services, dashboards and alerts, failure injection | OpenTelemetry, Prometheus, Grafana, Toxiproxy | Planned |
| 9 | **Deployment**: Helm charts, horizontal scaling, CI end-to-end environment | Kubernetes (kind in CI) | Planned |

## Open decisions

- ~~Camunda 7 or 8.~~ Decided in phase 5: CIB seven, the Apache-licensed continuation of
  Camunda 7, since Camunda 8 needs a production licence (ADR 0005).
- **OpenSearch or Elasticsearch.** The plan is OpenSearch (Apache 2.0 licence). The client code
  stays portable either way.
