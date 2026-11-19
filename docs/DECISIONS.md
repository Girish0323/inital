# Decisions

## ADR-001: Entity ID strategy

Use UUIDv4 primary keys for persistence; UUIDs are generated in the application (with a database default for direct inserts).
This keeps identifiers globally unique across services without sequence coordination.
Tenant and order business identifiers remain separate formatted strings.

## ADR-002: Base package

Use `com.hotelsaas` as the organization root and normalized Java module names below it.
Hyphens in artifact IDs become concatenated package segments (for example `authservice`).
This keeps package names legal Java identifiers while preserving module names.

## ADR-003: Spring versions

Use Spring Boot 3.5.16 with Spring Cloud 2025.0.2.
The Cloud train is the compatible 3.5 line; dependency versions remain managed at the root.

## ADR-004: Business identifier time zone

Formatters accept an injected `Clock`; tenant date generation uses the clock's configured zone.
The deployment default for platform business time is Asia/Kolkata.

## ADR-005: Local S3-compatible development server

Use the maintained PGSTY Silo image as the local MinIO-compatible object store.
MinIO community container images were withdrawn from public registries, so this pinned fork preserves the S3 and MinIO API expected by the project.
The compose service and developer-facing endpoint remain named MinIO for compatibility.

## ADR-006: Phase 1 schema ownership and RLS

Use UUIDv4 surrogate keys and `tenant_id VARCHAR(14)` on tenant-owned rows; composite tenant foreign keys prevent cross-hotel references.
The shared development database separates ownership into `auth`, `tenant`, `core`, `billing`, and `audit` schemas.
RLS is enabled and forced for tenant tables using the transaction-local `app.tenant_id`; the migration principal is separate from runtime roles.

## ADR-007: Payment storage constraints

Store payment method and lifecycle state as checked text values so Release 1 supports CASH and manual UPI only.
Enforce UPI reference uniqueness per tenant except for rejected entries; do not add a bill-level unique key.
The Release 1 one-payment rule remains in application domain logic, preserving the documented additive split-payment path.

## ADR-008: Auth token and session storage

Use HS256 JWTs with a `kid`, 15-minute access lifetime, and seven-day rotating refresh tokens stored as SHA-256 hashes.
Refresh families are locked during rotation and revoked when reuse is detected; Redis stores access-token blacklists and permission cache entries.
Production deployments set `JWT_SECRET_BASE64`; an unset key generates an ephemeral local-development key so a fresh compose stack can start.
