# FASE 3 parte 3 — Carga de datos reales en la base nueva

- Fecha de trabajo: 2026-09-26 (UTC).
- Base nueva: `dmt-db` (PostgreSQL 18.6 + TimescaleDB 2.30.1, `127.0.0.1:5443`,
  base `dmujeres`, usuario `dmt`).
- Base legada: `dmj-db` (PostgreSQL 17.10 + TimescaleDB 2.29.1): `traccar` en
  vivo y `traccar_qa`. **Solo lectura**: no se ejecutó INSERT/UPDATE/DELETE ni
  DDL sobre `traccar` ni sobre `traccar_qa`.
- Esquema destino: `database/schema/*.sql` (27 tablas `dmt_*` + particiones).
- Escritura: únicamente en `dmujeres` (contenedor `dmt-db`).
- Secretos (hashes, salts, claves, tokens): copiados por tubería
  `docker exec` → `docker exec` y dentro de SQL; no se imprimieron ni quedaron
  en documentos. En la evidencia solo hay conteos.

## 1. Particiones

Se creó la partición faltante del histórico (el legado arranca el
2026-08-17):

```sql
CREATE TABLE IF NOT EXISTS tracking.dmt_posicion_2026_08
    PARTITION OF tracking.dmt_posicion
    FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');
```

`dmt_evento_2026_08` **no se creó**: los eventos del legado empiezan el
2026-09-15 21:46:40 (único mes con datos: 2026-09). El techo condicionado a
"si hay eventos de ese mes" no se cumple.

Evidencia: `evidence/fase3-01-particiones.txt`.

## 2. Usuarios, roles y credenciales (id preservado)

Se copiaron 5 usuarios de `traccar.tc_users` (ids 1, 2, 3, 8, 9) sembrando
`id = id_legado`, `nombre`/`correo`/`telefono`, `hash_clave` = `hashedpassword`,
`sal` = `salt` (sin regenerar ni pedir cambio de clave), `habilitado = NOT
disabled`, `administrador`, `solo_lectura`, `clave_totp`, `expira_en`,
`ultimo_acceso_en` no provisto por el legado, y preferencias de UI en
`atributos` (map, zoom, límites, etc.).

Roles (`iam.dmt_rol`): `administrador`, `operador`, `solo_lectura`.
Asignación (`iam.dmt_usuario_rol`):

| id | usuario | flags legado | rol nuevo |
|---:|---|---|---|
| 1 | admin | administrator=t | administrador |
| 2 | cctv | — | operador |
| 3 | test | readonly=t | solo_lectura |
| 8 | Manzaba | — | operador |
| 9 | Fernando | — | operador |

Mapa: 5 filas por usuario en `system.dmt_migracion_mapa`. Evidencia:
`evidence/fase3-02-carga-nucleo.txt` y `fase3-02b-carga-nucleo-idempotencia.txt`.

## 3. Dispositivos (19: 9 vivos + 10 históricos)

`traccar.tc_devices` tiene 9 filas, todas vivas (47, 50, 51, 52, 53, 54, 56,
58, 59): sus `id`, `id_legado`, `identificador` (= `uniqueid`), `nombre`,
`habilitado`, `estado`, `ultima_conexion_en`, `ultima_posicion_id`, `grupo_id`,
teléfono/modelo/contacto/categoría, `atributos` JSONB y `expira_en` quedaron
idénticos al legado (identificador es contrato con la App: verificado 9/9).

El histórico de posiciones, sin embargo, tiene **17 deviceid**; 8 solo existen
en `traccar_qa.tc_devices` (2, 37, 38, 39, 40, 41, 42, 43) y 2 solo en el dump
`traccar-pre-purga-recorridos` (48 = `qa-f1`, 49 = `qa-f2`). Sin esas filas el
ETL de posiciones aborta por FK (ocurrió en el primer intento; ver
`evidence/fase3-03a-posiciones-etl-intento1.txt`). Decisión aplicada:

- Se migran como **dispositivos históricos**: `habilitado = false`,
  `estado = 'historico'`, `identificador = 'historico-<fuente>-<id>-<uniqueid>'`
  (los `uniqueid` `joseph`, `kevin` y `david` están repetidos entre producción
  y QA; el índice `uq_dmt_dispositivo_identificador` lo impide). El uniqueid
  original queda en `atributos.migracion.identificador_legado`.
- Los 2 del dump se restauran en una scratch temporal del clúster legado
  (`dmt_devices_scratch`, eliminada) desde
  `traccar-pre-purga-recorridos.dump` (solo lectura).

