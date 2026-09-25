-- GENERATED. DO NOT EDIT.
-- Additive domain constraints projected from JSON Schema enum authorities.
BEGIN;
DO $ores$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_form_submissions_state_enum'
      AND conrelid = 'public.form_submissions'::regclass
  ) THEN
    ALTER TABLE public.form_submissions
      ADD CONSTRAINT ck_form_submissions_state_enum CHECK (state IN ('received', 'synced', 'completed'));
  END IF;
END
$ores$;
DO $ores$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_conversation_messages_direction_enum'
      AND conrelid = 'public.conversation_messages'::regclass
  ) THEN
    ALTER TABLE public.conversation_messages
      ADD CONSTRAINT ck_conversation_messages_direction_enum CHECK (direction IN ('inbound', 'outbound'));
  END IF;
END
$ores$;
COMMIT;
