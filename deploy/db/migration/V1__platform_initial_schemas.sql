CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS tenant;
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS billing;
CREATE SCHEMA IF NOT EXISTS audit;

-- Tenant identity and global plan catalogue.
CREATE TABLE tenant.hotel_tenant (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    tenant_id varchar(14) PRIMARY KEY,
    order_prefix char(3) NOT NULL CHECK (order_prefix ~ '^[A-Z]{3}$'),
    hotel_name varchar(160) NOT NULL,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Kolkata',
    lifecycle_state varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (lifecycle_state IN ('PENDING','ACTIVE','SUSPENDED','DEACTIVATED','DELETED')),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    CONSTRAINT uq_hotel_tenant_id UNIQUE (id),
    CONSTRAINT ck_tenant_id_format CHECK (tenant_id ~ '^[A-Z]{3}[0-9]{11}$')
);

CREATE TABLE tenant.tenant_id_sequence (
    prefix char(3) PRIMARY KEY CHECK (prefix ~ '^[A-Z]{3}$'),
    last_value integer NOT NULL DEFAULT 0 CHECK (last_value BETWEEN 0 AND 999),
    updated_date timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant.subscription_plan (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code varchar(40) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    monthly_price numeric(12,2) NOT NULL CHECK (monthly_price >= 0),
    active boolean NOT NULL DEFAULT true,
    created_date timestamptz NOT NULL DEFAULT now(), updated_date timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE tenant.tenant_subscription (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    plan_id uuid NOT NULL REFERENCES tenant.subscription_plan(id),
    starts_on date NOT NULL, ends_on date,
    subscription_state varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (subscription_state IN ('PENDING','ACTIVE','EXPIRED','CANCELLED')),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id),
    CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

CREATE TABLE tenant.hotel_settings (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'INR', timezone varchar(64) NOT NULL DEFAULT 'Asia/Kolkata',
    gst_enabled boolean NOT NULL DEFAULT false, service_charge_rate numeric(7,4),
    invoice_prefix varchar(24),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (tenant_id), UNIQUE (id, tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id),
    CHECK (service_charge_rate IS NULL OR service_charge_rate BETWEEN 0 AND 100)
);

-- Global role catalogue; assignment rows and hotel accounts remain tenant scoped.
CREATE TABLE auth.role (
    role_code varchar(24) PRIMARY KEY CHECK (role_code IN ('SUPER_ADMIN','OWNER','MANAGER','CASHIER','STAFF')),
    description varchar(160) NOT NULL
);

CREATE TABLE auth.platform_admin (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role_code varchar(24) NOT NULL DEFAULT 'SUPER_ADMIN' REFERENCES auth.role(role_code),
    email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(100), account_state varchar(24) NOT NULL DEFAULT 'PENDING_SETUP',
    created_date timestamptz NOT NULL DEFAULT now(), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    CHECK (role_code = 'SUPER_ADMIN')
);

CREATE TABLE auth.user_account (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    email varchar(254) NOT NULL, display_name varchar(120) NOT NULL,
    password_hash varchar(100), account_state varchar(24) NOT NULL DEFAULT 'PENDING_SETUP'
        CHECK (account_state IN ('PENDING_SETUP','ACTIVE','LOCKED','DISABLED')),
    failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0), locked_until timestamptz,
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id)
);
CREATE UNIQUE INDEX uq_user_account_tenant_email ON auth.user_account(tenant_id, lower(email)) WHERE status <> 'DELETED';

CREATE TABLE auth.user_role (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    user_id uuid NOT NULL, role_code varchar(24) NOT NULL REFERENCES auth.role(role_code),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id),
    FOREIGN KEY (user_id, tenant_id) REFERENCES auth.user_account(id, tenant_id),
    CHECK (role_code <> 'SUPER_ADMIN')
);
CREATE UNIQUE INDEX uq_user_role_active ON auth.user_role(tenant_id, user_id, role_code) WHERE status <> 'DELETED';

CREATE TABLE auth.refresh_token (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    user_id uuid NOT NULL, token_hash varchar(128) NOT NULL, expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id),
    FOREIGN KEY (user_id, tenant_id) REFERENCES auth.user_account(id, tenant_id),
    UNIQUE (tenant_id, token_hash)
);

CREATE TABLE core.category (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL, name varchar(100) NOT NULL,
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id), FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id)
);
CREATE UNIQUE INDEX uq_category_active_name ON core.category(tenant_id, lower(name)) WHERE status <> 'DELETED';