Mapa: 9 filas `traccar.public.tc_devices`, 8 `traccar_qa.public.tc_devices`,
2 `dump.traccar-pre-purga-recorridos.tc_devices`. Evidencia:
`evidence/fase3-02b-carga-nucleo-idempotencia.txt`.

## 4. Asignaciones

`traccar.tc_user_device` → `operations.dmt_asignacion`: 20 pares
usuario↔dispositivo, `desde_en = now()` de la migración, `activa = true`,
`id_legado = NULL` (el legado no tiene id simple). Mapa por par
(`id_origen = '<userid>:<deviceid>'`). Idempotente por `NOT EXISTS
(usuario_id, dispositivo_id)`.

## 5. Eventos (id preservado)

`traccar.tc_events` → `tracking.dmt_evento`: 5.134 filas (todas en la
partición `dmt_evento_2026_09`), `id`, `tipo`, `dispositivo_id` (FK; ningún
evento con `deviceid` nulo), `posicion_id` (referencia informativa),
`geocerca_id`, `mantenimiento_id`, `ocurrido_en = eventtime AT TIME ZONE
'UTC'` y `atributos` JSONB. Los 7 dispositivos emisores están entre los 9
vivos. Conteo OLD con corte de id = NEW y mismo desglose por tipo (18 tipos;
evidencia §11/11b).

## 6. Jornadas derivadas

`operations.dmt_jornada`: 28 filas (20 cerradas, 8 abiertas), una por
`(dispositivo, journeyId)` de los eventos `mobileJourneyStarted`/
`mobileJourneyEnded` (`attributes.journeyId`), con `id_legado = journeyId`,
`inicio_en`/`fin_en` del par de eventos y `atributos` con los ids de evento.

- Los 8 `mobileJourneyStarted` sin `mobileJourneyEnded` quedan `abierta`.
- Los 3 `mobileJourneyEnded` sin `mobileJourneyStarted` **no son derivables**
  (no hay hora de inicio): se omiten y quedan reportados.
- `mobile.journeyId` actual de los dispositivos: los 3 activos (47, 50, 52)
  tienen su jornada `abierta`; no hay jornadas inventadas sin hora de inicio.

## 7. Batería / telemetría

`telemetry.dmt_bateria`: 26.888 muestras, sin duplicados por
`(dispositivo_id, registrado_en)`:

- 26.864 desde `attributes.batteryLevel` de `traccar.tc_positions` de los
  últimos 7 días (7 dispositivos; se guarda también `charge` como `cargando`
  y `posicion_id` en `atributos`). OLD con corte = NEW exacto.
- 24 desde `mobile.batteryHistory` de `traccar.tc_devices.attributes`
  (dispositivo 50, 2026-09-22 02:01→02:46; el dispositivo 47 tiene su
  historia fuera de la ventana de 7 días). La app sigue vigente, por eso se
  incluyó.

## 8. FCM y otras tablas legadas

| Origen | Destino | Decisión | Filas |
|---|---|---|---:|
| `tc_fcm_tokens` | `iam.dmt_token_fcm` | migrado (token, token previo, activo, inválido, fechas) | 9 |
| `tc_keystore` | `iam.dmt_clave_firma` | migrado (compatibilidad de JWT vigentes; secreto) | 1 |
| `tc_recovery_event` | `operations.dmt_alerta` (origen `recuperacion`) | migrado (estado OK→resuelta, FAILED→descartada, resto→nueva; estado legado en atributos) | 9.734 |
| `tc_revoked_tokens` | `iam.dmt_token_revocado` | **evaluar**: 0 filas; el legado solo guarda id, la tabla nueva exige `token_hash` | 0 |
| `tc_mobile_messages` | `operations.dmt_alerta` (origen `movil`) | **evaluar** (cola/lease efímero; no migrado) | 5.560 |
| `tc_statistics` | — | **no migrar** (métricas internas del motor) | 17 |
| `tc_actions` | `audit.dmt_auditoria` | **pendiente** fuera del alcance de esta parte (tabla equivalente existente) | 1.517 |
| `tc_device_health` | `telemetry.dmt_salud_dispositivo` | **pendiente** fuera del alcance de esta parte (tabla equivalente existente) | 1.002 |
| `tc_groups` | `tracking.dmt_grupo` (no existe en el esquema actual) | **evaluar** | 6 |

Cada decisión quedó registrada en `system.dmt_migracion_mapa`.

## 9. Posiciones

