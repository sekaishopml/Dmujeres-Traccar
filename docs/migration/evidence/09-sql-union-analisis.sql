\pset pager off
\echo '===== 1. FUENTES CRUDAS: filas, claves (deviceid,fixtime), duplicados internos ====='
WITH s AS (
    SELECT 'traccar.tc_positions' AS fuente, count(*) AS filas,
           count(DISTINCT (deviceid, fixtime)) AS claves FROM public.u_prod
    UNION ALL SELECT 'traccar.tc_positions_bak_20260903', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.u_bak
    UNION ALL SELECT 'traccar_qa.tc_positions', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.u_qa
    UNION ALL SELECT 'dump traccar-20260925-030001', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_d0925
    UNION ALL SELECT 'dump traccar-20260924-030001', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_d0924
    UNION ALL SELECT 'dump traccar-20260923-030001', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_d0923
    UNION ALL SELECT 'dump traccar-pre-cambio-usuarios', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_dprecambio
    UNION ALL SELECT 'dump traccar-20260922-030001', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_d0922b
    UNION ALL SELECT 'dump traccar-pre-purga-recorridos', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_dprepurga
    UNION ALL SELECT 'dump traccar-20260922-003135', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_d0922a
    UNION ALL SELECT 'dump traccar-20260919-125422', count(*),
           count(DISTINCT (deviceid, fixtime)) FROM public.pos_d0919
)
SELECT fuente, filas, claves, filas - claves AS duplicados_intra,
       round(100.0 * (filas - claves) / nullif(filas, 0), 3) AS pct_dup
FROM s ORDER BY filas DESC;

\echo '===== 2. CONJUNTO UNIFICADO DEDUPLICADO (prioridad prod > bak > qa > dumps recientes) ====='
DROP TABLE IF EXISTS public.u_final;
CREATE TABLE public.u_final AS
WITH todas AS (
    SELECT 1 AS prio, 'traccar.tc_positions'::text AS fuente, p.* FROM public.u_prod p
    UNION ALL SELECT 2, 'traccar.tc_positions_bak_20260903', b.* FROM public.u_bak b
    UNION ALL SELECT 3, 'traccar_qa.tc_positions', q.* FROM public.u_qa q
    UNION ALL SELECT 4, 'dump traccar-20260925-030001', d.* FROM public.pos_d0925 d
    UNION ALL SELECT 5, 'dump traccar-20260924-030001', d.* FROM public.pos_d0924 d
    UNION ALL SELECT 6, 'dump traccar-20260923-030001', d.* FROM public.pos_d0923 d
    UNION ALL SELECT 7, 'dump traccar-pre-cambio-usuarios', d.* FROM public.pos_dprecambio d
    UNION ALL SELECT 8, 'dump traccar-20260922-030001', d.* FROM public.pos_d0922b d
    UNION ALL SELECT 9, 'dump traccar-pre-purga-recorridos', d.* FROM public.pos_dprepurga d
    UNION ALL SELECT 10, 'dump traccar-20260922-003135', d.* FROM public.pos_d0922a d
    UNION ALL SELECT 11, 'dump traccar-20260919-125422', d.* FROM public.pos_d0919 d
), ranked AS (
    SELECT *, row_number() OVER (
        PARTITION BY deviceid, fixtime
        ORDER BY prio, servertime DESC NULLS LAST, id DESC
    ) AS rn
    FROM todas
)
SELECT prio, fuente, id, protocol, deviceid, servertime, devicetime, fixtime, valid,
       latitude, longitude, altitude, speed, course, address, attributes, accuracy,
       network, geofenceids
FROM ranked WHERE rn = 1;
CREATE INDEX ON public.u_final (deviceid, fixtime);

SELECT count(*) AS filas_unificadas, count(DISTINCT deviceid) AS dispositivos,
       min(fixtime) AS min_fx, max(fixtime) AS max_fx,
       count(DISTINCT (deviceid, fixtime)) AS claves
FROM public.u_final;

\echo '===== 3. APORTE POR FUENTE (filas ganadoras) ====='
SELECT fuente, prio, count(*) AS filas, count(DISTINCT deviceid) AS dispositivos,
       min(fixtime) AS min_fx, max(fixtime) AS max_fx
