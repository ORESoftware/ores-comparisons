-- GENERATED. DO NOT EDIT.
BEGIN;
CREATE TABLE IF NOT EXISTS request_observations (
  id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  route TEXT NOT NULL,
  status_code INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  outcome TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id)
);
COMMIT;
