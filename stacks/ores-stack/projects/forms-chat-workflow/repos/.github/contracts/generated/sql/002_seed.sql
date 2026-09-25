-- GENERATED. DO NOT EDIT.
BEGIN;
INSERT INTO form_submissions (id, form_key, conversation_id, state, audience, created_at) VALUES ('sub-demo-1', 'intake', 'convo-demo-1', 'received', 'entrepreneurs', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
INSERT INTO conversation_messages (id, submission_id, direction, body, created_at) VALUES ('msg-demo-1', 'sub-demo-1', 'inbound', 'hello from seeded form', '2026-01-01T00:00:01Z') ON CONFLICT DO NOTHING;
COMMIT;
