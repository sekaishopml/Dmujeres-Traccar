-- ============================================================================
-- 04_refrescar_posicion_actual.sql
-- Proyecto: DMujeres Tracking - base nueva (dmt-db / dmujeres)
-- ============================================================================
-- Refresca el modelo de lectura tracking.dmt_posicion_actual con el ultimo
-- registro de tracking.dmt_posicion por dispositivo habilitado.
--
-- Por que existe: la migracion cargo el historico (dmt_posicion) pero dejo
-- dmt_posicion_actual vacia; el servicio de tracking la mantendra en operacion
-- (una fila por dispositivo). Este script es idempotente y sirve para que el
-- panel en vivo de la API tenga datos reales desde el primer arranque.
--
-- Solo escribe en la base nueva. No toca produccion (dmj-db).
-- ============================================================================

BEGIN;

INSERT INTO tracking.dmt_posicion_actual (
    dispositivo_id, posicion_id, latitud, longitud, altitud_m, velocidad_kmh,
    rumbo_grados, precision_m, bateria_pct, valida, fijado_en, registrado_en,
    recibido_en, atributos
)
SELECT DISTINCT ON (p.dispositivo_id)
       p.dispositivo_id, p.id, p.latitud, p.longitud, p.altitud_m, p.velocidad_kmh,
       p.rumbo_grados, p.precision_m,
       coalesce(p.bateria_pct, (p.atributos->>'batteryLevel')::real),
       p.valida, p.fijado_en, p.registrado_en, p.recibido_en, p.atributos
FROM tracking.dmt_posicion p
JOIN tracking.dmt_dispositivo d ON d.id = p.dispositivo_id AND d.habilitado
ORDER BY p.dispositivo_id, p.registrado_en DESC, p.id DESC
ON CONFLICT (dispositivo_id) DO UPDATE SET
    posicion_id    = EXCLUDED.posicion_id,
    latitud        = EXCLUDED.latitud,
    longitud       = EXCLUDED.longitud,
    altitud_m      = EXCLUDED.altitud_m,
    velocidad_kmh  = EXCLUDED.velocidad_kmh,
    rumbo_grados   = EXCLUDED.rumbo_grados,
    precision_m    = EXCLUDED.precision_m,
    bateria_pct    = EXCLUDED.bateria_pct,
    valida         = EXCLUDED.valida,
    fijado_en      = EXCLUDED.fijado_en,
    registrado_en  = EXCLUDED.registrado_en,
    recibido_en    = EXCLUDED.recibido_en,
    atributos      = EXCLUDED.atributos,
    actualizado_en = now()
WHERE EXCLUDED.registrado_en >= tracking.dmt_posicion_actual.registrado_en;

COMMIT;
