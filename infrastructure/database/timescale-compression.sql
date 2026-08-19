-- TimescaleDB: compresión y retención para tc_positions
-- Conserva todo el histórico; la compresión reduce el espacio sin borrar datos.
-- Se puede ejecutar varias veces sin problema.

-- 1) Chunk mensual (los chunks nuevos se crean de 1 mes; mejor compresión y menos overhead)
SELECT set_chunk_time_interval('tc_positions', INTERVAL '1 month');

-- 2) Habilitar compresión: agrupa por dispositivo, ordena por tiempo descendente
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM timescaledb_information.hypertables
    WHERE hypertable_name = 'tc_positions' AND compression_enabled
  ) THEN
    ALTER TABLE tc_positions SET (
      timescaledb.compress,
      timescaledb.compress_segmentby = 'deviceid',
      timescaledb.compress_orderby = 'fixtime DESC'
    );
  END IF;
END $$;

-- 3) Política: comprimir en segundo plano los chunks con más de 1 día
--    (lo reciente queda descomprimido para escritura/lectura rápida)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM timescaledb_information.jobs
    WHERE proc_name = 'policy_compression' AND hypertable_name = 'tc_positions'
  ) THEN
    PERFORM add_compression_policy('tc_positions', INTERVAL '1 day');
  END IF;
END $$;

-- 4) RETENCIÓN: DESACTIVADA por decisión (D-014: conservar todo).
--    Si en el futuro se quiere acotar, descomentar:
--    SELECT add_retention_policy('tc_positions', INTERVAL '5 years');

-- 5) Opcional: eventos y acciones (volúmenes pequeños; comprimir también ayuda)
--    ALTER TABLE tc_events SET (
--      timescaledb.compress,
--      timescaledb.compress_segmentby = 'deviceid',
--      timescaledb.compress_orderby = 'eventtime DESC'
--    );
--    SELECT add_compression_policy('tc_events', INTERVAL '1 day');
