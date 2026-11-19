# Entity Relationship Diagrams

These diagrams describe the Phase 1 baseline migrations in `deploy/db/migration`. `id` values are UUIDv4. Tenant-owned entities use `tenant_id VARCHAR(14)` and composite foreign keys so references cannot cross hotel boundaries. Global reference tables are called out separately.

## `tenant` schema

```mermaid
erDiagram
  HOTEL_TENANT ||--o{ TENANT_SUBSCRIPTION : owns
  SUBSCRIPTION_PLAN ||--o{ TENANT_SUBSCRIPTION : assigns
  HOTEL_TENANT ||--|| HOTEL_SETTINGS : configures
  HOTEL_TENANT {
    varchar14 tenant_id PK
    uuid id UK
    char3 order_prefix
    varchar hotel_name
    varchar lifecycle_state
  }
  TENANT_ID_SEQUENCE {
    char3 prefix PK
    int last_value
  }
  SUBSCRIPTION_PLAN {
    uuid id PK
    varchar plan_code UK
    numeric monthly_price
  }
  TENANT_SUBSCRIPTION {
    uuid id PK
    varchar14 tenant_id FK
    uuid plan_id FK
    date starts_on
    date ends_on
  }
  HOTEL_SETTINGS {
    uuid id PK
    varchar14 tenant_id FK_UK
    char3 currency
    varchar timezone
  }
```

`TENANT_ID_SEQUENCE` is global and keyed by prefix. Its row is incremented transactionally before a tenant insert; it has no `tenant_id` column and is not subject to hotel RLS.

## `auth` schema

```mermaid
erDiagram
  HOTEL_TENANT ||--o{ USER_ACCOUNT : scopes
  USER_ACCOUNT ||--o{ USER_ROLE : receives
  ROLE ||--o{ USER_ROLE : assigned
  USER_ACCOUNT ||--o{ REFRESH_TOKEN : owns
  ROLE ||--o{ PLATFORM_ADMIN : identifies
  HOTEL_TENANT {
    varchar14 tenant_id PK
  }
  USER_ACCOUNT {
    uuid id PK
    varchar14 tenant_id FK
    varchar email
    varchar password_hash
    varchar account_state
  }
  ROLE {
    varchar role_code PK
    varchar description
  }
  USER_ROLE {
    uuid id PK
    varchar14 tenant_id FK
    uuid user_id FK
    varchar role_code FK
  }
  REFRESH_TOKEN {
    uuid id PK
    varchar14 tenant_id FK
    uuid user_id FK
    varchar token_hash
    timestamptz expires_at
  }
  PLATFORM_ADMIN {
    uuid id PK
    varchar role_code FK
    varchar email UK
    varchar account_state
  }
}
```

The role catalogue (`SUPER_ADMIN`, `OWNER`, `MANAGER`, `CASHIER`, `STAFF`) is global. Hotel accounts and their assignments are tenant scoped. The platform Super Admin lives in `platform_admin`, outside hotel RLS; seeded identities have no password hash and remain `PENDING_SETUP`.

## `core` schema

```mermaid
erDiagram
  HOTEL_TENANT ||--o{ CATEGORY : scopes
  HOTEL_TENANT ||--o{ MENU_ITEM : scopes
  CATEGORY ||--o{ MENU_ITEM : groups
  HOTEL_TENANT ||--o{ HOTEL_ORDER : scopes
  HOTEL_ORDER ||--o{ ORDER_ITEM : contains
  MENU_ITEM ||--o{ ORDER_ITEM : snapshots
  HOTEL_TENANT ||--o{ ORDER_NUMBER_SEQUENCE : sequences
  CATEGORY {
    uuid id PK
    varchar14 tenant_id FK
    varchar name
    varchar status
  }
  MENU_ITEM {
    uuid id PK
    varchar14 tenant_id FK
    uuid category_id FK
    varchar name
    numeric unit_price
    boolean available
  }
  HOTEL_ORDER {
    uuid id PK
    varchar14 tenant_id FK
    varchar order_number
    date business_date
    varchar order_state
  }
  ORDER_ITEM {
    uuid id PK
    varchar14 tenant_id FK
    uuid order_id FK
    uuid menu_item_id FK
    numeric unit_price_snapshot
    int quantity
  }
  ORDER_NUMBER_SEQUENCE {
    varchar14 tenant_id PK
    date business_date PK
    int last_value
  }
}
```

## `billing` schema

```mermaid
erDiagram
  HOTEL_TENANT ||--o{ BILL : scopes
  HOTEL_ORDER ||--o{ BILL : billed_as
  BILL ||--o{ PAYMENT : receives
  HOTEL_TENANT ||--o{ INVOICE_SEQUENCE : sequences
  BILL {
    uuid id PK
    varchar14 tenant_id FK
    uuid order_id FK
    varchar invoice_number
    char7 financial_year
    numeric subtotal
    numeric discount
    numeric service_charge
    numeric tax
    numeric total
    varchar bill_state
  }
  PAYMENT {
    uuid id PK
    varchar14 tenant_id FK
    uuid bill_id FK
    varchar payment_method
    varchar payment_state
    numeric amount
    varchar upi_reference
    varchar idempotency_key
  }
  INVOICE_SEQUENCE {
    varchar14 tenant_id PK
    char7 financial_year PK
    bigint last_value
  }
}
```

Payment references `bill_id` together with `tenant_id`. UPI references are unique per tenant for all rows except `REJECTED`; the database has no unique constraint on `bill_id`, leaving the Release 1 one-payment rule in the domain.

## `audit` schema

```mermaid
erDiagram
  HOTEL_TENANT ||--o{ AUDIT_LOG : scopes
  AUDIT_LOG {
    uuid id PK
    varchar14 tenant_id FK
    varchar actor_id
    varchar action
    varchar resource_type
    varchar resource_id
    jsonb before_state
    jsonb after_state
    inet ip_address
    varchar correlation_id
    timestamptz occurred_at
  }
}
```

Audit rows are append-only. Each service schema also has a tenant-scoped `outbox_event` table (`id`, `tenant_id`, event type, JSONB payload, occurrence time, publication time), with a partial index over unpublished events.
