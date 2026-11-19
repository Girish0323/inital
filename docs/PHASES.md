PHASES: Hotel Billing SaaS (run one phase at a time)

Each phase lists Deliverables and Acceptance. Do not exceed the phase scope.

Phase 0: Foundation

Deliverables: Maven parent and all modules scaffolded (empty but building); common module (TenantContext holding a String tenant ID, problem+json error model, base entity with String tenantId, event envelope, plus TenantPrefixResolver, TenantIdFormatter and OrderNumberFormatter per Section 4a, with unit tests for format, zero-padding, accents, names under 3 letters and date handling); docker-compose.yml with Postgres, Redis, RabbitMQ, Eureka, MailHog, MinIO; .env.example; formatting (Spotless) and ArchUnit baseline; GitHub Actions workflow running build and tests; docs/ skeleton.
Acceptance: docker compose up starts infrastructure; ./mvnw clean verify passes; CI green.

Phase 1: Design docs and database

Deliverables: docs/architecture.md (high-level diagram in Mermaid, service responsibilities, event catalog, sequence diagrams for login, tenant creation, and order → bill → cash/UPI payment → verification); docs/er-diagram.md (Mermaid ER per schema); Flyway migrations for all schemas including RLS policies, indexes, constraints, audit tables, outbox tables, invoice-sequence table, tenant.tenant_id_sequence, core.order_number_sequence, payment table (cash/UPI fields per Section 6), seed data; docs/DECISIONS.md.
Acceptance: migrations apply cleanly on empty Postgres in a Testcontainers test; RLS test proves cross-tenant reads return zero rows; upi_reference uniqueness per tenant verified; tenant_id is VARCHAR(14) on every business table and RLS works with it.

Phase 2: Auth service

Deliverables: login, logout, refresh (rotation and reuse detection), forgot/reset/change password, account lockout, password policy, JWT with kid, Redis blacklist, RBAC permission model (tables plus cache), MFA TOTP interface, session listing and revoke; events (UserLoggedIn, PasswordResetRequested); gateway JWT validation filter; OpenAPI.
Acceptance: integration tests for happy path, lockout, expired/revoked/reused refresh token, suspended-tenant login blocked; permission-matrix parameterized test (Section 5).

Phase 3: Tenant service and isolation framework

Deliverables: tenant CRUD with Section 4a hotel ID generation (atomic per-prefix counter, optional Super Admin prefix override) and lifecycle state machine, subscription plans (assign/upgrade/downgrade/expiry job), owner provisioning saga (create tenant → create owner → send reset link, with compensation), tenant events; shared tenant isolation library in common (JWT → TenantContext, Hibernate filter, RLS GUC hook, async/thread propagation, guard against missing tenant); Super Admin-only endpoints.
Acceptance: ID generation tests (format, prefix derivation edge cases, 50 parallel creations on one prefix give unique gap-free 001 to 050, overflow past 999 raises TENANT_PREFIX_EXHAUSTED, rolled-back creation burns no number); isolation integration suite (two tenants, all CRUD endpoints, ID-guessing attempts); suspended tenant blocked at gateway and service; saga compensation test.

Phase 4: Core, user and hotel modules

Deliverables: hotel-core bootstrapped; Hotel module (profile, settings, tax, currency, timezone default Asia/Kolkata, invoice config, optional UPI display details); User module (create/update/deactivate/role assignment/password-reset trigger, hierarchy rules from Section 5); events to audit and notification.
Acceptance: permission tests for Owner vs Manager rules; ArchUnit module boundaries pass.

Phase 5: Category and menu

Deliverables: Category and Menu modules (CRUD, search, filter, availability toggle, image upload to S3/MinIO with validation, tenant-scoped Redis cache with eviction on change); Manager-only writes, read for others.
Acceptance: Cashier/Staff write attempts return 403; cache never leaks across tenants (tested); soft-delete unique-constraint test.

Phase 6: Orders

Deliverables: Order module with state machine and Section 4a order number generation (per hotel, per business date in the hotel timezone, resets daily, injected Clock); create, add/remove items, quantity changes, hold/resume/cancel; price snapshot; optimistic locking and concurrency test; availability check on add.
Acceptance: order numbers follow ABC202611180001 format, reset to 0001 on a new day and are independent per hotel; parallel creations give no duplicates or gaps; rolled-back creation burns no number; timezone boundary test (23:59 vs 00:01 IST); more than 9999 orders in a day widens rather than wraps; only Cashier can mutate; billed orders immutable; invalid transitions rejected; concurrent edit test.

Phase 7: Billing and payments (cash and manual UPI)

Deliverables:

