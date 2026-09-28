# Histórico unificado de posiciones (FASE 3, parte 2)

Fecha de trabajo: 2026-09-25 (UTC). Fuente de verdad de esquema:
`database/schema/03_tracking.sql` y `docs/database/MAPA-LEGADO-DMT.md`
(FASE 3 parte 1).

Objetivo: reconstruir el histórico fragmentado de posiciones entre
`traccar`, `tc_positions_bak_20260903`, `traccar_qa` y los dumps de
`/var/backups/dmj`, deduplicarlo y dejarlo listo para cargar en
`tracking.dmt_posicion`.

Producción es **solo lectura**: no se ejecutó ningún INSERT/UPDATE/DELETE
sobre `traccar` ni `traccar_qa`. Todo el trabajo se hizo en bases scratch
creadas y eliminadas (`dmt_hist_test`, `dmt_etl_scratch`, `dmt_new_test`).

---

## 1. Inventario de fuentes

`fixtime` y `devicetime` son idénticos en el 100 % de las filas de todas las
fuentes (0 diferencias, diferencia máxima 0 s), por lo que la clave de
deduplicación `(deviceid, fixtime)` coincide con `(deviceid, devicetime)`.

| # | Fuente | Filas | Rango fixtime | Dispositivos | Uso / decisión |
|---|---|---:|---|---:|---|
| 1 | `traccar.tc_positions` (viva) | 26.683 → 26.800* | 2026-09-22 02:58:01 → hoy | 7 | Prioridad 1. Solo SELECT. |
| 2 | `traccar.tc_positions_bak_20260903` | 19.602 | 2026-08-17 02:45:22.166 → 2026-09-03 21:26:07.122 | 1 (dev 2) | Prioridad 2. Es la única fuente del tramo 08-17→09-03. |
| 3 | `traccar_qa.tc_positions` | 37.936 | 2026-08-17 02:45:22.166 → 2026-09-12 04:10:20.506 | 6 | Prioridad 3. Puente 09-03→09-12. |
| 4 | dump `traccar-pre-purga-recorridos` (09-22 02:57) | 15.898 | 2026-09-11 11:21:17.813 → 2026-09-22 02:57:28 | 11 | Prioridad 9 entre dumps. **Fuente clave de 09-12→09-22**. |
| 5 | dump `traccar-20260922-003135` | 15.665 | 2026-09-11 11:21:17.813 → 2026-09-21 23:29:09.144 | 11 | Redundante con el pre-purga (5,5 h antes). |
| 6 | dump `traccar-20260919-125422` | 11.759 | 2026-09-11 11:21:17.813 → 2026-09-19 12:53:13.795 | 11 | Redundante. |
| 7 | dump `traccar-20260925-030001` | 20.149 | 2026-09-22 02:58:01 → 2026-09-25 02:59:41 | 7 | Aporta 2 filas mock eliminadas de producción. |
| 8 | dump `traccar-20260924-030001` | 13.122 | 2026-09-22 02:58:01 → 2026-09-24 02:59:16 | 6 | Redundante con producción (aporta 3 mock, colapsadas). |
| 9 | dump `traccar-20260923-030001` | 4.189 | 2026-09-22 02:58:01 → 2026-09-23 02:59:42 | 4 | Redundante con producción. |
| 10 | dump `traccar-20260922-030001` | 4 | 2026-09-22 02:58:01 → 2026-09-22 02:59:40 | 1 | Redundante con producción. |
| 11 | dump `traccar-pre-cambio-usuarios` (09-22 14:55) | 1.522 | 2026-09-21 23:10:13.548 → 2026-09-22 14:42:06.901 | 5 | Aporta 414 claves que producción ya no tenía (ventana post-purga previa a la copia viva). |
| 12 | dump `traccar-20260920-030001` | 0 | — | — | **0 bytes**: `pg_restore: error: input file is too short (read 0, expected 5)`. |
| 13 | dump `traccar-20260921-030001` | 0 | — | — | **0 bytes**: mismo error. |

