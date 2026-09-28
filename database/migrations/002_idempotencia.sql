-- 002 — Idempotencia de posiciones (boot_id + secuencia).
--
-- Por qué: una retransmisión del lote móvil (timeout con POST ya aplicado)
-- duplicaba fixes porque no había identidad estable. La identidad pasa a ser
-- (dispositivo_id, boot_id, local_sequence); boot_id es UUID por arranque de
-- proceso y local_sequence un contador persistente que solo incrementa.
--
-- Cambios:
--   * tracking.dmt_posicion gana boot_id text y local_sequence bigint.
--   * UNIQUE parcial (dispositivo_id, registrado_en, boot_id, local_sequence)
--     WHERE boot_id IS NOT NULL: la tabla es particionada por registrado_en y
--     PostgreSQL exige la clave de partición en todo índice único. Como una
--     retransmisión trae el mismo captured_at, el dedupe sigue siendo exacto;
--     los históricos sin identidad quedan fuera por el WHERE.
--   * Purga de 1 fix corrupto de 2037 (reloj adelantado del equipo 50,
--     id 92908, registrado 2037-10-15T18:59:35Z, lat 37.422 lon -122.084):
--     sin valor operativo, solo poluciona la partición 2037_10.
--
-- Reversible (cómo deshacer, en orden):
--   DROP INDEX IF EXISTS tracking.uq_dmt_posicion_identidad;
--   ALTER TABLE tracking.dmt_posicion DROP COLUMN IF EXISTS local_sequence;
--   ALTER TABLE tracking.dmt_posicion DROP COLUMN IF EXISTS boot_id;
-- La fila de 2037 no se recupera (purga documentada de dato corrupto).
--
-- No se aplica automáticamente: entregar el archivo y aplicar en ventana de
-- mantenimiento con la flota detenida. Idempotente (IF NOT EXISTS).

BEGIN;

ALTER TABLE tracking.dmt_posicion
  ADD COLUMN IF NOT EXISTS boot_id text;

ALTER TABLE tracking.dmt_posicion
  ADD COLUMN IF NOT EXISTS local_sequence bigint;

CREATE UNIQUE INDEX IF NOT EXISTS uq_dmt_posicion_identidad
  ON tracking.dmt_posicion (dispositivo_id, registrado_en, boot_id, local_sequence)
  WHERE boot_id IS NOT NULL;

-- Purga del único fix absurdo de 2037: WHERE exacto por id para no tocar
-- ninguna otra fila. Recibido el 2026-09-28, capturado con reloj en 2037.
DELETE FROM tracking.dmt_posicion
 WHERE id = 92908;

COMMIT;
