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

## Phase 2 ? Auth service

Status: complete; `./mvnw clean verify` passed and all apps started healthy with Eureka registration.

- Auth endpoints implement login/logout, access JWTs with `kid`, hashed refresh-token rotation and reuse-family revocation, password reset/change, password history and policy, five-attempt lockout, session listing/revocation, and the TOTP verifier interface.
- Flyway V3 adds refresh families, reset tokens, session records, MFA storage, password history, outbox events, and data-driven role permissions. Permission lookups use Redis caching.
- The gateway forwards auth API traffic and validates JWTs through the auth service before forwarding protected routes.
- PostgreSQL/Redis Testcontainers tests cover login/refresh/logout, expired/revoked/reused refresh tokens, account lockout, suspended-tenant login, reset and session revocation, and all permission-matrix entries (44 cases).
- `docker compose --profile apps up --build -d` produced healthy services; Eureka listed the gateway and all six application services.

Next: Phase 3 ? Tenant service and isolation framework.
Known gaps: reset delivery is represented by the transactional `PasswordResetRequested` outbox event; notification delivery is part of Phase 9. Local JWT keys are ephemeral unless `JWT_SECRET_BASE64` is configured.