\* El conteo de producción crece de forma continua por operación normal
(26.683 al inicio de la sesión, 26.800 al cierre). La migración no escribe.

Notas del inventario:

- Los 8 dumps no vacíos contienen además una copia plana de
  `tc_positions_bak_20260903` (19.602 filas, 08-17→09-03 en todos). Se
  verificó que la copia restaurada del dump pre-purga es idéntica a la tabla
  viva de producción: `count=19602`, `sum(id)=193467281`,
  `sum(hashtext(...))=280626975224` en ambas.
- La hypertable de producción arranca el **2026-09-11 11:21:17.813** (chunks
  `_hyper_1_*`). El tramo 08-17→09-11 existe únicamente en
  `tc_positions_bak_20260903` (hasta 09-03) y `traccar_qa` (hasta 09-12).
- La purga de recorridos ocurrió el 2026-09-22 ~02:57 (hora local): el dump
  pre-purga conserva los chunks anteriores; producción quedó desde
  09-22 02:58:01.
- Verificación de TOC de cada dump y logs de restauración en
  `evidence/toc/` y `evidence/restore/`. Listado y sha256 en
  `evidence/01-inventario-backups.txt`.

## 2. Deduplicación y conjunto unificado

Clave: `(deviceid, fixtime)`. Prioridad acordada:
**producción > `tc_positions_bak_20260903` > `traccar_qa` > dumps**; entre
dumps gana el más reciente. Desempate dentro de una misma fuente:
`servertime` más reciente y luego `id` más alto.

Duplicados internos (filas menos claves):

| Fuente | Filas | Claves | Duplicados | % |
|---|---:|---:|---:|---:|
| `traccar_qa.tc_positions` | 37.936 | 37.927 | 9 | 0,024 |
| `traccar.tc_positions` | 26.702 | 26.405 | 297 | 1,112 |
| dump 20260925 | 20.149 | 19.991 | 158 | 0,784 |
| `tc_positions_bak_20260903` | 19.602 | 19.601 | 1 | 0,005 |
| dump pre-purga | 15.898 | 15.898 | 0 | 0 |
| dump 20260922-003135 | 15.665 | 15.665 | 0 | 0 |
| dump 20260924 | 13.122 | 13.055 | 67 | 0,511 |
| dump 20260919 | 11.759 | 11.759 | 0 | 0 |
| dump 20260923 | 4.189 | 4.176 | 13 | 0,310 |
| dump pre-cambio-usuarios | 1.522 | 1.519 | 3 | 0,197 |
| dump 20260922-030001 | 4 | 4 | 0 | 0 |

Solapamiento entre fuentes (claves compartidas):

| Par | Claves en común |
|---|---:|
| `tc_positions_bak_20260903` ↔ `traccar_qa` | 19.602 |
| `traccar_qa` ↔ dump pre-purga | 1 (empalme exacto dev 43: 09-11 11:21:17.813) |
| producción ↔ dump pre-purga | 0 (producción arranca 33 s después) |
| producción ↔ dump 20260925 | 20.144 |

Aporte por fuente al conjunto final (snapshot 21:58:42 UTC):

| Fuente ganadora | Filas | Dispositivos | Rango |
|---|---:|---:|---|
| `traccar.tc_positions` | 26.405 | 7 | 09-22 02:58 → 09-25 21:58 |
| `tc_positions_bak_20260903` | 19.601 | 1 | 08-17 02:45 → 09-03 21:26 |
| `traccar_qa.tc_positions` | 18.326 | 6 | 09-03 21:26 → 09-12 04:10 |
| dump pre-cambio-usuarios | 414 | 3 | 09-21 23:10 → 09-22 14:42 |
| dump 20260925 (mock) | 2 | 1 | 09-23 21:30 → 09-24 11:03 |
| dump pre-purga | 15.897 | 11 | 09-11 11:24 → 09-22 02:57 |
| **Total unificado (corte 21:58:42)** | **80.645** | **17** | **08-17 02:45:22 → 09-25 21:58:42** |

