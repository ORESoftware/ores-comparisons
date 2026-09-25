-- GENERATED. Source: contracts/comparison-domain.schema.json + contracts/storage.sql-map.json
BEGIN;
CREATE TABLE IF NOT EXISTS tenants (
  tenant_id TEXT NOT NULL,
  slug TEXT NOT NULL,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id),
  UNIQUE (slug)
);

CREATE TABLE IF NOT EXISTS principals (
  principal_id TEXT NOT NULL,
  tenant_id TEXT NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
  email TEXT NOT NULL,
  display_name TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (principal_id),
  UNIQUE (tenant_id, email)
);
CREATE INDEX IF NOT EXISTS idx_principals_tenant_id ON principals (tenant_id);

CREATE TABLE IF NOT EXISTS work_items (
  work_item_id TEXT NOT NULL,
  tenant_id TEXT NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
  scenario TEXT NOT NULL CHECK (scenario IN ('http-observability', 'forms-chat-workflow', 'cached-rpc', 'big-org-example-commerce', 'big-org-example-collaboration', 'big-org-example-operations')),
  status TEXT NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed')),
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (work_item_id)
);
CREATE INDEX IF NOT EXISTS idx_work_items_tenant_id ON work_items (tenant_id);
CREATE INDEX IF NOT EXISTS idx_work_items_scenario ON work_items (scenario);
CREATE INDEX IF NOT EXISTS idx_work_items_status ON work_items (status);

CREATE TABLE IF NOT EXISTS audit_events (
  audit_event_id TEXT NOT NULL,
  tenant_id TEXT NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
  principal_id TEXT REFERENCES principals(principal_id) ON DELETE SET NULL,
  event_type TEXT NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (audit_event_id)
);
CREATE INDEX IF NOT EXISTS idx_audit_events_tenant_id ON audit_events (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_event_type ON audit_events (event_type);

COMMIT;
