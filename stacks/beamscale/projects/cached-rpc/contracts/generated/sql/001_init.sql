-- GENERATED. DO NOT EDIT.
BEGIN;
CREATE TABLE IF NOT EXISTS rpc_invocations (
  id TEXT NOT NULL,
  operation TEXT NOT NULL,
  cache_key TEXT NOT NULL,
  cache_state TEXT NOT NULL,
  duration_ms INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS rpc_cache_entries (
  cache_key TEXT NOT NULL,
  payload_json JSONB NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (cache_key)
);
COMMIT;