Conteo final esperado (corte usado por el ETL): **80.728 filas, 17
dispositivos, 2026-08-17 02:45:22.166 → 2026-09-25 22:12:11 UTC**
(la diferencia con 80.645 es solo el crecimiento normal de producción entre
ambos cortes). Distribución: 2026-08 = 14.207; 2026-09 = 66.521.

Reproducible con el SQL de unión (en la scratch del ETL, o replicando la
unión documentada en `evidence/03-union-analisis.txt`):

```sql
SELECT count(*), count(DISTINCT deviceid), min(fixtime), max(fixtime)
FROM public.u_final
WHERE fixtime <= TIMESTAMP '2026-09-25 22:12:11';  -- 80728 | 17 | ... | ...
```

### Verificación de continuidad 08-17 → hoy

No se pierde ningún tramo: los empalmes entre fuentes son contiguos
(`bak`→`qa` 09-03 21:26; `qa`→`pre-purga` 09-11/09-12; `pre-purga`→
producción 09-22 02:57:28→02:58:01). La partición `2026_08` es necesaria
(14.207 filas).

## 3. Huecos > 24 h por dispositivo

Todos los huecos detectados son **inactividad real del dispositivo**: quedan
dentro de ventanas cubiertas por las fuentes (si hubiera datos, estarían en
ellas). No hay huecos atribuibles a la fragmentación.

| Dispositivo | Desde | Hasta | Hueco |
|---|---|---|---|
| 47 | 2026-09-16 20:32:36 | 2026-09-21 22:11:10 | 5 d 01:38 |
| 2 | 2026-08-20 15:43:44 | 2026-08-25 16:21:36 | 5 d 00:37 |
| 43 | 2026-09-16 04:53:58 | 2026-09-20 23:45:20 | 4 d 18:51 |
| 2 | 2026-08-26 13:12:26 | 2026-08-31 01:33:43 | 4 d 12:21 |
| 43 | 2026-09-11 11:47:07 | 2026-09-15 07:20:11 | 3 d 19:33 |
| 40 | 2026-09-14 08:10:29 | 2026-09-17 18:08:20 | 3 d 09:57 |
| 39 | 2026-09-14 23:27:44 | 2026-09-17 11:40:37 | 2 d 12:12 |
| 2 | 2026-09-03 22:15:57 | 2026-09-06 07:30:22 | 2 d 09:14 |
| 2 | 2026-08-31 01:34:14 | 2026-09-02 00:28:58 | 1 d 22:54 |
| 2 | 2026-09-16 19:42:55 | 2026-09-18 13:45:02 | 1 d 18:02 |
| 50 | 2026-09-20 19:34:57 | 2026-09-22 12:16:57 | 1 d 16:41 |
| 2 | 2026-09-20 21:45:08 | 2026-09-22 07:32:07 | 1 d 09:46 |
| 50 | 2026-09-22 12:16:57 | 2026-09-23 21:30:08 | 1 d 09:13 |
| 42 | 2026-09-16 11:37:16 | 2026-09-17 18:26:16 | 1 d 06:48 |
| 41 | 2026-09-15 14:37:09 | 2026-09-16 20:16:28 | 1 d 05:39 |
| 2 | 2026-09-02 10:05:33 | 2026-09-03 13:48:10 | 1 d 03:42 |
| 38 | 2026-09-10 22:14:09 | 2026-09-11 23:02:10 | 1 d 00:48 |
| 50 | 2026-09-24 11:03:38 | 2026-09-25 11:10:45 | 1 d 00:07 |

El detalle de huecos > 6 h (contexto de jornada) está en
`evidence/03-union-analisis.txt` (sección 9). Cobertura por dispositivo en la
sección 11 del mismo archivo.

