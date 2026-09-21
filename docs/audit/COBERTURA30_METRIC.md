# Métrica Cobertura30 (F0) — denominador independiente

Estado: definida e instrumentada (app 1.1.21+ reporta el embudo por bucket de
5 min). Reemplaza la métrica anterior basada en los propios fixes, que daba
100 % con 1 punto en 24 h (Kevin, 20-sep).

## Definición

```
Cobertura30 = segundos con dt ≤ 30 s EN MOVIMIENTO / segundos en movimiento (sensor)
```

- **Numerador**: pares de fixes consecutivos en movimiento (velocidad previa
  > 3 kn) con separación ≤ 30 s, sumando su dt.
- **Denominador [independiente]**: `mv` (segundos en movimiento por sensor) que
  la app acumula por bucket de 5 min y sube en el snapshot de salud. NO depende
  de que existan fixes.
- **Por dispositivo y día.** Una jornada sin datos sube el denominador y el
  numerador queda en 0 → cobertura 0 % (honesto).

## SQL (producción)

```sql
-- Numerador: segundos cubiertos con dt<=30s en movimiento (hoy, por equipo)
WITH g AS (
  SELECT deviceid, fixtime, speed,
    lag(speed) OVER (PARTITION BY deviceid ORDER BY fixtime) prev_speed,
    EXTRACT(EPOCH FROM (fixtime - lag(fixtime) OVER (PARTITION BY deviceid ORDER BY fixtime)))::numeric dt
  FROM tc_positions
  WHERE fixtime > (now() AT TIME ZONE 'America/Guayaquil') - interval '24 hours'
)
SELECT d.name equipo,
       round(sum(CASE WHEN dt <= 30 THEN dt ELSE 0 END)) segundos_cubiertos_30s
FROM g JOIN tc_devices d ON d.id = g.deviceid
WHERE prev_speed > 3 AND dt IS NOT NULL
GROUP BY 1;

-- Denominador: segundos en movimiento por sensor (embudo en attributes)
SELECT d.name equipo,
       round(sum((h.attributes::jsonb->>'funnel')::jsonb->>'mv')::numeric) segundos_movimiento_sensor,
       count(*) buckets
FROM tc_device_health h
JOIN tc_devices d ON d.id = h.deviceid
WHERE h.eventtype = 'HEARTBEAT'
  AND h.attributes::jsonb ? 'funnel'
  AND h.ts > (now() AT TIME ZONE 'America/Guayaquil') - interval '24 hours'
GROUP BY 1;

-- Cobertura30 = numerador / denominador (por equipo, mismo rango)
```

## Metas (por modelo de equipo, jornada laboral, pantalla apagada)

| KPI | Meta |
|---|---|
| Cobertura30 | ≥ 90 % |
| Hueco máximo en movimiento | ≤ 120 s en el 95 % de los viajes |
| Cortes con datos en BD (HIDDEN_BY_FILTER) | 0 |
| Rectas "medido" sobre un hueco | 0 |
| Fixes descartados sin motivo registrado | 0 |

## Notas de honestidad

- El denominador por sensor puede sobreestimar movimiento (vibración de motor
  con vehículo quieto) o subestimarlo (sensor pausado). Se compara también con
  el logger de referencia en la validación de campo (F4); si la desviación es
  > 10 %, se reporta y se ajusta.
- La primera lectura del embudo es línea base (delta 0) por diseño: no se
  inventan datos del bucket en el que arrancó la app.

## Nota de versión en la app (política pedida)

A partir de esta fase, las notas OTA NO llevan descripción técnica ni de IA:
solo **"Actualizar a la versión 1.x.x"**.
