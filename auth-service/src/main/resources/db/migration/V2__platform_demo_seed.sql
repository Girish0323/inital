INSERT INTO tenant.subscription_plan(plan_code, display_name, monthly_price) VALUES
    ('STARTER', 'Starter', 0.00), ('GROWTH', 'Growth', 1999.00)
ON CONFLICT (plan_code) DO NOTHING;

SET LOCAL app.tenant_id = 'ABC20261117001';
INSERT INTO tenant.hotel_tenant(tenant_id, order_prefix, hotel_name, lifecycle_state, created_by)
VALUES ('ABC20261117001', 'ABC', 'Demo Hotel Alpha', 'ACTIVE', 'phase1-seed')
ON CONFLICT (tenant_id) DO NOTHING;
SET LOCAL app.tenant_id = 'XYZ20261117001';
INSERT INTO tenant.hotel_tenant(tenant_id, order_prefix, hotel_name, lifecycle_state, created_by)
VALUES ('XYZ20261117001', 'XYZ', 'Demo Hotel Beta', 'ACTIVE', 'phase1-seed')
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO tenant.tenant_id_sequence(prefix, last_value) VALUES ('ABC', 1), ('XYZ', 1)
ON CONFLICT (prefix) DO NOTHING;

SET LOCAL app.tenant_id = 'ABC20261117001';
INSERT INTO tenant.hotel_settings(tenant_id) VALUES ('ABC20261117001')
ON CONFLICT (tenant_id) DO NOTHING;
SET LOCAL app.tenant_id = 'XYZ20261117001';
INSERT INTO tenant.hotel_settings(tenant_id) VALUES ('XYZ20261117001')
ON CONFLICT (tenant_id) DO NOTHING;

SET LOCAL app.tenant_id = 'ABC20261117001';
INSERT INTO tenant.tenant_subscription(tenant_id, plan_id, starts_on)
SELECT t.tenant_id, p.id, DATE '2026-11-17' FROM tenant.hotel_tenant t CROSS JOIN tenant.subscription_plan p
WHERE t.tenant_id = 'ABC20261117001' AND p.plan_code = 'STARTER'
  AND NOT EXISTS (SELECT 1 FROM tenant.tenant_subscription s WHERE s.tenant_id=t.tenant_id AND s.status <> 'DELETED');
SET LOCAL app.tenant_id = 'XYZ20261117001';
INSERT INTO tenant.tenant_subscription(tenant_id, plan_id, starts_on)
SELECT t.tenant_id, p.id, DATE '2026-11-17' FROM tenant.hotel_tenant t CROSS JOIN tenant.subscription_plan p
WHERE t.tenant_id = 'XYZ20261117001' AND p.plan_code = 'STARTER'
  AND NOT EXISTS (SELECT 1 FROM tenant.tenant_subscription s WHERE s.tenant_id=t.tenant_id AND s.status <> 'DELETED');

INSERT INTO auth.role(role_code, description) VALUES
    ('SUPER_ADMIN','Platform administrator'), ('OWNER','Hotel owner'),
    ('MANAGER','Hotel manager'), ('CASHIER','Cashier'), ('STAFF','Staff member')
ON CONFLICT (role_code) DO NOTHING;

-- Identity placeholders intentionally have no password hash; no demo credential is committed.
INSERT INTO auth.platform_admin(email, account_state) VALUES ('super-admin@example.invalid', 'PENDING_SETUP')
ON CONFLICT (email) DO NOTHING;

SET LOCAL app.tenant_id = 'ABC20261117001';
INSERT INTO auth.user_account(tenant_id, email, display_name, account_state, created_by)
SELECT t.tenant_id, roles.role_code || '+' || lower(t.order_prefix) || '@example.invalid',
       initcap(replace(roles.role_code, '_', ' ')) || ' ' || t.hotel_name, 'PENDING_SETUP', 'phase1-seed'
FROM tenant.hotel_tenant t CROSS JOIN (VALUES ('OWNER'),('MANAGER'),('CASHIER'),('STAFF')) AS roles(role_code)
WHERE t.tenant_id = 'ABC20261117001' AND NOT EXISTS
    (SELECT 1 FROM auth.user_account u WHERE u.tenant_id=t.tenant_id AND lower(u.email)=lower(roles.role_code || '+' || t.order_prefix || '@example.invalid'));
SET LOCAL app.tenant_id = 'XYZ20261117001';
INSERT INTO auth.user_account(tenant_id, email, display_name, account_state, created_by)
SELECT t.tenant_id, roles.role_code || '+' || lower(t.order_prefix) || '@example.invalid',
       initcap(replace(roles.role_code, '_', ' ')) || ' ' || t.hotel_name, 'PENDING_SETUP', 'phase1-seed'
FROM tenant.hotel_tenant t CROSS JOIN (VALUES ('OWNER'),('MANAGER'),('CASHIER'),('STAFF')) AS roles(role_code)
WHERE t.tenant_id = 'XYZ20261117001' AND NOT EXISTS
    (SELECT 1 FROM auth.user_account u WHERE u.tenant_id=t.tenant_id AND lower(u.email)=lower(roles.role_code || '+' || t.order_prefix || '@example.invalid'));

SET LOCAL app.tenant_id = 'ABC20261117001';
INSERT INTO auth.user_role(tenant_id, user_id, role_code, created_by)
SELECT u.tenant_id, u.id, upper(split_part(u.email,'+',1)), 'phase1-seed'
FROM auth.user_account u WHERE u.tenant_id = 'ABC20261117001'
  AND NOT EXISTS (SELECT 1 FROM auth.user_role ur WHERE ur.tenant_id=u.tenant_id AND ur.user_id=u.id AND ur.status <> 'DELETED');
SET LOCAL app.tenant_id = 'XYZ20261117001';
INSERT INTO auth.user_role(tenant_id, user_id, role_code, created_by)
SELECT u.tenant_id, u.id, upper(split_part(u.email,'+',1)), 'phase1-seed'
FROM auth.user_account u WHERE u.tenant_id = 'XYZ20261117001'
  AND NOT EXISTS (SELECT 1 FROM auth.user_role ur WHERE ur.tenant_id=u.tenant_id AND ur.user_id=u.id AND ur.status <> 'DELETED');
