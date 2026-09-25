-- GENERATED. DO NOT EDIT.
-- Additive domain constraints projected from JSON Schema enum authorities.
BEGIN;
DO $ores$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_request_observations_outcome_enum'
      AND conrelid = 'public.request_observations'::regclass
  ) THEN
    ALTER TABLE public.request_observations
      ADD CONSTRAINT ck_request_observations_outcome_enum CHECK (outcome IN ('ok', 'error'));
  END IF;
END
$ores$;
COMMIT;