Se ejecutó el ETL `scripts/migration/01_cargar_posiciones.sh` contra
`dmujeres` (no contra scratch) desde `dmj-db`, con
`DMT_NEW_PSQL="psql -h dmt-db -p 5432 -U dmt -d dmujeres"` y la clave leída
de `/home/DMujeres-Tracking/.env` (nunca impresa). Se conectó temporalmente
`dmt-db` a la red `compose_default` para que `dmj-db` lo alcanzara (deshecho
al cierre; ver §12).

- 8 dumps restaurados en la scratch `dmt_etl_scratch` (2 dumps de 0 bytes
  omitidos, 09-20/09-21), unión con producción (SELECT/COPY TO) y QA,
  deduplicación por `(deviceid, fixtime)` y CSV.
- Carga final: **80.809 filas en 18 lotes** de 5.000, 17 dispositivos,
  2026-08-17 02:45:22.166 → 2026-09-25 22:33:51 UTC.
- Particiones: `2026_08` = 14.207, `2026_09` = 66.602.
- `system.dmt_migracion_mapa`: 18 filas `lote:*` para
  `traccar.public.tc_positions`.
- Idempotencia: re-ejecutar `01_cargar_posiciones.sql` con el mismo staging
  insertó **0 filas** (conteo 80.809 sin cambios).
- Nota: la producción sigue viva y escribió posiciones después del snapshot
  (26.876 al cierre, +32 desde el preflight). Una re-ejecución completa del
  `.sh` refresca el snapshot: inserta solo esas filas nuevas por operación
  normal, nunca duplica.

## 10. Tabla de conteos OLD vs NEW

Cortes: posiciones `fijado_en <= 2026-09-25 22:33:51+00`; eventos
`id <= 32231`; recuperación `id <= 27752`; batería
`registrado_en <= 2026-09-25 22:33:21+00`.

| Objeto | OLD (con corte) | NEW | Comentario |
|---|---:|---:|---|
| Usuarios | 5 | 5 | ids preservados |
| Usuarios habilitados | 5 | 5 | `NOT disabled` |
| Usuarios administradores | 1 | 1 | rol `administrador` |
| Roles | — | 3 | catálogo nuevo |
| Usuario-rol | — | 5 | 1 admin / 3 operador / 1 solo lectura |
| Dispositivos | 9 | **19** | 9 vivos + 10 históricos (8 QA + 2 dump) por la FK del histórico |
| Dispositivos vivos | 9 | 9 | identificador 9/9 idéntico |
| Asignaciones | 20 | 20 | — |
| Eventos | 5.134 | 5.134 | desglose por tipo idéntico |
| Jornadas | — | 28 | 20 cerradas + 8 abiertas; 3 eventos `Ended` sin `Started` no derivables |
| Batería | 26.864 | 26.888 | 26.864 posiciones + 24 `batteryHistory` |
| FCM | 9 | 9 | — |
| Claves de firma | 1 | 1 | — |
| Alertas de recuperación | 9.734 | 9.734 | — |
| Posiciones | 26.868 (vivo, ≤ corte) | **80.809** | NEW es el unificado (prod+bak+QA+dumps); no comparable 1:1 |
| Posiciones 2026-08 | — | 14.207 | partición `2026_08` |
| Posiciones 2026-09 | — | 66.602 | partición `2026_09` |
| Posiciones inválidas | 293 (valid=false) | 295 | +2 mock |
| Posiciones mock marcadas | 2 (en dumps) | 2 | `valida=false`, `atributos.migracion.mock=true` |

Diferencias de filas por dispositivo quedan explicadas: la producción tiene
duplicados internos de `(deviceid, fixtime)` (p. ej. dev 52: 5.596 crudas →
5.497 claves; dev 59: 1.349 → 1.246) que el unificado deduplica; y los
dispositivos 47/50 incluyen además historia de dumps/QA (evidencia §14).

## 11. Resultado de la validación

| Verificación | Resultado |
|---|---|
| `unique_id`/identificador duplicado | 0 |
| Claves `(dispositivo_id, registrado_en)` duplicadas | 0 (80.809 = 80.809 distintas) |
| Última posición por dispositivo (9 vivos) | 9/9 idénticas al legado con el mismo corte; 51 y 54 sin posiciones en ambos lados (NULL = NULL) |
| Rango del histórico | 2026-08-17 02:45:22 → 2026-09-25 22:33:51 UTC |
| Huérfanos (posiciones/eventos/jornadas/batería/FCM/asignaciones/alertas) | 0 en los 9 controles |
| `system.dmt_migracion_mapa` | 75 filas: 5 usuarios, 19 dispositivos, 20 asignaciones, 18 lotes de posiciones, 13 resúmenes |
| Secuencias `setval` | usuario 9, dispositivo 59, evento 32.231, posición 88.694 |
| Idempotencia ETL posiciones | 0 filas nuevas, conteo sin cambios |
| Idempotencia carga núcleo | 0 filas nuevas (solo +7 batería y +8 recuperación por crecimiento vivo en la 2.ª pasada) |

