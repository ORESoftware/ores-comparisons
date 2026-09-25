-- GENERATED. DO NOT EDIT.
-- Additive domain constraints projected from JSON Schema enum authorities.
BEGIN;
DO $ores$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_enterprise_work_items_kind_enum'
      AND conrelid = 'public.enterprise_work_items'::regclass
  ) THEN
    ALTER TABLE public.enterprise_work_items
      ADD CONSTRAINT ck_enterprise_work_items_kind_enum CHECK (kind IN ('create', 'update', 'sync'));
  END IF;
END
$ores$;
COMMIT;
