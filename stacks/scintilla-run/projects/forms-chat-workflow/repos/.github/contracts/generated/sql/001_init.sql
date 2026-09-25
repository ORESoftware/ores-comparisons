-- GENERATED. DO NOT EDIT.
BEGIN;
CREATE TABLE IF NOT EXISTS form_submissions (
  id TEXT NOT NULL,
  form_key TEXT NOT NULL,
  conversation_id TEXT NOT NULL,
  state TEXT NOT NULL,
  audience TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS conversation_messages (
  id TEXT NOT NULL,
  submission_id TEXT NOT NULL,
  direction TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id)
);
COMMIT;