Evidencia: `evidence/fase3-05-validacion.txt`, `fase3-04-secuencias.txt`.

## 12. Cierre

- Base nueva consistente: 27 tablas `dmt_*` + particiones, sin tablas de
  staging (se eliminaron las 12 `system.dmt_staging_*` y las 2 de validación).
  Quedan 0 filas en `audit.dmt_auditoria`, `telemetry.dmt_salud_dispositivo`,
  `iam.dmt_token_revocado` (pendientes/evaluar, ver §8).
- Scratch eliminadas: `dmt_etl_scratch` (por el propio ETL),
  `dmt_devices_scratch`; el clúster legado quedó solo con `postgres`,
  `traccar` y `traccar_qa`.
- Temporales `docker cp` en `dmj-db` (`/tmp/dmt_dumps`, `/tmp/dmt_etl_src`)
  eliminados.
- Red temporal `compose_default` desconectada de `dmt-db` (vuelve a
  `dmt_default`).
- Producción sin cambios por la migración: `tc_users` 5 y `tc_devices` 9
  intactos; `tc_positions` 26.844 (preflight) → 26.876 (cierre), solo por
  operación normal. `dmj-db` y `dmt-db` siguen `healthy`.
- Evidencia: `evidence/fase3-06-cierre.txt`.

### Re-ejecución idempotente

```sh
# 0) Red temporal para que dmj-db alcance dmt-db
docker network connect compose_default dmt-db
export PGPASSWORD="$(sed -n 's/^POSTGRES_PASSWORD=//p' /home/DMujeres-Tracking/.env)"

# 1) Carga núcleo (usuarios, dispositivos + histórico QA/dump, asignaciones,
#    eventos, jornadas, batería, FCM, claves, recuperación y mapa)
/home/DMujeres-Tracking/scripts/migration/02_cargar_nucleo.sh

# 2) Posiciones (reconstruye el unificado y carga por lotes; re-ejecutar
#    solo inserta lo nuevo de producción)
docker exec dmj-db sh -lc 'mkdir -p /tmp/dmt_dumps /tmp/dmt_etl_src'
docker cp /var/backups/dmj/. dmj-db:/tmp/dmt_dumps/
docker cp /home/DMujeres-Tracking/scripts/migration/01_cargar_posiciones.sh \
          /home/DMujeres-Tracking/scripts/migration/01_cargar_posiciones.sql \
          dmj-db:/tmp/dmt_etl_src/
docker exec -e PGUSER=traccar -e PGPASSWORD \
  -e DMT_NEW_PSQL="psql -h dmt-db -p 5432 -U dmt -d dmujeres" \
  -e DMT_DUMPS_DIR=/tmp/dmt_dumps -e DMT_WORK_DIR=/tmp/dmt_dumps/etl \
  dmj-db bash /tmp/dmt_etl_src/01_cargar_posiciones.sh

# 3) Secuencias (setval de ids legados)
docker exec -i dmt-db psql -U dmt -d dmujeres -f - \
  < /home/DMujeres-Tracking/scripts/migration/03_ajustar_secuencias.sql

# 4) Limpieza de red
docker network disconnect compose_default dmt-db
```

Idempotencia: los `INSERT` usan `ON CONFLICT`/`NOT EXISTS` por claves legadas
y el cargador de posiciones tiene índice único
`uq_dmt_posicion_migracion` + `ON CONFLICT DO NOTHING`.

## 13. Desajustes encontrados

1. **El conjunto unificado no tiene 9 dispositivos sino 17**: 8 solo en QA y 2
   (48, 49) solo en el dump pre-purga. El enunciado de esta parte asumía
   `tc_devices` (9). Se resolvió migrando 10 dispositivos históricos
   deshabilitados, con identificador desambiguado. Sin esto el ETL aborta.
2. **`uniqueid` repetidos entre producción y QA** (`joseph`, `kevin`,
   `david`): impide copiar QA tal cual por el índice único; de ahí el prefijo
   `historico-`.
