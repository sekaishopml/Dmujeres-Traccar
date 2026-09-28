\pset pager off
\echo '===== 10. ANOMALIAS (corregido) ====='
SELECT 'fixtime futuro (> now())' AS anomalia, count(*) AS filas,
       min(fixtime)::text AS min_fx, max(fixtime)::text AS max_fx
FROM public.u_final WHERE fixtime > now()
UNION ALL SELECT 'fixtime anterior a 2026-01-01', count(*),
       min(fixtime)::text, max(fixtime)::text
    FROM public.u_final WHERE fixtime < '2026-01-01'
UNION ALL SELECT 'precision_m > 500', count(*),
       min(accuracy)::text, max(accuracy)::text
    FROM public.u_final WHERE accuracy > 500
UNION ALL SELECT 'valid = false', count(*), NULL, NULL
    FROM public.u_final WHERE valid = false
UNION ALL SELECT 'mock macias (8 ids del backup)', count(*),
       min(fixtime)::text, max(fixtime)::text
    FROM public.u_final WHERE id IN (73883,73887,73893,84216,84217,84219,77604,77605);

\echo '===== 10c. FILAS MOCK EN u_final (todos los ids del backup mock) ====='
SELECT id, deviceid, fixtime, latitude, longitude, speed, accuracy, valid, fuente
FROM public.u_final
WHERE id IN (73883,73887,73893,84216,84217,84219,77604,77605)
ORDER BY id;

\echo '===== 10d. valid=false por dispositivo y fuente ====='
SELECT deviceid, fuente, count(*) AS filas_invalidas,
       min(fixtime) AS min_fx, max(fixtime) AS max_fx
FROM public.u_final WHERE valid = false
GROUP BY 1, 2 ORDER BY 1, 2;

\echo '===== 10e. precision > 500 por dispositivo ====='
SELECT deviceid, count(*) AS filas, round(max(accuracy)::numeric, 1) AS max_precision_m,
       min(fixtime) AS min_fx, max(fixtime) AS max_fx
FROM public.u_final WHERE accuracy > 500 GROUP BY 1 ORDER BY 2 DESC;

\echo '===== 10f. speed: rango en nudos (origen) ====='
SELECT min(speed) AS min_nudos, max(speed) AS max_nudos,
       round(avg(speed)::numeric, 3) AS avg_nudos,
       round((max(speed) * 1.852)::numeric, 2) AS max_kmh
FROM public.u_final;
