-- F0: embudo de salud por dispositivo y día.
--
-- El embudo viaja en `tc_device_health.attributes.funnel` como string JSON
-- (doble codificación: {"funnel": "{\"rx\":..,\"rj\":..,\"rr\":{..},\"en\":..,\"ak\":..,\"mv\":..,\"w\":..}"}).
-- Claves: rx=recibidos, rj=rechazados, rr=rechazos por motivo, en=encolados,
-- ak=confirmados, mv=segundos en movimiento, w=segundos de ventana.
--
-- Uso: psql -U traccar -d traccar -f funnel_daily.sql
-- La vista es la base de Cobertura30 (denominador independiente = mv).

CREATE OR REPLACE VIEW v_device_funnel_daily AS
SELECT
    h.deviceid,
    d.name AS device_name,
    (h.ts AT TIME ZONE 'America/Bogota')::date AS day,
    COUNT(*)                                                        AS buckets,
    COALESCE(SUM(((h.attributes ->> 'funnel')::jsonb ->> 'rx')::bigint), 0) AS received,
    COALESCE(SUM(((h.attributes ->> 'funnel')::jsonb ->> 'rj')::bigint), 0) AS rejected,
    COALESCE(SUM(((h.attributes ->> 'funnel')::jsonb ->> 'en')::bigint), 0) AS enqueued,
    COALESCE(SUM(((h.attributes ->> 'funnel')::jsonb ->> 'ak')::bigint), 0) AS acked,
    COALESCE(SUM(((h.attributes ->> 'funnel')::jsonb ->> 'mv')::bigint), 0) AS moving_seconds,
    COALESCE(SUM(((h.attributes ->> 'funnel')::jsonb ->> 'w')::bigint), 0)  AS window_seconds
FROM tc_device_health h
JOIN tc_devices d ON d.id = h.deviceid
WHERE h.attributes ? 'funnel'
GROUP BY h.deviceid, d.name, (h.ts AT TIME ZONE 'America/Bogota')::date;

COMMENT ON VIEW v_device_funnel_daily IS
    'F0: embudo de salud agregado por dispositivo y dia (America/Bogota). Denominador de Cobertura30 = moving_seconds.';

-- Consulta de alerta (la usa AdminAlertsService): dispositivos con jornada
-- activa (mobile.journeyId > 0) sin ningún bucket de embudo en las últimas 24 h.
-- Si un piloto aparece aquí, la telemetría de salud no está llegando.
SELECT
    d.id,
    d.name,
    MAX(h.ts) AS last_bucket_at
FROM tc_devices d
LEFT JOIN tc_device_health h
       ON h.deviceid = d.id
      AND h.attributes ? 'funnel'
      AND h.ts >= NOW() - INTERVAL '24 hours'
WHERE (d.attributes::jsonb ->> 'mobile.journeyId') ~ '^[0-9]+$'
  AND (d.attributes::jsonb ->> 'mobile.journeyId')::bigint > 0
GROUP BY d.id, d.name
HAVING MAX(h.ts) IS NULL OR MAX(h.ts) < NOW() - INTERVAL '24 hours';