CREATE TABLE core.menu_item (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL, category_id uuid NOT NULL,
    name varchar(140) NOT NULL, description varchar(500), unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0),
    available boolean NOT NULL DEFAULT true,
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id),
    FOREIGN KEY (category_id, tenant_id) REFERENCES core.category(id, tenant_id)
);
CREATE UNIQUE INDEX uq_menu_item_active_name ON core.menu_item(tenant_id, lower(name)) WHERE status <> 'DELETED';

CREATE TABLE core.orders (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    order_number varchar(32) NOT NULL, business_date date NOT NULL,
    order_state varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (order_state IN ('DRAFT','ACTIVE','HELD','BILLED','PAID','CANCELLED')),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id), FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id),
    UNIQUE (tenant_id, order_number)
);

CREATE TABLE core.order_item (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL, order_id uuid NOT NULL,
    menu_item_id uuid NOT NULL, item_name_snapshot varchar(140) NOT NULL,
    unit_price_snapshot numeric(12,2) NOT NULL CHECK (unit_price_snapshot >= 0), quantity integer NOT NULL CHECK (quantity > 0),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id),
    FOREIGN KEY (order_id, tenant_id) REFERENCES core.orders(id, tenant_id),
    FOREIGN KEY (menu_item_id, tenant_id) REFERENCES core.menu_item(id, tenant_id)
);

CREATE TABLE core.order_number_sequence (
    tenant_id varchar(14) NOT NULL, business_date date NOT NULL, last_value integer NOT NULL DEFAULT 0 CHECK (last_value >= 0),
    PRIMARY KEY (tenant_id, business_date), FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id)
);

CREATE TABLE billing.bill (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL, order_id uuid NOT NULL,
    invoice_number varchar(40) NOT NULL, financial_year char(7) NOT NULL,
    subtotal numeric(12,2) NOT NULL CHECK (subtotal >= 0), discount numeric(12,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    service_charge numeric(12,2) NOT NULL DEFAULT 0 CHECK (service_charge >= 0), tax numeric(12,2) NOT NULL DEFAULT 0 CHECK (tax >= 0),
    total numeric(12,2) NOT NULL CHECK (total >= 0), bill_state varchar(20) NOT NULL DEFAULT 'GENERATED'
        CHECK (bill_state IN ('GENERATED','PAID','CANCELLED')),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id), FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id),
    FOREIGN KEY (order_id, tenant_id) REFERENCES core.orders(id, tenant_id),
    UNIQUE (tenant_id, invoice_number)
);

CREATE TABLE billing.invoice_sequence (
    tenant_id varchar(14) NOT NULL, financial_year char(7) NOT NULL,
    last_value bigint NOT NULL DEFAULT 0 CHECK (last_value >= 0),
    PRIMARY KEY (tenant_id, financial_year), FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id)
);

CREATE TABLE billing.payment (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL, bill_id uuid NOT NULL,
    payment_method varchar(8) NOT NULL CHECK (payment_method IN ('CASH','UPI')),
    payment_state varchar(24) NOT NULL CHECK (payment_state IN ('RECORDED','PENDING_VERIFICATION','VERIFIED','REJECTED')),
    amount numeric(12,2) NOT NULL CHECK (amount > 0), amount_tendered numeric(12,2), change_due numeric(12,2),
    upi_reference varchar(80), payer_vpa varchar(120), recorded_by varchar(128) NOT NULL,
    verified_by varchar(128), verified_at timestamptz, rejection_reason varchar(500), idempotency_key varchar(128) NOT NULL,
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id), FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id),
    FOREIGN KEY (bill_id, tenant_id) REFERENCES billing.bill(id, tenant_id),
    UNIQUE (tenant_id, idempotency_key),
    CHECK ((payment_method = 'UPI' AND upi_reference IS NOT NULL AND amount_tendered IS NULL AND change_due IS NULL)
        OR (payment_method = 'CASH' AND upi_reference IS NULL AND amount_tendered IS NOT NULL AND change_due IS NOT NULL)),
    CHECK ((payment_state = 'PENDING_VERIFICATION' AND payment_method = 'UPI' AND verified_at IS NULL)
        OR payment_state <> 'PENDING_VERIFICATION'),
    CHECK (payment_state <> 'REJECTED' OR (payment_method = 'UPI' AND rejection_reason IS NOT NULL)),
    CHECK (payment_state <> 'VERIFIED' OR payment_method = 'UPI' OR amount_tendered >= amount)
);
CREATE UNIQUE INDEX uq_payment_upi_reference_active ON billing.payment(tenant_id, upi_reference)
    WHERE payment_method = 'UPI' AND payment_state <> 'REJECTED';