billing-service: tax, GST (CGST/SGST), service charge and discount strategies; gap-free invoice numbering per tenant per financial year; PDF invoice (OpenPDF or similar) showing payment method and UPI reference; reprint marked "DUPLICATE"; Manager cancel of unpaid bill; docs/BILLING_RULES.md with worked examples.
Payment module (in hotel-core): PaymentStrategy with CashPaymentStrategy (tendered, change due) and UpiManualPaymentStrategy (record → pending → verify/reject), one payment per bill domain rule, Idempotency-Key handling, endpoints to list unverified UPI payments for the verification queue.
Events: BillGenerated, BillCancelled, PaymentRecorded, PaymentVerified, PaymentRejected. Acceptance: golden-file tax tests (including rounding edge cases); concurrent invoice numbering produces no gaps or duplicates; cash change calculation correct and negative change rejected; UPI amount must equal bill total; same UTR cannot be reused in a tenant (rejected payments excluded); unverified UPI does not mark the bill paid; Cashier and Manager can verify, Staff and Owner cannot; duplicate Idempotency-Key creates no second payment; cancelled bill returns order to ACTIVE; paid bill cannot be edited or cancelled; no refund endpoint exists.
Phase 8: Reporting and dashboard

Deliverables: daily/weekly/monthly/yearly sales, top categories/items, cashier performance, revenue analysis, bill summary, tax summary, payment-mode summary (cash vs UPI, pending verification count); CSV/XLSX export; Owner and Manager dashboards; read-optimized queries or materialized views with tenant-aware indexes; Redis caching of heavy reports.
Acceptance: report correctness tests on seeded data (only verified payments counted); Cashier/Staff denied; p95 under 2 s on a 1M-row seeded dataset (include the load-test script).

Phase 9: Audit and notification

Deliverables: audit-service (consume events, append-only store, query API for Super Admin and Owner scopes, before/after diff, optional hash chain for tamper evidence); notification-service (email via SMTP/SES, SMS interface only, templates for password reset, invoice, tenant events; retry with DLQ).
Acceptance: every audited activity in Section 7 (module 11) produces a record, including UPI verified/rejected (tested end-to-end); DLQ and retry test; no PII or tokens in logs.

Phase 10: Platform hardening and ops

Deliverables: gateway routes, rate limiting, CORS, aggregated Swagger; Resilience4j on all inter-service calls; centralized JSON logging with correlation ID; Prometheus metrics and Grafana dashboards; OpenTelemetry tracing; ELK compose profile; Dockerfiles (multi-stage, non-root, healthchecks); full CI/CD (build, test, SAST and dependency scan, image build and push, deploy to staging); Terraform or CloudFormation for AWS (ECS Fargate or EKS, RDS Multi-AZ, ElastiCache, Amazon MQ, S3, ALB); backup and disaster-recovery runbook (PITR, RPO/RTO targets, restore drill); alerting rules.
Acceptance: docker compose --profile full up runs the whole platform; smoke-test script exercises tenant creation → login → menu → order → bill → UPI payment → verification → report; k6 or Gatling script verifies the 500 tenants / 10,000 users profile at p95 under 2 s.

Phase 11: Final QA and handover

Deliverables: full regression run; OWASP checklist review in docs/SECURITY.md (threats, mitigations, JWT flow diagram, RBAC and tenant isolation design); Postman collection or .http files; README with a 10-minute quickstart; known-gaps list; docs/RELEASE_2.md describing how to add refunds, Razorpay, card and split payments on top of the extension points (documentation only, no code).
Acceptance: all earlier acceptance checks green in CI from a clean clone.

RELEASE 2 BACKLOG (do not build now)
Refunds: request → approve flow, credit notes, refund audit events, refund permissions for Owner/Manager
Razorpay gateway adapter, webhook signature verification, reconciliation
Card payments
Split payments (relax the one-payment-per-bill domain rule)
Mapping to the original 25 deliverables
#	Original item	Where it lives
1	High-level architecture	Phase 1: docs/architecture.md
2	Low-level design	Phase 1 plus per-module READMEs
3, 4	ER diagram, schema	Phase 1
5, 6	Microservice design, modular monolith design	Phases 1 to 9
7	Folder structure	Section 10 and Phase 0
8, 9	Entities, DTOs	Phases 2 to 9
10, 11	API specs, Swagger	Every phase, aggregated in Phase 10
12 to 15	Security, JWT, RBAC, tenant isolation	Phases 2, 3, 11
16	Design patterns	Section 9, noted in code
17	Migrations	Phase 1 onward
18, 19	Docker, CI/CD	Phases 0 and 10
20 to 22	Exception, logging, audit frameworks	Phases 0, 9, 10
23, 24	Unit and integration tests	Every phase
25	Production deployment architecture	Phase 10