## 4. Anomalías

| Anomalía | Filas | Decisión en la carga |
|---|---:|---|
| `fixtime` futuro (> now) | 0 | — |
| `fixtime` anterior a 2026-01-01 | 0 | — |
| `precision_m` > 500 | 2 (dev 52, hasta 602,5 m) | **Conservar**; `valida` según origen. No se degrada por precisión. |
| `valid = false` | 293 | Conservar con `valida=false` (nunca borrar en origen). |
| Mock "macias" | 8 en `_bak_macias_mock_positions_20260925`; 5 presentes en dumps; 2 sobreviven la deduplicación | Cargar con `valida=false` y `atributos.migracion.mock=true`; no se borra nada en origen. |

Detalle de mock: las 8 filas son de `deviceid=50` con coordenadas de Google
HQ (37.4220009, -122.0840607). Tres (09-23 21:30:08) están en el dump
09-24; dos más (09-24 11:03:38) en el dump 09-25; tras deduplicar por
`(deviceid, fixtime)` sobreviven 2 filas (ids 73893 y 77605). Las tres
restantes (ids 84216/84217/84219, fixtime 09-25 08:58:59) **solo existen en
la tabla de respaldo mock** (se insertaron después del dump 09-25 03:00 y se
borraron de producción), por lo que no entran al unificado; si se quisieran
conservar habría que cargarlas aparte desde esa tabla.

## 5. Mapeo aplicado y discrepancia con el enunciado

El esquema real de FASE 3 parte 1 (`03_tracking.sql`) **no tiene columna
`id_legado`** en `tracking.dmt_posicion`: el id legado se conserva en `id`
(identidad `GENERATED BY DEFAULT`). Además, el mapeo documentado en
`MAPA-LEGADO-DMT.md` §5.3 es `devicetime → registrado_en` (particionado) y
`fixtime → fijado_en`, no `registrado_en = fixtime` como decía el enunciado.
Se siguió el esquema y el mapa del repositorio (fuente de verdad de FASE 3
parte 1). En los datos verificados `devicetime = fixtime` siempre, así que la
diferencia es semántica, no de contenido.

| Legado | Nuevo | Regla |
|---|---|---|
| `id` | `id` | Conserva id legado (rol de `id_legado`). |
| `deviceid` | `dispositivo_id` | `JOIN tracking.dmt_dispositivo d ON d.id_legado = deviceid`. |
| `protocol` | `protocolo` | — |
| `servertime` | `recibido_en` | `AT TIME ZONE 'UTC'`. |
| `devicetime` | `registrado_en` | `AT TIME ZONE 'UTC'`; columna de particionado. |
| `fixtime` | `fijado_en` | `AT TIME ZONE 'UTC'`. |
| `valid` | `valida` | `valid AND NOT es_mock AND fixtime ∈ [2026-01-01, now]`. |
| `latitude`/`longitude`/`altitude` | `latitud`/`longitud`/`altitud_m` | — |
| `speed` | `velocidad_kmh` | `speed * 1.852` (nudos → km/h). Verificado: 0,24487 kn → 0,4535 km/h. |
| `course` | `rumbo_grados` | — |
| `accuracy` | `precision_m` | — |
| `address`/`network`/`geofenceids` | `direccion`/`red`/`geocercas` | — |
| `attributes` | `atributos` | JSON texto → JSONB (`system.dmt_jsonb_seguro`); `batteryLevel` → `bateria_pct`. |

Idempotencia: índice único `uq_dmt_posicion_migracion
(dispositivo_id, registrado_en, id)` + `ON CONFLICT DO NOTHING`. En tabla
particionada el índice incluye la clave de partición (`registrado_en`).

## 6. ETL

- `scripts/migration/01_cargar_posiciones.sh`: reconstruye el conjunto
  unificado desde las fuentes originales (restaura dumps en scratch propia,
  copia producción/QA solo con SELECT/COPY TO, deduplica, exporta CSV) y lo
  carga en el staging de la base nueva. Parámetros por variables de entorno
  (sin secretos en el repo).
