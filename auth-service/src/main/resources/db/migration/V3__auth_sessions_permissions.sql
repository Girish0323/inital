CREATE TABLE auth.refresh_token_family (
    id uuid PRIMARY KEY,
    tenant_id varchar(14) NOT NULL,
    user_id uuid NOT NULL,
    revoked_at timestamptz,
    reuse_detected_at timestamptz,
    created_date timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (user_id, tenant_id) REFERENCES auth.user_account(id, tenant_id)
);
ALTER TABLE auth.refresh_token ADD COLUMN family_id uuid;
ALTER TABLE auth.refresh_token ADD COLUMN consumed_at timestamptz;
ALTER TABLE auth.refresh_token ADD COLUMN replaced_by uuid;
ALTER TABLE auth.refresh_token ADD CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES auth.refresh_token_family(id);
CREATE INDEX ix_refresh_token_family ON auth.refresh_token(family_id);
CREATE TABLE auth.password_reset_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id varchar(14) NOT NULL,
    user_id uuid NOT NULL, token_hash varchar(128) NOT NULL, expires_at timestamptz NOT NULL,
    consumed_at timestamptz, created_date timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (user_id, tenant_id) REFERENCES auth.user_account(id, tenant_id), UNIQUE (token_hash)
);
CREATE INDEX ix_password_reset_user ON auth.password_reset_token(tenant_id, user_id);
CREATE TABLE auth.permission (
    permission_code varchar(100) PRIMARY KEY, description varchar(200) NOT NULL
);
CREATE TABLE auth.role_permission (
    role_code varchar(24) NOT NULL REFERENCES auth.role(role_code),
    permission_code varchar(100) NOT NULL REFERENCES auth.permission(permission_code),
    PRIMARY KEY (role_code, permission_code)
);
CREATE TABLE auth.user_mfa (
    tenant_id varchar(14) NOT NULL, user_id uuid NOT NULL, totp_secret_encrypted varchar(512),
    enabled boolean NOT NULL DEFAULT false, created_date timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id), FOREIGN KEY (user_id, tenant_id) REFERENCES auth.user_account(id, tenant_id)
);
CREATE TABLE auth.session_record (
    id uuid PRIMARY KEY, tenant_id varchar(14) NOT NULL, user_id uuid NOT NULL,
    created_date timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz, ip_address inet, user_agent varchar(500),
    FOREIGN KEY (user_id, tenant_id) REFERENCES auth.user_account(id, tenant_id)
);
DO $$ DECLARE r text; p text; BEGIN
  FOREACH r IN ARRAY ARRAY['SUPER_ADMIN','OWNER','MANAGER','CASHIER','STAFF'] LOOP
    FOREACH p IN ARRAY ARRAY['tenant.manage','subscription.manage','platform.settings','platform.audit','owner.password.reset','hotel.profile.read','hotel.profile.update','user.manager.manage','user.staff.manage','category.read','category.write','menu.read','menu.write','order.read','order.write','bill.read','bill.write','bill.cancel','payment.record','payment.verify','payment.read','payment.read.own','report.read','report.operational','dashboard.platform','dashboard.full','dashboard.operational','hotel.audit.read'] LOOP
      INSERT INTO auth.permission VALUES (p,p) ON CONFLICT DO NOTHING;
    END LOOP;
  END LOOP;
END $$;
INSERT INTO auth.role_permission(role_code,permission_code)
SELECT x.role_code,x.permission_code FROM (VALUES
('SUPER_ADMIN','tenant.manage'),('SUPER_ADMIN','subscription.manage'),('SUPER_ADMIN','platform.settings'),('SUPER_ADMIN','platform.audit'),('SUPER_ADMIN','owner.password.reset'),('SUPER_ADMIN','dashboard.platform'),
('OWNER','hotel.profile.read'),('OWNER','hotel.profile.update'),('OWNER','user.staff.manage'),('OWNER','category.read'),('OWNER','menu.read'),('OWNER','order.read'),('OWNER','bill.read'),('OWNER','payment.read'),('OWNER','report.read'),('OWNER','dashboard.full'),('OWNER','hotel.audit.read'),
('MANAGER','user.staff.manage'),('MANAGER','category.read'),('MANAGER','category.write'),('MANAGER','menu.read'),('MANAGER','menu.write'),('MANAGER','order.read'),('MANAGER','bill.read'),('MANAGER','bill.cancel'),('MANAGER','payment.verify'),('MANAGER','payment.read'),('MANAGER','report.operational'),('MANAGER','dashboard.operational'),('MANAGER','hotel.audit.read'),
('CASHIER','category.read'),('CASHIER','menu.read'),('CASHIER','order.read'),('CASHIER','order.write'),('CASHIER','bill.read'),('CASHIER','bill.write'),('CASHIER','payment.record'),('CASHIER','payment.verify'),('CASHIER','payment.read.own'),
('STAFF','category.read'),('STAFF','menu.read')) AS x(role_code,permission_code) ON CONFLICT DO NOTHING;
ALTER TABLE auth.refresh_token_family ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.refresh_token_family FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.refresh_token_family USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE auth.password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.password_reset_token FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.password_reset_token USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE auth.user_mfa ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.user_mfa FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.user_mfa USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
ALTER TABLE auth.session_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.session_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.session_record USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
CREATE TABLE auth.password_history (tenant_id varchar(14) NOT NULL, user_id uuid NOT NULL, password_hash varchar(100) NOT NULL, changed_at timestamptz NOT NULL DEFAULT now(), FOREIGN KEY(user_id,tenant_id) REFERENCES auth.user_account(id,tenant_id));
CREATE INDEX ix_password_history_recent ON auth.password_history(tenant_id,user_id,changed_at DESC);
CREATE TABLE auth.login_attempt (id bigserial PRIMARY KEY, email varchar(254) NOT NULL, attempted_at timestamptz NOT NULL DEFAULT now(), succeeded boolean NOT NULL);