3. **Eventos de agosto**: no existen (mínimo 2026-09-15); la partición
   `dmt_evento_2026_08` no aplica.
4. **Jornadas**: 3 `mobileJourneyEnded` sin `mobileJourneyStarted` no son
   derivables sin hora de inicio; se omitieron (no se inventaron datos).
5. **`id_legado` en jornadas**: no hay id simple de jornada en el legado; se
   usó `journeyId` (existe la columna `id_legado`).
6. **Duplicados internos de producción** (297+ claves repetidas en la ventana
   analizada): el unificado deduplica por diseño; explica que el conteo por
   dispositivo sea menor que el crudo.
7. **`tc_actions` (1.517) y `tc_device_health` (1.002)** tienen tabla
   equivalente y el MAPA dice `migrar`, pero no estaban en el alcance de esta
   parte: quedan como pendientes registrados en el mapa.
8. **`tc_revoked_tokens`**: 0 filas y el legado solo conserva `id`; la tabla
   nueva exige `token_hash`, así que no hay nada copiable.
9. **`tc_mobile_messages` (5.560)** y **`tc_groups` (6)**: quedan en
   `evaluar`; no se migraron.
10. **Scratch en el clúster legado**: la ETL crea y elimina
    `dmt_etl_scratch` (y la carga núcleo `dmt_devices_scratch`) para restaurar
    dumps. No se tocó `traccar` ni `traccar_qa`; el clúster quedó limpio.
11. **Red temporal**: `dmj-db` no puede alcanzar `dmt-db` de otra forma
    (puertos publicados solo en 127.0.0.1); se conectó `dmt-db` a
    `compose_default` y se desconectó al cierre.

## 14. Dudas / decisiones a confirmar

1. ¿Se aceptan los 10 dispositivos históricos con `identificador` prefijado
   (`historico-...`) y `habilitado=false`, o se prefiere remapear las
   posiciones de los ids de QA a los dispositivos vivos con el mismo
   `uniqueid` (joseph/kevin/david)?
2. ¿Se migran `tc_actions` → `audit.dmt_auditoria` y `tc_device_health` →
   `telemetry.dmt_salud_dispositivo` en la siguiente parte?
3. `tc_mobile_messages`: ¿se migra el histórico de acks a
   `operations.dmt_alerta` (origen `movil`) o se descarta la cola?
4. `tc_groups`: ¿se crea `tracking.dmt_grupo` o se descarta la agrupación?
5. Mock: las 2 filas de `deviceid=50` se cargaron marcadas e inválidas; las 3
   que solo viven en `_bak_macias_mock_positions_20260925` quedaron fuera.
   ¿Recuperarlas?
6. El corte de la validación es un snapshot (producción sigue viva): antes
   del cutover conviene re-ejecutar el ETL como *refresh* y volver a correr
   las validaciones.
7. Zona horaria de los cortes mensuales: se mantuvo UTC (coherente con el
   legado); confirmar si la operación espera `America/Bogota`.

## 15. Evidencia

| Archivo | Contenido |
|---|---|
| `evidence/fase3-00-preflight.txt` | `docker ps`, versiones, conteos OLD/NEW previos. |
| `evidence/fase3-01-particiones.txt` | Meses de eventos + DDL y particiones resultantes. |
| `evidence/fase3-02-carga-nucleo.txt` | Primera carga núcleo (17 dispositivos). |
| `evidence/fase3-02b-carga-nucleo-idempotencia.txt` | Re-ejecución con dispositivos solo-dump y prueba de idempotencia. |
| `evidence/fase3-03a-posiciones-etl-intento1.txt` | Intento fallido por devices 48/49 (FK). |
| `evidence/fase3-03b-posiciones-etl.txt` | Carga final de posiciones (80.809, 18 lotes). |
| `evidence/fase3-03c-posiciones-etl-idempotencia.txt` | Re-ejecución del cargador: 0 filas. |
| `evidence/fase3-04-secuencias.txt` | `setval` de ids legados. |
| `evidence/fase3-05-validacion.txt` | Validación OLD vs NEW completa. |
| `evidence/fase3-06-cierre.txt` | Limpieza, conteos finales, producción y `docker ps`. |

Scripts nuevos: `scripts/migration/02_staging_nucleo.sql`,
`scripts/migration/02_cargar_nucleo.sh`, `scripts/migration/02_cargar_nucleo.sql`,
`scripts/migration/03_ajustar_secuencias.sql`.