- `scripts/migration/01_cargar_posiciones.sql`: crea la partición `2026_08`,
  el staging si falta, el índice de idempotencia y carga por lotes
  (`dmt.batch_size`, por defecto 5000) registrando cada lote en
  `system.dmt_migracion_mapa`.

Resultado de la prueba real (scratch `dmt_new_test`, esquema nuevo completo):

| Verificación | Resultado |
|---|---|
| Filas cargadas | 80.728 (18 lotes) |
| Particiones | `2026_08`: 14.207 · `2026_09`: 66.521 |
| `valida=false` | 295 (293 origen + 2 mock) |
| `bateria_pct` no nulo | 80.421 |
| Velocidad máxima | 195,69 km/h (= 105,66 kn × 1,852) |
| `system.dmt_migracion_mapa` | 18 lotes únicos |
| Re-ejecución del cargador | 0 filas nuevas; conteo sin cambios |

Logs: `evidence/04-etl-run.txt`, `evidence/05-etl-idempotencia.txt`,
`evidence/06-verificacion-final.txt`, `evidence/07-csv-muestra.txt`.

## 7. Evidencia

| Archivo | Contenido |
|---|---|
| `evidence/01-inventario-backups.txt` | `ls -la`, sha256 de dumps, TOC y resumen por dump. |
| `evidence/02-inventario-fuentes.txt` | Filas/rango/dispositivos por fuente restaurada. |
| `evidence/03-union-analisis.txt` | Análisis completo: duplicados, solapamientos, meses, huecos, anomalías, cobertura. |
| `evidence/04-etl-run.txt` | Ejecución completa del ETL. |
| `evidence/05-etl-idempotencia.txt` | Segunda ejecución del cargador (0 filas). |
| `evidence/06-verificacion-final.txt` | Conteos y particiones tras la carga. |
| `evidence/07-csv-muestra.txt` | Cabecera, tamaño y sha256 del CSV unificado. |
| `evidence/08-conteos-finales.txt` | Conteos antes/después de producción y QA. |
| `evidence/toc/`, `evidence/restore/` | TOC de cada dump y logs de `pg_restore`. |

## 8. Limpieza

Se eliminaron las scratch `dmt_hist_test`, `dmt_etl_scratch` y
`dmt_new_test` (solo quedan `postgres`, `template0/1`, `traccar` y
`traccar_qa`). Conteos finales de control en
`evidence/08-conteos-finales.txt`: producción 26.683 → 26.800+ (el servicio
sigue escribiendo), `traccar_qa` 37.936 → 37.936, `tc_positions_bak_20260903`
19.602 → 19.602.

## 9. Dudas / decisiones a confirmar

1. **`id_legado` vs `id`**: el enunciado pedía columna `id_legado` y
   `registrado_en = fixtime`; el esquema de FASE 3 parte 1 no tiene
   `id_legado` y usa `devicetime → registrado_en`, `fixtime → fijado_en`.
   Se siguió el repositorio. Confirmar si se mantiene así.
2. **Dumps de 0 bytes (09-20 y 09-21)**: el respaldo diario falló esos dos
   días. El pre-purga cubre ese tramo; conviene arreglar el cron de backup.
3. **Mock**: ¿cargar las 2 filas sobrevivientes marcadas (implementado) o
   excluirlas del todo? ¿Recuperar las 3 que solo están en
   `_bak_macias_mock_positions_20260925`?
4. **`protocol`**: el mapa dice "osmand en el 100 % del histórico", pero hay
   filas `dmj-mqtt` (p. ej. primeras de agosto). No afecta la carga
   (`protocolo` es texto).
5. **Corte temporal**: producción sigue escribiendo; el conteo esperado debe
   citarse siempre con el corte (`fixtime <= '2026-09-25 22:12:11'`).
