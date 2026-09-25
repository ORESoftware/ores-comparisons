-- GENERATED. DO NOT EDIT.
-- Additive domain constraints projected from JSON Schema enum authorities.
BEGIN;
DO $ores$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_rpc_invocations_cache_state_enum'
      AND conrelid = 'public.rpc_invocations'::regclass
  ) THEN
    ALTER TABLE public.rpc_invocations
      ADD CONSTRAINT ck_rpc_invocations_cache_state_enum CHECK (cache_state IN ('hit', 'miss', 'bypass'));
  END IF;
END
$ores$;
COMMIT;
