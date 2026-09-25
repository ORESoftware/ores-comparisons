INSERT INTO rpc_resources (id, tenant_id, resource_key, display_name, revision, active)
VALUES ('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001', 'alpha', 'Alpha resource', 1, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO rpc_events (id, tenant_id, resource_id, kind, payload_json, sequence)
VALUES ('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'created', '{"source":"seed"}', 1)
ON CONFLICT (id) DO NOTHING;