FROM public.u_final GROUP BY 1, 2 ORDER BY prio;

\echo '===== 4. FILAS POR MES (fixtime) ====='
SELECT to_char(fixtime, 'YYYY-MM') AS mes, count(*) AS filas,
       count(DISTINCT deviceid) AS dispositivos,
       min(fixtime) AS min_fx, max(fixtime) AS max_fx
FROM public.u_final GROUP BY 1 ORDER BY 1;

\echo '===== 5. FILAS POR MES Y DISPOSITIVO ====='
SELECT to_char(fixtime, 'YYYY-MM') AS mes, deviceid, count(*) AS filas,
       min(fixtime) AS min_fx, max(fixtime) AS max_fx
FROM public.u_final GROUP BY 1, 2 ORDER BY 1, 2;

\echo '===== 6. SOLAPAMIENTO ENTRE FUENTES (claves compartidas) ====='
SELECT 'u_bak vs u_qa' AS par,
       (SELECT count(*) FROM public.u_bak b
         WHERE EXISTS (SELECT 1 FROM public.u_qa q
                        WHERE q.deviceid = b.deviceid AND q.fixtime = b.fixtime)) AS bak_en_qa,
       (SELECT count(*) FROM public.u_qa q
         WHERE EXISTS (SELECT 1 FROM public.u_bak b
                        WHERE b.deviceid = q.deviceid AND b.fixtime = q.fixtime)) AS qa_en_bak
UNION ALL
SELECT 'u_qa vs pre-purga',
       (SELECT count(*) FROM public.u_qa q
         WHERE EXISTS (SELECT 1 FROM public.pos_dprepurga p
                        WHERE p.deviceid = q.deviceid AND p.fixtime = q.fixtime)),
       (SELECT count(*) FROM public.pos_dprepurga p
         WHERE EXISTS (SELECT 1 FROM public.u_qa q
                        WHERE q.deviceid = p.deviceid AND q.fixtime = p.fixtime))
UNION ALL
SELECT 'u_prod vs pre-purga',
       (SELECT count(*) FROM public.u_prod p
         WHERE EXISTS (SELECT 1 FROM public.pos_dprepurga d
                        WHERE d.deviceid = p.deviceid AND d.fixtime = p.fixtime)),
       (SELECT count(*) FROM public.pos_dprepurga d
         WHERE EXISTS (SELECT 1 FROM public.u_prod p
                        WHERE p.deviceid = d.deviceid AND p.fixtime = d.fixtime))
UNION ALL
SELECT 'u_prod vs d0925',
       (SELECT count(*) FROM public.u_prod p
         WHERE EXISTS (SELECT 1 FROM public.pos_d0925 d
                        WHERE d.deviceid = p.deviceid AND d.fixtime = p.fixtime)),
       (SELECT count(*) FROM public.pos_d0925 d
         WHERE EXISTS (SELECT 1 FROM public.u_prod p
                        WHERE p.deviceid = d.deviceid AND p.fixtime = d.fixtime));

\echo '===== 7. CLAVES APORTADAS SOLO POR DUMPS (no estan en prod/bak/qa) ====='
SELECT d.fuente, count(*) AS claves_exclusivas,
       count(DISTINCT d.deviceid) AS dispositivos,
       min(d.fixtime) AS min_fx, max(d.fixtime) AS max_fx
