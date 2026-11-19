# Progress

## Phase 0 ? Foundation

Status: complete and committed as `64de4d6`.

## Phase 1 ? Design docs and database

Status: complete.

- Architecture responsibilities, event catalog, and Mermaid sequence diagrams for login, tenant creation, and order-to-payment flows are documented.
- Per-schema ER diagrams and ADRs for UUIDv4 IDs, database schema ownership, RLS, and payment constraints are recorded.
- Flyway V1/V2 migrations create the five schemas, tenant-scoped baseline tables, composite tenant foreign keys, tenant-leading/partial indexes, forced RLS, audit/outbox tables, tenant/order/invoice sequences, payment constraints, and pending-setup demo data.
- PostgreSQL 16 Testcontainers tests validate clean migration, `tenant_id VARCHAR(14)` and forced RLS across tenant tables, cross-tenant read isolation, seed roles/hotels, append-only audit rows, tenant-scoped UPI reference uniqueness/rejected reuse, and no bill-level payment uniqueness.
- `./mvnw clean verify` passes across all modules; all five database integration checks pass.

Next: Phase 2 ? Auth Service implementation.
Known gaps: the schema is a baseline; service endpoints, runtime migration startup, and domain logic are scheduled for later phases.
