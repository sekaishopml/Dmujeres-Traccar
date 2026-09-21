-- F1: verificación de fidelidad de ruta por dispositivo y día.
--
-- Responde la pregunta "¿el trazo sigue la vía?" con tres números honestos:
--  1. mediana y p95 del intervalo ENTRE puntos en movimiento (>100 m de avance),
--  2. huecos > 120 s en movimiento (rectas potenciales),
--  3. embudo del día (mv = segundos en movimiento, denominador de Cobertura30).
--
-- Uso: psql -U traccar -d traccar -f cobertura30_diaria.sql
-- Nota: el umbral de 100 m es el criterio de "tramo en movimiento" del panel.

WITH p AS (
    SELECT
        deviceid,
        fixtime::date AS dia,
        fixtime,
        latitude,
        longitude,
        LEAD(fixtime) OVER w AS next_t,
        LEAD(latitude) OVER w AS next_lat,
        LEAD(longitude) OVER w AS next_lon
    FROM tc_positions
    WHERE fixtime > now() - interval '7 days'
    WINDOW w AS (PARTITION BY deviceid ORDER BY fixtime)
),
tramos AS (
    SELECT
        deviceid,
        dia,
        EXTRACT(EPOCH FROM (next_t - fixtime)) AS seg,
        111000 * sqrt(
            power(next_lat - latitude, 2)
            + power((next_lon - longitude) * cos(radians(latitude)), 2)
        ) AS metros
    FROM p
    WHERE next_t IS NOT NULL
)
SELECT
    t.deviceid,
    d.name,
    t.dia,
    COUNT(*) FILTER (WHERE t.metros > 100) AS tramos_movimiento,
    ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY t.seg)
        FILTER (WHERE t.metros > 100)::numeric, 0) AS mediana_mov_seg,
    ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY t.seg)
        FILTER (WHERE t.metros > 100)::numeric, 0) AS p95_mov_seg,
    COUNT(*) FILTER (WHERE t.metros > 100 AND t.seg > 120) AS huecos_mov_mayores_120s,
    COALESCE(f.moving_seconds, 0) AS segundos_en_movimiento_embudo
FROM tramos t
JOIN tc_devices d ON d.id = t.deviceid
LEFT JOIN v_device_funnel_daily f ON f.deviceid = t.deviceid AND f.day = t.dia
WHERE t.metros > 100
GROUP BY t.deviceid, d.name, t.dia, f.moving_seconds
ORDER BY t.dia DESC, t.deviceid;
