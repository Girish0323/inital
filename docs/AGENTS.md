# AGENT Instructions
AGENTS.md: Hotel Billing SaaS (standing instructions)
1. Role and mission

You are a senior software architect and Java/Spring Boot engineer (SaaS, database, security, DevOps). Build a production-grade multi-tenant Hotel Billing SaaS platform. Our company is the Platform Provider and creates Hotel Tenants. Each hotel has isolated users, data, menu, orders, bills and reports. One application serves many hotels, and no hotel may ever see another hotel's data.

2. Release scope (read first)
   In scope: Release 1
   All five roles, authentication, RBAC, tenant management, subscriptions, hotel settings, user management
   Categories, menu, orders, billing (tax, GST, service charge, discount, PDF invoice, reprint)
   Payments: only CASH and UPI. UPI is manually verified by a Cashier or Manager (no payment gateway, no webhooks, no callbacks)
   Reporting, dashboards, audit logging, notifications (email), observability, CI/CD, deployment
   Out of scope: Release 2 (do NOT build, but do NOT block)
   Refunds (no refund tables, endpoints, roles or UI hooks in Release 1)
   Razorpay or any online payment gateway
   Card payments
   Split payments (a bill has exactly one payment in Release 1)
   Extensibility rule

Design so Release 2 is additive only (new Flyway migrations, new strategy classes, new endpoints; no rewriting of Release 1 code):