CREATE UNIQUE INDEX uq_user_account_email_global ON auth.user_account(lower(email)) WHERE status <> 'DELETED';
INSERT INTO auth.permission(permission_code,description) VALUES
('tenant.lifecycle.manage','Tenant CRUD and lifecycle'),('subscription.manage','Subscription plans and assignments'),('platform.metrics.view','Platform settings and analytics'),('platform.audit.read','Cross-tenant audit'),('owner.password.reset','Owner reset links'),('hotel.profile.read','Hotel profile read'),('hotel.profile.update','Hotel profile create/update'),('user.manager.manage','Create or disable Managers'),('user.staff.manage','Manage Cashiers and Staff'),('category.create','Create categories'),('category.read','Read categories'),('category.update','Update categories'),('category.delete','Delete categories'),('menu.create','Create menu items'),('menu.read','Read and search menu items'),('menu.update','Update menu items'),('menu.delete','Delete menu items'),('order.create','Create orders'),('order.read','Read orders'),('order.update','Update orders'),('order.delete','Cancel orders'),('bill.create','Generate bills'),('bill.read','Read bills'),('bill.update','Update or print bills'),('bill.cancel','Cancel unpaid bills'),('payment.record','Record payments'),('payment.verify','Verify UPI payments'),('payment.read','Read payments'),('payment.read.own','Read own payments'),('reports.full','Full reports'),('reports.operational','Operational reports'),('dashboard.platform','Platform dashboard'),('dashboard.full','Full hotel dashboard'),('dashboard.operational','Operational dashboard'),('hotel.audit.read','Read hotel audit'),('hotel.audit.write','Manage hotel audit')
ON CONFLICT(permission_code) DO NOTHING;
INSERT INTO auth.role_permission(role_code,permission_code) VALUES
('SUPER_ADMIN','tenant.lifecycle.manage'),('SUPER_ADMIN','subscription.manage'),('SUPER_ADMIN','platform.metrics.view'),('SUPER_ADMIN','platform.audit.read'),('SUPER_ADMIN','owner.password.reset'),('SUPER_ADMIN','dashboard.platform'),
('OWNER','hotel.profile.read'),('OWNER','hotel.profile.update'),('OWNER','user.manager.manage'),('OWNER','user.staff.manage'),('OWNER','category.read'),('OWNER','menu.read'),('OWNER','order.read'),('OWNER','bill.read'),('OWNER','payment.read'),('OWNER','reports.full'),('OWNER','dashboard.full'),('OWNER','hotel.audit.read'),('OWNER','hotel.audit.write'),
('MANAGER','hotel.profile.read'),('MANAGER','user.staff.manage'),('MANAGER','category.create'),('MANAGER','category.read'),('MANAGER','category.update'),('MANAGER','category.delete'),('MANAGER','menu.create'),('MANAGER','menu.read'),('MANAGER','menu.update'),('MANAGER','menu.delete'),('MANAGER','order.read'),('MANAGER','bill.read'),('MANAGER','bill.cancel'),('MANAGER','payment.verify'),('MANAGER','payment.read'),('MANAGER','reports.operational'),('MANAGER','dashboard.operational'),('MANAGER','hotel.audit.read'),
('CASHIER','category.read'),('CASHIER','menu.read'),('CASHIER','order.create'),('CASHIER','order.read'),('CASHIER','order.update'),('CASHIER','order.delete'),('CASHIER','bill.create'),('CASHIER','bill.read'),('CASHIER','bill.update'),('CASHIER','payment.record'),('CASHIER','payment.verify'),('CASHIER','payment.read.own'),
('STAFF','category.read'),('STAFF','menu.read')
ON CONFLICT DO NOTHING;
ALTER TABLE auth.refresh_token_family ADD COLUMN session_id uuid REFERENCES auth.session_record(id);
ALTER TABLE auth.password_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.password_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auth.password_history USING (tenant_id=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