FROM (
    SELECT 'dump traccar-20260925-030001' AS fuente, deviceid, fixtime, id FROM public.pos_d0925
    UNION ALL SELECT 'dump traccar-20260924-030001', deviceid, fixtime, id FROM public.pos_d0924
    UNION ALL SELECT 'dump traccar-20260923-030001', deviceid, fixtime, id FROM public.pos_d0923
    UNION ALL SELECT 'dump traccar-pre-cambio-usuarios', deviceid, fixtime, id FROM public.pos_dprecambio
    UNION ALL SELECT 'dump traccar-20260922-030001', deviceid, fixtime, id FROM public.pos_d0922b
    UNION ALL SELECT 'dump traccar-pre-purga-recorridos', deviceid, fixtime, id FROM public.pos_dprepurga
    UNION ALL SELECT 'dump traccar-20260922-003135', deviceid, fixtime, id FROM public.pos_d0922a
    UNION ALL SELECT 'dump traccar-20260919-125422', deviceid, fixtime, id FROM public.pos_d0919
) d
WHERE NOT EXISTS (SELECT 1 FROM public.u_prod p WHERE p.deviceid = d.deviceid AND p.fixtime = d.fixtime)
  AND NOT EXISTS (SELECT 1 FROM public.u_bak b WHERE b.deviceid = d.deviceid AND b.fixtime = d.fixtime)
  AND NOT EXISTS (SELECT 1 FROM public.u_qa q WHERE q.deviceid = d.deviceid AND q.fixtime = d.fixtime)
GROUP BY 1 ORDER BY 1;

\echo '===== 8. HUECOS > 24 H EN EL UNIFICADO, POR DISPOSITIVO ====='
WITH g AS (
    SELECT deviceid, fixtime,
           lag(fixtime) OVER (PARTITION BY deviceid ORDER BY fixtime) AS prev
    FROM public.u_final
)
SELECT deviceid, prev, fixtime, fixtime - prev AS hueco
FROM g WHERE prev IS NOT NULL AND fixtime - prev > interval '24 hours'
ORDER BY hueco DESC;

\echo '===== 9. HUECOS > 6 H (contexto de jornada activa) ====='
WITH g AS (
    SELECT deviceid, fixtime,
           lag(fixtime) OVER (PARTITION BY deviceid ORDER BY fixtime) AS prev
    FROM public.u_final
)
SELECT deviceid, prev, fixtime, fixtime - prev AS hueco
FROM g WHERE prev IS NOT NULL AND fixtime - prev > interval '6 hours'
ORDER BY hueco DESC LIMIT 40;

\echo '===== 10. ANOMALIAS ====='
SELECT 'fixtime futuro (> now())' AS anomalia, count(*) AS filas,
       min(fixtime) AS min_fx, max(fixtime) AS max_fx FROM public.u_final WHERE fixtime > now()
UNION ALL SELECT 'fixtime anterior a 2026-01-01', count(*), min(fixtime), max(fixtime)
    FROM public.u_final WHERE fixtime < '2026-01-01'
UNION ALL SELECT 'precision_m > 500', count(*), min(accuracy), max(accuracy)
    FROM public.u_final WHERE accuracy > 500
UNION ALL SELECT 'valid = false', count(*), NULL, NULL
    FROM public.u_final WHERE valid = false
UNION ALL SELECT 'mock macias (id en backup mock)', count(*), min(fixtime), max(fixtime)
    FROM public.u_final WHERE id IN (73883,73887,73893,84216,84217,84219,77604,77605);

\echo '===== 10b. DETALLE DE ANOMALIAS (limite 50) ====='
SELECT id, deviceid, fixtime, latitude, longitude, accuracy, valid, speed, fuente
FROM public.u_final
WHERE fixtime > now()
   OR fixtime < '2026-01-01'
   OR accuracy > 500
   OR valid = false
   OR id IN (73883,73887,73893,84216,84217,84219,77604,77605)
ORDER BY fixtime LIMIT 50;

\echo '===== 11. COBERTURA POR DISPOSITIVO ====='
SELECT deviceid, count(*) AS filas, min(fixtime) AS primer_fx, max(fixtime) AS ultimo_fx,
       count(DISTINCT fixtime::date) AS dias_con_datos,
       count(DISTINCT to_char(fixtime, 'YYYY-MM')) AS meses
FROM public.u_final GROUP BY 1 ORDER BY 1;

\echo '===== 12. VALIDEZ DE JSON EN attributes (para el ETL) ====='
SELECT count(*) AS total,
       count(*) FILTER (WHERE attributes IS NULL OR btrim(attributes) = '') AS vacios,
       count(*) FILTER (WHERE attributes IS NOT NULL AND btrim(attributes) <> ''
                         AND attributes !~ '^\s*[\{\[].*[\}\]]\s*$') AS sospechosos
FROM public.u_final;
