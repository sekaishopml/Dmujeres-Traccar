-- Actividad con rango "de tal hora a tal hora" (app 2.4.1). Opcional: las
-- actividades anteriores solo tienen hora de inicio.
BEGIN;
ALTER TABLE operations.dmt_actividad
    ADD COLUMN IF NOT EXISTS hora_fin TEXT CHECK (hora_fin IS NULL OR hora_fin ~ '^[0-2][0-9]:[0-5][0-9]$');
COMMIT;
