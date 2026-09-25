-- GENERATED. DO NOT EDIT.
BEGIN;
INSERT INTO request_observations (id, request_id, route, status_code, duration_ms, outcome, created_at) VALUES ('obs-demo-1', 'req-demo-1', '/comparison/http-observability', 200, 4, 'ok', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
COMMIT;
