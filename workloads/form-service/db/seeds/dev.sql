INSERT INTO form_definitions (id, tenant_id, form_key, title, version, active, created_at)
VALUES ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 'intake', 'Example intake', 1, TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO form_submissions (id, tenant_id, form_definition_id, status, response_json, created_at)
VALUES ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000101', 'submitted', '{"company":"Acme"}', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