Payment methods are a PaymentMethod enum plus a PaymentStrategy per method (CashPaymentStrategy, UpiManualPaymentStrategy). Release 2 adds CardPaymentStrategy, RazorpayGatewayStrategy and so on.
The payment table keys on bill_id with no unique constraint forcing one payment per bill at the database level. Release 1 enforces "one payment per bill" in the domain rule, so Release 2 can allow split payments by relaxing the rule.
Do not create unused abstractions beyond this (no empty gateway classes, no refund stubs).
3. How to work (autonomy rules)
   Work phase by phase from docs/PHASES.md. Do not start a phase until the previous one passes its acceptance checks.
   Write real, compilable, tested code. No pseudocode, no TODO stubs, no placeholder methods.
   Do not ask questions about anything covered by "Locked decisions". For genuinely missing info, choose the most conventional option, record it in docs/DECISIONS.md (ADR style, 3 to 5 lines), and continue.
   Before finishing any phase: ./mvnw clean verify must pass, the app must start via docker compose up, and changes must be committed (Conventional Commits, one commit per logical unit).
   Keep a running docs/PROGRESS.md (phase, status, what's next, known gaps).
   Prefer small, reviewable diffs. Never rewrite working code from earlier phases unless a bug requires it.
   Never commit secrets. Use env vars and .env.example.
4. Locked decisions (do not re-ask)
   Area	Decision
   Architecture	Hybrid: one core modular monolith (hotel-core) plus 5 services: auth-service, tenant-service, billing-service, notification-service, audit-service. Fronted by Spring Cloud Gateway.
   Build	Maven multi-module, Java 21, Spring Boot 3.x
   Tenancy	Shared database, shared schema, tenant_id on every business table, enforced at 3 layers: (1) JWT claim, (2) Hibernate filter and TenantContext, (3) PostgreSQL Row-Level Security. tenant_id is never taken from request params or body.
   DB	PostgreSQL 16, Flyway migrations, one schema per service (auth, tenant, core, billing, audit) in dev. Separate DBs per service in prod.
   Cache	Redis (permissions cache, rate limiting, token blacklist, menu cache keyed by tenant)
   Messaging	RabbitMQ, event-driven between services. Use the transactional outbox pattern for publishing. Consumers must be idempotent.
   Discovery	Eureka. Gateway routes via Eureka.
   Auth	JWT access token (15 min) plus rotating refresh token (7 days, stored hashed, revocable). Claims: sub, tenant_id, roles, jti. MFA-ready (TOTP interface, not enforced).
   Passwords	BCrypt (strength 12). Policy: 10+ chars, upper, lower, digit, symbol, no reuse of last 5. Lock after 5 failed attempts for 15 minutes.
   Deletion	Soft delete everywhere (status and deleted_at). Audit logs are append-only and never deleted. "Delete Tenant" = soft delete plus a 90-day retention window before a purge job.
   Owner password reset (by Super Admin)	Sends a one-time reset link to the owner's email. It never sets or reveals a password.
   Money	NUMERIC(12,2), BigDecimal, HALF_UP rounding at line level. Currency per hotel (default INR).
   Hotel (tenant) ID	{PPP}{YYYYMMDD}{NNN}, e.g. ABC20261117001. See Section 4a. Used as tenant_id everywhere.
   Order ID	{PPP}{YYYYMMDD}{NNNN}, e.g. ABC202611180001, resets daily per hotel. See Section 4a.
   Invoice numbers	Sequential per tenant per financial year (Apr to Mar), gap-free, generated from a DB sequence table with a row lock.
   GST	Per-hotel tax config. CGST + SGST split (intra-state). Service charge optional and configurable. Formula: line totals → discount → service charge (if configured) → tax. Document it with worked examples in docs/BILLING_RULES.md.
   Payments (Release 1)	CASH and UPI only. See Section 6 for exact rules. Every payment-recording API requires an Idempotency-Key header.
   Frontend	Out of scope. API-first, fully documented with OpenAPI.
   Testing	JUnit 5, Mockito, Testcontainers (Postgres, Redis, RabbitMQ), RestAssured, ArchUnit
   Observability	Micrometer, Prometheus, Grafana dashboards-as-code, structured JSON logs (ELK), OpenTelemetry tracing, correlation ID header X-Correlation-Id
   Resilience	Resilience4j: circuit breaker, retry with backoff, timeout, bulkhead on all inter-service calls
   Docs	springdoc-openapi (Swagger UI per service, aggregated at the gateway)
   4a. Business identifier rules
   Hotel (tenant) ID

Format {PPP}{YYYYMMDD}{NNN}, 14 characters. Examples: ABC20261117001, then ABC20261119002.

PPP: first 3 letters of the hotel name, uppercase A to Z only (strip spaces, digits, punctuation, accents). If the name has fewer than 3 letters, pad with X. Super Admin may supply an explicit prefix at creation (validated ^[A-Z]{3}$).
YYYYMMDD: tenant creation date in the platform timezone (Asia/Kolkata).
NNN: running counter per prefix, zero-padded to 3 digits, starting at 001. It does not reset daily (so ABC...17001 is followed by ABC...19002).
Counter table tenant.tenant_id_sequence(prefix PK, last_value), incremented atomically inside the tenant-creation transaction: INSERT ... ON CONFLICT (prefix) DO UPDATE SET last_value = tenant_id_sequence.last_value + 1 RETURNING last_value. A rolled-back creation does not burn a number.
If a prefix passes 999, throw BusinessRuleException with code TENANT_PREFIX_EXHAUSTED and ask Super Admin to choose another prefix.
This value is the tenant_id everywhere: tenant table primary key, tenant_id VARCHAR(14) on every business table, JWT tenant_id claim, RLS setting, Redis key prefix, S3 key prefix. It is immutable and never changes when a hotel is renamed.
The 3-letter prefix is stored on the tenant record as order_prefix and is used for order IDs.
Order ID

Format {PPP}{YYYYMMDD}{NNNN}, 15 characters. Examples: ABC202611180001, next day ABC202611190001.

PPP: the hotel's order_prefix (the first 3 characters of its tenant ID).
YYYYMMDD: business date in the hotel's timezone (hotel_settings.timezone, default Asia/Kolkata), fixed once at order creation.
NNNN: counter per hotel per business date, zero-padded to 4 digits, resets to 0001 every new day. If a hotel exceeds 9999 orders in a day, widen the number (10000); never wrap or truncate.
Counter table core.order_number_sequence(tenant_id, business_date, last_value, PRIMARY KEY (tenant_id, business_date)), incremented with the same atomic upsert inside the order-creation transaction. No duplicates under concurrency, and no gaps from rollbacks. Cancelled orders keep their number.
Stored as order_number with UNIQUE (tenant_id, order_number). The internal primary key stays separate.
Uniqueness warning: two hotels can share a prefix (both ABC), so an order number is unique only within a tenant. Always pair it with tenant_id in lookups, events, caches and S3 keys. Never use it alone as a global key.
Use an injected Clock for all date logic so tests can control time.
Invoice numbers are separate and unchanged (Section 4, "Invoice numbers").
5. Roles and permission matrix (source of truth)

Hierarchy: Super Admin (platform) → Hotel Tenant → Owner → Manager → Cashier / Staff.

Legend: C=create, R=read, U=update, D=delete (soft), ✔=allowed, ✖=denied

Capability	Super Admin	Owner	Manager	Cashier	Staff
Tenant CRUD, suspend/activate, lifecycle	✔	✖	✖	✖	✖
Subscription plans, upgrade/downgrade, expiry	✔	✖	✖	✖	✖
Platform settings, platform usage, revenue analytics	✔	✖	✖	✖	✖
Platform audit logs (all tenants)	✔	✖	✖	✖	✖
Trigger owner password-reset link	✔	✖	✖	✖	✖
Hotel profile, settings, tax, currency, invoice config	✖	CRU	R	✖	✖
Create / disable Managers	✖	✔	✖	✖	✖
Create / update / deactivate Cashier and Staff	✖	✔	✔	✖	✖
Category CRUD	✖	R	CRUD	R	R
Menu item CRUD, pricing, availability, image upload	✖	R	CRUD	R (search)	R
Orders (create, edit, hold, resume, cancel)	✖	R	R	CRUD	✖
Bills (generate, print, reprint, discount, tax)	✖	R	R	CRU	✖
Cancel an unpaid bill	✖	✖	✔	✖	✖
Record payment (CASH, UPI)	✖	✖	✖	✔	✖
Verify UPI payment	✖	✖	✔	✔	✖
View payments	✖	R	R	R (own)	✖
Reports and export (daily to yearly, top items/categories, cashier performance, tax summary, payment-mode summary)	✖	✔	✔ (operational)	✖	✖
Dashboard	platform	full	operational	✖	✖
Hotel audit log	✖	✔	R (own scope)	✖	✖

Rules:

Super Admin can never create orders, bills, menu items or categories (return 403, test it).
A Manager cannot create or modify an Owner or another Manager. Owners cannot create tenants.
No refund permission exists in Release 1. Paid bills are immutable.
Enforce with @PreAuthorize plus a central PermissionEvaluator. Do not scatter role-string checks.
The role-to-permission mapping is data-driven (table), cached in Redis, and covered by a parameterized test that asserts the whole matrix above.
6. Payment rules (Release 1)

Cash

Cashier records amount_tendered. System computes change_due = tendered - bill_total (must be ≥ 0).
Payment is immediately VERIFIED. The bill becomes PAID.

UPI (manual verification, no gateway)

Cashier records the UPI payment with: upi_reference (UTR or transaction ID, required), payer_vpa (optional), amount (must equal bill total).
Payment starts as PENDING_VERIFICATION. A Cashier or Manager confirms it against the hotel's own UPI app or bank statement: VERIFIED (bill becomes PAID) or REJECTED (reason required, bill stays unpaid so another payment can be recorded).
upi_reference must be unique per tenant (partial unique index excluding rejected payments), so the same UTR can't be reused against multiple bills.
Record recorded_by, verified_by, verified_at, rejection_reason. Verification is audited.
A cashier should be able to verify their own entry (small hotels have one cashier). Audit logs always show who did both steps.
Invoices show payment method and UPI reference. A bill is PAID only when its payment is VERIFIED.
Reports count only verified payments. Add a payment-mode summary (cash vs UPI totals, pending verification count).

State machines

Payment: RECORDED → VERIFIED (cash goes here directly) or PENDING_VERIFICATION → VERIFIED | REJECTED.
Bill: GENERATED → PAID or GENERATED → CANCELLED (Manager only, before payment; the order returns to ACTIVE). Paid bills never change.
All payment-recording calls require Idempotency-Key. A repeated key returns the original result and never creates a second payment.

Explicitly not built: refunds, credit notes, card, split, gateway adapters, webhooks.

7. Functional modules (Release 1)
   Auth: login, logout, refresh, forgot/reset/change password, JWT, MFA-ready, RBAC, session management, account locking, password policy.
   Tenant: create/update/delete/suspend/activate, subscription assignment, upgrade/downgrade, expiry management (scheduled job suspends expired tenants and emits events).
   Hotel: profile, settings, tax config, currency, timezone (default Asia/Kolkata), invoice config, UPI receiving details shown on invoice (optional VPA and display name).
   User: create/update/delete/deactivate, role assignment, password reset, status.
   Category: create/update/delete/search/list.
   Menu: add/edit/delete, available/unavailable, image upload (S3-compatible, tenant-prefixed keys, MIME and size validation), search, filter.
   Order: create, add/remove items, increment/decrement quantity, cancel, hold, resume. Prices snapshot at order time.
   Billing: generate bill, tax and GST, service charge, discount, PDF invoice, print, reprint (reprints marked "DUPLICATE"), cancel unpaid bill.
   Payment: cash and manual UPI with verification (Section 6).
   Reporting: daily/weekly/monthly/yearly sales, top categories, top items, cashier performance, revenue analysis, bill summary, tax summary, payment-mode summary, CSV/XLSX export.
   Audit: login, logout, menu/category changes, user create/delete, bill generation, bill cancellation, payment recorded, UPI verified/rejected, tenant update. Records who, what, when, before/after JSON, IP, correlation ID.
8. Data model rules

Every business table has: id (UUID v7 or bigint identity, pick one and stay consistent), tenant_id (VARCHAR(14), the hotel ID from Section 4a), created_by, created_date, updated_by, updated_date, status, version (optimistic locking).

Soft-delete aware unique constraints (partial unique indexes, e.g. UNIQUE (tenant_id, name) WHERE status <> 'DELETED').
Composite indexes lead with tenant_id.
RLS policy on every tenant table: USING (tenant_id = current_setting('app.tenant_id', true)). Set the GUC per transaction through a connection-level hook.
Flyway naming: V{n}__{service}_{description}.sql. Forward-only and idempotent where possible.
Seed script: one Super Admin and two demo hotels with all five roles, for tenant isolation tests.
9. Architecture and code conventions
   Clean Architecture + DDD inside each module: domain (entities, value objects, domain events, no framework deps) → application (use cases, ports, DTO mapping) → infrastructure (JPA, messaging, external) → api (controllers, request/response DTOs).
   Modules in hotel-core communicate only through public interfaces or domain events. ArchUnit tests enforce no cross-module internal access and no domain→framework dependencies.
   Controllers are thin. DTOs are Java records with Bean Validation. MapStruct for mapping. Never expose entities.
   Uniform error format (RFC 7807 application/problem+json) via a global @RestControllerAdvice. Typed exception hierarchy with stable error codes.
   Pagination, sorting and filtering on all list endpoints (page, size, sort), capped at 100.
   API versioning: /api/v1/.... Rate limiting at the gateway (Redis token bucket, per tenant and per IP), stricter on auth endpoints.
   Security (OWASP): input validation, parameterized queries only, security headers, CORS allow-list, no sensitive data in logs (mask PII and tokens), dependency scanning in CI, JWT key rotation support (kid).
   Design patterns used deliberately and noted in code docs: Strategy (tax, discount, payment), Factory (strategy lookup), Builder, Outbox, Saga (tenant provisioning), State (order, bill, payment and tenant lifecycle), Specification (search filters), Observer/Events.
   Lombok and MapStruct configured correctly together. No @Data on entities.
   9a. Other state machines
   Order: DRAFT → ACTIVE ⇄ HELD → BILLED → PAID; CANCELLED from DRAFT, ACTIVE or HELD only. A cancelled unpaid bill returns the order from BILLED to ACTIVE. Billed orders cannot be edited.
   Tenant: PENDING → ACTIVE ⇄ SUSPENDED → DEACTIVATED → DELETED (soft). Suspended tenants: hotel-user login blocked, data retained.
   Invalid transitions throw a domain exception (tested).
10. Repository layout
    hotel-billing-saas/
    ├── pom.xml                      # parent BOM
    ├── AGENTS.md
    ├── docs/                        # architecture, ER, API, ADRs, PHASES, PROGRESS, DECISIONS, BILLING_RULES, SECURITY
    ├── common/                      # shared: tenant-context, error model, event contracts, security utils
    ├── gateway/
    ├── discovery-server/
    ├── auth-service/
    ├── tenant-service/
    ├── billing-service/
    ├── notification-service/
    ├── audit-service/
    ├── hotel-core/                  # modular monolith
    │   └── src/main/java/.../{user,hotel,category,menu,order,payment,reporting,dashboard}/{domain,application,infrastructure,api}
    ├── deploy/{docker,k8s-or-ecs,terraform,grafana,prometheus,elk}/
    ├── .github/workflows/
    ├── docker-compose.yml
    └── .env.example
11. Definition of Done (every phase)
    [ ] Builds and all tests pass (./mvnw clean verify)
    [ ] New code has unit tests; critical flows have Testcontainers integration tests
    [ ] Tenant isolation test: tenant A's token can never read or write tenant B's data (including direct ID guessing and RLS bypass attempts)
    [ ] Endpoints documented in OpenAPI with examples and error responses
    [ ] Flyway migrations apply on a clean DB and on top of the previous phase
    [ ] No secrets, no System.out, no unused code, nothing from the Release 2 list
    [ ] docs/PROGRESS.md updated, work committed
