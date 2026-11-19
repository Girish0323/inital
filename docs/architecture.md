# Architecture

## System context

```mermaid
flowchart LR
  client[Hotel clients / API consumers] --> gateway[Spring Cloud Gateway]
  gateway --> auth[Auth Service]
  gateway --> tenant[Tenant Service]
  gateway --> billing[Billing Service]
  gateway --> core[Hotel Core modular monolith]
  gateway --> audit[Audit Service]
  gateway --> notify[Notification Service]
  gateway -. discovery .-> eureka[Eureka]
  auth --> db[(PostgreSQL 16
auth schema)]
  tenant --> db
  core --> db
  billing --> db
  audit --> db
  auth --> redis[(Redis)]
  gateway --> redis
  auth --> mq[(RabbitMQ)]
  tenant --> mq
  core --> mq
  billing --> mq
  mq --> audit
  mq --> notify
  core --> s3[(MinIO-compatible S3)]
  notify --> mailhog[MailHog / SMTP]
```

The development environment uses one PostgreSQL database divided into `auth`, `tenant`, `core`, `billing`, and `audit` schemas. Production separates service databases. Every hotel-owned row carries the immutable 14-character `tenant_id`. PostgreSQL RLS reads the transaction-local `app.tenant_id` setting; application code also scopes access through the tenant context. The Super Admin platform identity is kept separate from hotel users.

## Service responsibilities

| Component | Responsibility | Data ownership |
| --- | --- | --- |
| Gateway | Entry point, Eureka-aware routing, health endpoint | None |
| Discovery Server | Eureka registry | None |
| Auth Service | Hotel accounts, login/session state, role assignments | `auth` |
| Tenant Service | Hotel lifecycle, subscriptions, hotel settings, tenant ID sequence | `tenant` |
| Hotel Core | Category, menu, orders, order sequence, domain modules | `core` |
| Billing Service | Bills, payments, invoice sequence | `billing` |
| Audit Service | Append-only audit records | `audit` |
| Notification Service | Email delivery and event-driven notifications | Own notification store when introduced |
| Common | Tenant context, error contracts, identifiers, base entity, event contracts | No owned schema |

`hotel-core` is a modular monolith. Its category, menu, order, reporting, and dashboard modules communicate through public application interfaces and events. Service databases are separate in production; the development schema layout keeps the initial integration environment small.

## Module boundaries

Each business module follows `domain -> application -> infrastructure -> api`. Domain packages contain framework-free rules. APIs use DTOs and do not expose persistence entities. Cross-service communication uses versioned event envelopes over RabbitMQ and service APIs through the gateway/Eureka. Event consumers are expected to be idempotent; producers persist outbox records in the same transaction as domain changes.

## Event catalog

| Event | Producer | Consumers | Purpose |
| --- | --- | --- | --- |
| `UserLoggedIn` | Auth Service | Audit Service | Record successful login and correlation context |
| `TenantCreated` | Tenant Service | Auth, Notification, Audit | Start owner provisioning, welcome/reset notification, and audit trail |
| `TenantStatusChanged` | Tenant Service | Auth, Audit | Apply tenant lifecycle and record the change |
| `OrderCreated` | Hotel Core | Audit | Record an order creation |
| `BillGenerated` | Billing Service | Audit, Notification | Record billing and make invoice notification available |
| `PaymentRecorded` | Billing Service | Audit | Record cash receipt or pending UPI verification |
| `PaymentVerified` | Billing Service | Audit, Notification | Mark a confirmed payment and notify interested consumers |
| `PaymentRejected` | Billing Service | Audit | Preserve rejection reason and verification actor |

Each event is wrapped in `EventEnvelope<T>` with event ID, type, occurrence time, tenant ID, correlation ID, and payload. Outbox delivery is at least once; consumers deduplicate by event ID.

## Database and tenancy

The schema and Flyway files are under `deploy/db/migration`. `V1` creates the schemas, tenant-scoped base tables, composite tenant foreign keys, partial/tenant-leading indexes, RLS policies, sequence tables, outboxes, and append-only audit storage. `V2` inserts pending-setup demo identities without password hashes. Migration execution must use a trusted schema-owner role; seed inserts set the matching `app.tenant_id` for each tenant.

RLS applies to every table with a hotel `tenant_id`, including outbox and audit tables. Global catalog tables (roles, subscription plans, platform administrators, and the per-prefix tenant ID counter) do not contain hotel data and are not tenant filtered. `tenant_id_sequence` is intentionally global because creation allocates a prefix counter before a tenant row exists. The order and invoice sequence keys are composite and start with tenant identity.

In production, application roles must not own the tables and must not have `BYPASSRLS`. The migration role is separate from runtime roles. Runtime transactions set `app.tenant_id` locally before tenant SQL executes.

## Key flows

### Login

```mermaid
sequenceDiagram
  actor User
  participant Gateway
  participant Auth as Auth Service
  participant DB as auth schema
  participant Redis
  participant Audit as Audit Service
  User->>Gateway: POST /api/v1/auth/login
  Gateway->>Auth: Forward request + correlation ID
  Auth->>DB: Find active account within tenant scope
  Auth->>Auth: Verify BCrypt hash and account state
  Auth->>Redis: Store/rotate session or token blacklist state
  Auth-->>Gateway: Access token + refresh token response
  Auth-)Audit: UserLoggedIn outbox event
  Gateway-->>User: 200 response + X-Correlation-Id
```

### Tenant creation

```mermaid
sequenceDiagram
  actor Admin as Super Admin
  participant Tenant as Tenant Service
  participant DB as tenant schema
  participant Auth as Auth Service
  participant MQ as RabbitMQ
  participant Notify as Notification Service
  Admin->>Tenant: Create hotel + optional prefix
  Tenant->>DB: Upsert prefix counter and allocate next ID
  Tenant->>DB: Insert tenant, settings, subscription, outbox in one transaction
  Tenant-->>Admin: Tenant ID and pending lifecycle state
  Tenant-)MQ: Publish TenantCreated from outbox
  MQ-)Auth: Provision owner identity and reset flow
  MQ-)Notify: Send owner setup notification
```

### Order, bill, cash payment, and manual UPI verification

```mermaid
sequenceDiagram
  actor Cashier
  actor Verifier as Cashier or Manager
  participant Gateway
  participant Core as Hotel Core
  participant Billing as Billing Service
  participant DB as core / billing schemas
  Cashier->>Gateway: Create order
  Gateway->>Core: Request + authenticated tenant context
  Core->>DB: Allocate daily tenant order number and save order/items
  Core-->>Cashier: Order number
  Cashier->>Gateway: Generate bill
  Gateway->>Billing: Bill request for active order
  Billing->>DB: Allocate tenant/FY invoice and persist bill
  Billing-->>Cashier: Bill and invoice number
  alt Cash payment
    Cashier->>Billing: Record CASH amount tendered + Idempotency-Key
    Billing->>DB: Save VERIFIED payment and change due
    Billing->>DB: Mark bill PAID and write outbox event
  else Manual UPI
    Cashier->>Billing: Record UPI reference + Idempotency-Key
    Billing->>DB: Save PENDING_VERIFICATION payment
    Verifier->>Billing: Verify or reject with reason
    Billing->>DB: Persist actor, time, result, audit/outbox event
    Billing->>DB: Mark bill PAID only after verification
  end
```
