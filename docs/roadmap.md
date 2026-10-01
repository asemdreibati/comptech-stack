# Roadmap

Souqly is built one slice at a time. Each phase ships a working, tested, load-tested service and
an ADR that explains its hardest decision.

| # | Phase | Stack it exercises | Status |
|---|---|---|---|
| 1 | **Inventory and flash sales**: reservations, expiry, Redis admission gate, group commit, transactional outbox | Spring Boot, MongoDB, Redis, Kafka | ✅ Done |
| 2 | **Identity and API gateway**: buyer/seller/admin realms, service-to-service tokens, rate limits per seller application | Keycloak, WSO2 API Manager | Next |
| 3 | **Catalog and search**: category-specific listing schemas, product images, Arabic/English search with typo tolerance, availability fed from inventory events | MongoDB, Form.io, MinIO, OpenSearch, Kafka | Planned |
| 4 | **Orders and payments**: checkout saga (reserve, pay, confirm), with compensation and idempotent payment capture, Avro events with Schema Registry | Spring Boot, Kafka (Confluent Schema Registry), MongoDB | Planned |
| 5 | **Seller onboarding and returns**: KYC forms, approval and dispute workflows with SLA timers | Camunda, Form.io, MinIO | Planned |
| 6 | **Trust and recommendations**: linked seller accounts (shared bank, address or device), "customers also bought" | Neo4j, Kafka | Planned |
| 7 | **Storefront web app**: buyer site and seller portal | Next.js (frontend), the APIs above | Planned |
| 8 | **Operations**: tracing across services, dashboards and alerts, failure injection | OpenTelemetry, Prometheus, Grafana, Toxiproxy | Planned |
| 9 | **Deployment**: Helm charts, horizontal scaling, CI end-to-end environment | Kubernetes (kind in CI) | Planned |

## Open decisions

- **Camunda 7 or 8.** Camunda 7 Community Edition reached end of life in October 2025 and needs a
  relational database. Camunda 8 runs on Elasticsearch/OpenSearch but needs a licence in
  production. To be decided in phase 5, based on what the target employer runs.
- **OpenSearch or Elasticsearch.** The plan is OpenSearch (Apache 2.0 licence). The client code
  stays portable either way.
