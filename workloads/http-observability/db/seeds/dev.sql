INSERT INTO http_observations (id, tenant_id, request_id, method, path, status_code, duration_ms, outcome, created_at)
VALUES ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 'seed-request', 'GET', '/healthz', 200, 3, 'ok', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO telemetry_spans (id, tenant_id, http_observation_id, trace_id, span_name, duration_ms, created_at)
VALUES ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000101', 'seed-trace', 'http.server', 3, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
