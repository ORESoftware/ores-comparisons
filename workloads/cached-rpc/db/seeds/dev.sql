INSERT INTO rpc_methods (id, tenant_id, operation_key, transport, active, created_at)
VALUES ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000001','health.get','json-rpc',TRUE,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
INSERT INTO rpc_cache_entries (id, tenant_id, rpc_method_id, cache_key, response_json, state, expires_at, revision, created_at)
VALUES ('00000000-0000-0000-0000-000000000302','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000301','health:v1','{"ok":true}','fresh',CURRENT_TIMESTAMP + INTERVAL '5 minutes',1,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
