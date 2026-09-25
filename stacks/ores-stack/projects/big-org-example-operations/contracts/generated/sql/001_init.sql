-- GENERATED. DO NOT EDIT.
BEGIN;
CREATE TABLE IF NOT EXISTS enterprise_work_items (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  payload TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id)
);
COMMIT;
