# Decisions

## ADR-001: Entity ID strategy

Use UUID primary keys for persistence; UUIDs are generated in the application.
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
