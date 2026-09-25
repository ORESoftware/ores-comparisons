-- GENERATED. DO NOT EDIT.
BEGIN;
INSERT INTO rpc_invocations (id, operation, cache_key, cache_state, duration_ms, created_at) VALUES ('rpc-demo-1', 'GetCachedComparison', 'comparison:demo', 'miss', 7, '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO rpc_cache_entries (cache_key, payload_json, expires_at, created_at) VALUES ('comparison:demo', '{"value":"seeded"}', '2099-01-01T00:00:00Z', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
COMMIT;