-- Deliberately no UNIQUE(bill_id): Release 1 enforces one verified payment in the domain, leaving split payments additive.

CREATE TABLE audit.audit_log (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    actor_id varchar(128) NOT NULL, action varchar(80) NOT NULL, resource_type varchar(80) NOT NULL,
    resource_id varchar(128), before_state jsonb, after_state jsonb,
    ip_address inet, correlation_id varchar(128), occurred_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(128), created_date timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128), updated_date timestamptz NOT NULL DEFAULT now(),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE', version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
    PRIMARY KEY (id), UNIQUE (id, tenant_id), FOREIGN KEY (tenant_id) REFERENCES tenant.hotel_tenant(tenant_id)
);

CREATE FUNCTION audit.reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit.audit_log is append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_audit_log_append_only BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_mutation();

-- Transactional outbox tables are service-owned. Payloads contain event envelopes as JSONB.
CREATE TABLE auth.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL REFERENCES tenant.hotel_tenant(tenant_id),
    event_type varchar(160) NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz, created_date timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0
);
CREATE TABLE tenant.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL REFERENCES tenant.hotel_tenant(tenant_id),
    event_type varchar(160) NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz, created_date timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0
);
CREATE TABLE core.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL REFERENCES tenant.hotel_tenant(tenant_id),
    event_type varchar(160) NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz, created_date timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0
);
CREATE TABLE billing.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL REFERENCES tenant.hotel_tenant(tenant_id),
    event_type varchar(160) NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz, created_date timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0
);
CREATE TABLE audit.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL REFERENCES tenant.hotel_tenant(tenant_id),
    event_type varchar(160) NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz, created_date timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0
);

-- Indexes lead with tenant_id so tenant-scoped access remains selective.
CREATE INDEX ix_tenant_subscription_tenant ON tenant.tenant_subscription(tenant_id, subscription_state);
CREATE INDEX ix_auth_user_account_tenant ON auth.user_account(tenant_id, account_state);
CREATE INDEX ix_auth_user_role_tenant ON auth.user_role(tenant_id, user_id);
CREATE INDEX ix_auth_refresh_token_tenant ON auth.refresh_token(tenant_id, user_id, expires_at);
CREATE INDEX ix_category_tenant ON core.category(tenant_id, status);
CREATE INDEX ix_menu_item_tenant_category ON core.menu_item(tenant_id, category_id);
CREATE INDEX ix_orders_tenant_business_date ON core.orders(tenant_id, business_date);
CREATE INDEX ix_order_item_tenant_order ON core.order_item(tenant_id, order_id);
CREATE INDEX ix_bill_tenant_created ON billing.bill(tenant_id, created_date);
CREATE INDEX ix_payment_tenant_created ON billing.payment(tenant_id, created_date);
CREATE INDEX ix_payment_tenant_bill ON billing.payment(tenant_id, bill_id);
CREATE INDEX ix_audit_log_tenant_occurred ON audit.audit_log(tenant_id, occurred_at DESC);
CREATE INDEX ix_auth_outbox_pending ON auth.outbox_event(occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_tenant_outbox_pending ON tenant.outbox_event(occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_core_outbox_pending ON core.outbox_event(occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_billing_outbox_pending ON billing.outbox_event(occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_audit_outbox_pending ON audit.outbox_event(occurred_at) WHERE published_at IS NULL;

-- FORCE also applies the policy to table owners. Platform superusers remain a controlled operational role.
DO $$
DECLARE target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'tenant.hotel_tenant','tenant.tenant_subscription','tenant.hotel_settings',
        'auth.user_account','auth.user_role','auth.refresh_token',
        'core.category','core.menu_item','core.orders','core.order_item','core.order_number_sequence','core.outbox_event',
        'billing.bill','billing.invoice_sequence','billing.payment','billing.outbox_event',
        'auth.outbox_event','tenant.outbox_event','audit.audit_log','audit.outbox_event'
    ] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
        EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (tenant_id = current_setting(''app.tenant_id'', true)) WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))', target);
    END LOOP;
END;
$$;
