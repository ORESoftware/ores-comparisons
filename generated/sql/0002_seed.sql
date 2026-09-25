-- GENERATED. Source: contracts/seed.instances.json
BEGIN;
INSERT INTO tenants (tenant_id, slug, name, created_at) VALUES ('tenant_demo', 'demo', 'Comparison Demo', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO principals (principal_id, tenant_id, email, display_name, created_at) VALUES ('principal_demo', 'tenant_demo', 'demo@example.test', 'Demo User', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO work_items (work_item_id, tenant_id, scenario, status, payload, created_at, updated_at) VALUES ('work_demo', 'tenant_demo', 'http-observability', 'queued', '{"data":"seed=true"}'::jsonb, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO audit_events (audit_event_id, tenant_id, principal_id, event_type, payload, created_at) VALUES ('audit_demo', 'tenant_demo', 'principal_demo', 'seed.created', '{"data":"source=contracts/seed.instances.json"}'::jsonb, '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
COMMIT;
