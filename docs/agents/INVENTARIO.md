# DMujeres Tracking - FASE 0: Inventario del sistema actual

Auditoria de solo lectura ejecutada el 2026-09-25 (hora local -05; algunas
consultas SQL registran fecha UTC). No se modifico la base de datos, ni los
servicios, ni los contenedores. No se copiaron valores de secretos: cuando un
archivo aloja secretos se indica solo su ruta y el tipo de secreto.

Host auditado: `cydigital`
- SO: `Linux cydigital 5.15.0-191-generic #201-Ubuntu SMP x86_64`
- CPU: 4 vCPU. RAM: 9.7 GiB (3.4 GiB usados, 5.8 GiB disponibles).
- Java de los servicios: OpenJDK 21.0.12.1.
- Red: IP publica `68.168.20.219/25` (eth0), Tailscale `100.83.91.53/32`.
- Firewall: `ufw` activo; solo 22/tcp, 4096/tcp, 8000/tcp, 8082/tcp, 1883/tcp y
  999/tcp permitidos en la tabla de `ufw` (no aparece 5055; ver seccion 4).

Comandos base usados:

```bash
uname -a; nproc; free -h; ip -4 addr show
sudo -n ufw status numbered
java -version
```

## 1. Estado actual: instalaciones y Git

| Elemento | Valor verificado |
|---|---|
| Instalacion actual | `/DMujeres-Tracking` (repo git, raiz de trabajo del monorepo) |
| Rama / HEAD | `main`, commit `af926a45486a7fa9083673c7acaad574c9941868` (2026-09-25) |
| Remote | `https://github.com/sekaishopml/Dmujeres-Traccar.git` |
| Arbol de trabajo | 29 entradas modificadas y 1 archivo sin seguimiento (`fallback/app/src/test/java/org/traccar/client/OtaDecisionTest.kt`); los dos submodulos aparecen `-dirty` |
| Submodulo `server` | `426679fc7e30fdf389718b3f10a765a1c2940e98`, rama `dev`, remote `.../Dmujeres-Traccar-server.git`; contenido local modificado |
| Submodulo `dashboard` | `44d8effc8daec8000def72c9548faa5d582a5f39`, rama `dev`, remote `.../Dmujeres-Traccar-dashboard.git`; contenido local modificado |
| Submodulo anidado `server/traccar-web` | No inicializado (`-695a4730...` en `git submodule status --recursive`) |
| Raiz nueva | `/home/DMujeres-Tracking` (644 KB, 2 archivos, NO es repo git) |
| Relacion entre ambas | `/home/DMujeres-Tracking` es el andamiaje de la plataforma objetivo (estructura del Plan Maestro: `apps/`, `services/`, `packages/`, `database/`, `infrastructure/`, `backups/`, `docs/`, `tests/`, `scripts/`). Solo contiene `docs/Plan-Maestro.pdf` y `docs/agents/ORQUESTADOR.md`. `/DMujeres-Tracking` sigue siendo produccion y fuente de datos; no debe tocarse hasta el cutover. |

Notas:
- `fallback/` y `mobile/` NO son submodulos: son carpetas versionadas en el
  repo raiz (mismo HEAD que la raiz).
- El plan maestro asigna a esta raiz la estructura de destino; hoy la unica
  documentacion de coordinacion es `docs/agents/ORQUESTADOR.md`.

Comandos:

```bash
git -C /DMujeres-Tracking submodule status --recursive
git -C /DMujeres-Tracking status --porcelain
git -C /DMujeres-Tracking branch --show-current
git -C /DMujeres-Tracking diff --submodule=short -- dashboard server
find /home/DMujeres-Tracking -maxdepth 3
git -C /home/DMujeres-Tracking rev-parse --show-toplevel   # fatal: no es repo git
```

## 2. Servicios systemd

Unidades `dmj-*` existentes (no hay otras): `dmj-traccar.service`,
`dmj-match.service`, `dmj-traccar-watchdog.service` y
`dmj-traccar-watchdog.timer`.

| Unidad | Estado | ExecStart | Usuario | EnvironmentFiles | Drop-ins |
|---|---|---|---|---|---|
| `dmj-traccar.service` | `active (running)` desde 2026-09-25 16:39:35 -05 | `/DMujeres-Tracking/infrastructure/scripts/traccar-systemd.sh` | `opencode:opencode` | Ninguno | `/etc/systemd/system/dmj-traccar.service.d/10-net-bind.conf` |
| `dmj-match.service` | `active (running)` desde 2026-09-19 16:55:20 -05 | `/usr/bin/java -Xmx2g -jar /opt/matchservice/target/matchservice.jar` | `opencode` | Ninguno | Ninguno |
| `dmj-traccar-watchdog.service` | `inactive (dead)` (oneshot) | `/DMujeres-Tracking/infrastructure/scripts/traccar-watchdog.sh` | sin `User=` (root por defecto) | Ninguno | Ninguno |
| `dmj-traccar-watchdog.timer` | `active (waiting)`, cada 5 min | activa la unidad anterior | - | - | Ninguno |

Detalles de `dmj-traccar.service`:
- `ExecStartPre=/DMujeres-Tracking/infrastructure/scripts/dev.sh up` (levanta
  los contenedores con Docker Compose antes de arrancar el servidor).
- `WorkingDirectory=/DMujeres-Tracking/server`.
- `Requires=docker.service`, `Wants=network-online.target`,
  `WantedBy=multi-user.target`, `Restart=always`, `RestartSec=10`.
- `StandardOutput/Error=journal`.
- El proceso final es
  `java -jar /DMujeres-Tracking/server/target/tracker-server.jar /DMujeres-Tracking/server/conf/traccar-dev.xml`
  (el script hace `exec java`); PID actual 4175434, memoria 572 MB.
- Drop-in `10-net-bind.conf`: `AmbientCapabilities=CAP_NET_BIND_SERVICE` y
  `CapabilityBoundingSet=CAP_NET_BIND_SERVICE` para poder enlazar el puerto
  privilegiado 999 sin root (se perdio el `setcap` del binario java tras la
  actualizacion de OpenJDK del 2026-09-23).
- No usa `EnvironmentFile`: el script `traccar-systemd.sh` carga
  `/DMujeres-Tracking/.env` y exporta el mapeo a variables del servidor. Ese
  script contiene valores por defecto de desarrollo para
  `MOBILE_HTTP_API_KEY` y `MOBILE_MQTT_PASSWORD` (no se copian).
- `/DMujeres-Tracking/server/conf/traccar-dev.xml` tiene permisos `600` y
  contiene la configuracion con `database.user`; la contrasena no esta en el
  XML, llega por variable de entorno (`config.useEnvironmentVariables=true`).

Detalles de `dmj-match.service`:
- Escucha en `127.0.0.1:8991` (log: `[matchservice] escuchando en
  127.0.0.1:8991`); memoria 235 MB, 6 dias activo.
- El jar vive FUERA del arbol del proyecto: `/opt/matchservice` (26 MB,
  incluye `car.json` de GraphHopper). El servidor lo consume por HTTP en
  `http://127.0.0.1:8991` (`MatchResource.java`).

`dmj-traccar-watchdog`:
- Hace `curl` a `http://127.0.0.1:5055/`; si no responde reinicia
  `dmj-traccar.service`. Escribe en journald y en
  `/var/log/dmj/watchdog.log`.
- Ultimas entradas del log: `watchdog: ok (HTTP 400)` cada ~5 min (el 400 es
  la respuesta normal del servidor a un GET sin parametros; el watchdog solo
  distingue 000 = muerto).

Comandos:

```bash
systemctl list-units 'dmj-*' --all --no-pager
systemctl list-unit-files 'dmj-*' --no-pager
systemctl show dmj-traccar.service -p ExecStart -p User -p EnvironmentFiles -p DropInPaths ...
cat /etc/systemd/system/dmj-traccar.service /etc/systemd/system/dmj-traccar.service.d/10-net-bind.conf
cat /etc/systemd/system/dmj-match.service /etc/systemd/system/dmj-traccar-watchdog.service /etc/systemd/system/dmj-traccar-watchdog.timer
systemctl status dmj-traccar.service dmj-match.service dmj-traccar-watchdog.timer --no-pager
tail -8 /var/log/dmj/watchdog.log
```

## 3. Contenedores Docker

Contenedores del producto (gestionados por el compose
`/DMujeres-Tracking/infrastructure/compose/docker-compose.yml`, red
`compose_default`):

| Contenedor | Imagen | Estado | Puertos publicados | Volumenes |
|---|---|---|---|---|
| `dmj-db` | `timescale/timescaledb:latest-pg17` | Up 6 dias (healthy) | `127.0.0.1:5433 -> 5432/tcp` | volumen `compose_dmj-pgdata` -> `/var/lib/postgresql/data` |
| `dmj-redis` | `redis:7-alpine` | Up 6 dias (healthy) | `127.0.0.1:6379 -> 6379/tcp` | volumen `compose_dmj-redisdata` -> `/data`; arranca con `--appendonly yes` |
| `dmj-mqtt` | `emqx/emqx:5.8.5` | Up 6 dias (healthy) | `0.0.0.0:1883 -> 1883/tcp` (MQTT), `127.0.0.1:8083 -> 8083/tcp` (WS), `127.0.0.1:18083 -> 18083/tcp` (dashboard). 8883/8084/4370/5369 NO publicados | volumen `compose_dmj-mqttdata` -> `/opt/emqx/data`; volumen de logs; bind de solo lectura `/DMujeres-Tracking/infrastructure/emqx/acl-file.conf` y `auth-file.csv` |

- Politica de reinicio de los tres: `unless-stopped`.
- `dmj-db` usa las variables `POSTGRES_USER`, `POSTGRES_PASSWORD`,
  `POSTGRES_DB` del `.env` (valores no copiados).
- `dmj-mqtt` tiene autenticacion por archivo (`auth-file.csv`, con hashes de
  usuarios MQTT) y ACL (`acl-file.conf`); la consola EMQX usa
  `EMQX_DASHBOARD_PASSWORD` (variable en `.env`, valor no copiado).
- `cyhotel-*` (`cyhotel-master`, `cyhotel-kiosco`, `cyhotel-admin`,
  `cyhotel-db`, imagenes `cyhotel-*` y `postgres:16-alpine`) son de OTRO
  producto (CyHotel), no pertenecen a DMujeres Tracking y NO se tocan en esta
  migracion. Publican 8000/8001/8002.

Comandos:

```bash
docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
docker inspect dmj-db dmj-redis dmj-mqtt   # parseado con python3 -c/json (sin jq)
```

## 4. Puertos y procesos

Quien escucha cada puerto verificado con `ss -lntp` (via `sudo -n`):

| Puerto | Servicio | Proceso / bind | Notas |
|---|---|---|---|
| 999/tcp | Web/API actual (Traccar) | java PID 4175434 (`dmj-traccar`), `*:999` | Sirve dashboard, API REST y canal movil HTTP. Puerto privilegiado por drop-in de capacidades |
| 5055/tcp | Protocolo OsmAnd (App) | java PID 4175434, `*:5055` | HTTP del decoder OsmAnd |
| 8991/tcp | Map-matching GraphHopper | java PID 756 (`dmj-match`), `127.0.0.1:8991` | Solo loopback |
| 5433/tcp | PostgreSQL/TimescaleDB | `docker-proxy` -> `dmj-db`, `127.0.0.1:5433` | Solo loopback |
| 1883/tcp | MQTT EMQX | `docker-proxy` -> `dmj-mqtt`, `0.0.0.0:1883` y `[::]:1883` | Expuesto en todas las interfaces |
| 8083/tcp | EMQX WebSocket MQTT | `docker-proxy` -> `dmj-mqtt`, `127.0.0.1:8083` | Solo loopback |
| 18083/tcp | Consola EMQX | `docker-proxy` -> `dmj-mqtt`, `127.0.0.1:18083` | Solo loopback |
| 6379/tcp | Redis | `docker-proxy` -> `dmj-redis`, `127.0.0.1:6379` | Solo loopback |
| 8000/8001/8002 | CyHotel | `docker-proxy` | Otro producto |

Nginx: NO instalado (`nginx: command not found`, no existe `/etc/nginx`);
Caddy y Apache2 inactivos. No hay listener en 80/443/8080/8082/8443.

Firewall: `ufw` activo con 22, 4096, 8000, 8082, 1883, 999 permitidos. El
puerto 5055 no figura en `ufw status numbered`; desde el propio host
`curl http://68.168.20.219:5055/` respondio HTTP 400 (vivo), por lo que la
regla efectiva pudo venir de otra cadena (Tailscale/iptables crudo). La
accesibilidad real desde Internet de 5055 queda como **no verificada desde
fuera del host**.

Comandos:

```bash
sudo -n ss -lntp
sudo -n ufw status numbered
sudo -n iptables -S INPUT DOCKER-USER
ss -lnt | grep -E ':(80|443|8082|8443|8080)\s'   # sin resultados
timeout 8 curl -s -o /dev/null -w "%{http_code}" -m 5 http://68.168.20.219:5055/
timeout 8 curl -s -o /dev/null -w "%{http_code}" -m 5 http://68.168.20.219:999/
```

## 5. Base de datos

Motor: `PostgreSQL 17.10 on x86_64-pc-linux-musl` (contenedor
`timescale/timescaledb:latest-pg17`). La sesion SQL reporta `TimeZone=UTC`,
pero las marcas de tiempo que escribe Traccar (`servertime`, `fixtime`,
`lastupdate`) quedan en **hora local del host** (America/Guayaquil -05,
equivalente a America/Bogota): evidencia, la ultima posicion de Fernando tiene
`servertime=2026-09-25 21:08:25` justo cuando el reloj local marcaba 21:08
(con `now()` UTC = 2026-09-26 02:08). No usar
`timezone('America/Bogota', servertime)` porque produce una doble conversion.

| Dato | Valor verificado |
|---|---|
| Extension | `timescaledb 2.29.1` (unicamente `plpgsql` y `timescaledb`; no hay PostGIS ni `pg_stat_statements`) |
| Base de datos real | `traccar` (usuario `traccar`, propietario de las 57 tablas del schema `public`) |
| Tamano | `pg_database_size('traccar')` = **61 MB** |
| Otras bases en el mismo contenedor | `traccar_qa` (47 MB; copia de QA), `postgres`, `template0`, `template1` |
| Hypertables | `tc_positions` (2 dimensiones, 9 chunks, dimension principal `fixtime`, compresion desactivada), `tc_events` (29 chunks), `tc_actions` (7 chunks) |
| Configuracion PG | `shared_buffers=2485MB`, `work_mem=19882kB`, `effective_cache_size=7455MB`, `max_connections=100` |

Conteos por tabla principal (solo SELECT):

| Tabla | Filas |
|---|---|
| `tc_users` | 5 |
| `tc_devices` | 9 |
| `tc_positions` | 26 522 (instantanea; tabla viva, el numero sube durante la auditoria) |
| `tc_positions_bak_20260903` | 19 602 |
| `tc_events` | 5 077 |
| `tc_actions` | 1 517 |
| `tc_user_device` | 20 |
| `tc_groups` | 6 |
| `tc_geofences` | 0 |
| `tc_notifications` | 0 |
| `tc_fcm_tokens` | 9 |
| `tc_mobile_messages` | 5 560 |
| `tc_recovery_event` | 9 631 |
| `tc_device_health` | 1 002 |

Historico de posiciones (`tc_positions`, tiempo `servertime` en hora local -05):
- Minimo: `2026-09-22 02:58:04.276`
- Maximo: `2026-09-25 22:03:27.289673`. Este maximo es **anomalo**: son 5 filas
  de `macias` con `servertime` identico `22:03:27.289673` (importacion en lote)
  y `fixtime` de las 11:22; el flujo vivo mas reciente al momento de la
  auditoria era `2026-09-25 21:08:25`. Revisar esas filas antes de migrar
  porque distorsionan rangos y reportes.
- Posiciones por dia (hora local -05), ultimos 7 dias (solo hay datos de 4):

| Dia | Posiciones |
|---|---|
| 2026-09-22 | 3 765 |
| 2026-09-23 | 8 900 |
| 2026-09-24 | 7 120 |
| 2026-09-25 | 6 737 |

Datos historicos fuera de `tc_positions` (hallazgo critico para la migracion):
- `tc_positions_bak_20260903`: **19 602 filas** con rango
  `2026-08-17 02:45:23.539` a `2026-09-03 21:26:06.874` (con dias sin datos).
- Base `traccar_qa`: `tc_positions` con **37 936 filas** de
  `2026-08-17 02:45:23.539` a `2026-09-12 04:10:51.833`.
- Existe `_bak_macias_mock_positions_20260925` (8 filas mock de `macias`,
  2026-09-23 a 2026-09-25).
- El rango `tc_positions_bak_20260903` + `traccar_qa` cubre hasta 09-12; la
  produccion arranca en 09-22. Entre 09-12 y 09-22 no se encontro posicion en
  ninguna tabla consultada. Existe el dump
  `/var/backups/dmj/traccar-pre-purga-recorridos.dump` (2026-09-22 02:57),
  inmediatamente anterior al minimo de produccion; **no verificado** aun si
  ese dump contiene la historia 09-12 a 09-22 (no se restauro en esta fase).

Comandos:

```bash
docker exec dmj-db printenv POSTGRES_USER POSTGRES_DB
docker exec dmj-db psql -U traccar -d traccar -Atc "select version();"
docker exec -i dmj-db psql -U traccar -d traccar -c "select extname, extversion from pg_extension;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select * from timescaledb_information.hypertables;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select relname, n_live_tup from pg_stat_user_tables order by n_live_tup desc;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select min(servertime), max(servertime) from tc_positions;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select servertime::date, count(*) from tc_positions group by 1 order by 1;"
docker exec -i dmj-db psql -U traccar -d traccar_qa -c "select count(*), min(servertime), max(servertime) from tc_positions;"
```

(La variable `POSTGRES_PASSWORD` no se consulto ni se copio.)

## 6. Usuarios

`tc_users` (sin hashes ni salts):

| id | name | email | administrator | readonly | disabled |
|---|---|---|---|---|---|
| 1 | admin | admin@dmj.local | t | f | f |
| 2 | cctv | cctv | f | f | f |
| 3 | test | test@dmujeres.local | f | t | f |
| 8 | Manzaba | manzaba@dmujeres.local | f | f | f |
| 9 | Fernando | fernando@dmujeres.local | f | f | f |

Equipos por usuario (`tc_user_device`, 20 asignaciones):

| userid | equipos |
|---|---|
| 1 (admin) | 9 |
| 2 (cctv) | 9 |
| 8 (Manzaba) | 1 |
| 9 (Fernando) | 1 |
| 3 (test) | 0 |

Credenciales: columnas `hashedpassword` y `salt`, ambas de 48 caracteres hex
en los 5 usuarios (el primer caracter varia por usuario). No presentan el
formato BCrypt `$2a$...` de Traccar; el algoritmo exacto queda **no
verificado** y debe auditarse antes de migrar autenticacion. No se copiaron
valores.

Comandos:

```bash
docker exec -i dmj-db psql -U traccar -d traccar -c "select id, name, email, administrator, readonly, disabled from tc_users order by id;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select userid, count(*) from tc_user_device group by 1 order by 1;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select id, length(hashedpassword), length(salt), left(hashedpassword,1) from tc_users;"
```

## 7. Dispositivos

`tc_devices` (9 equipos; instantanea de la auditoria, los `lastupdate` y
`status` cambian con el flujo vivo; fechas en hora local -05):

| id | name | uniqueid | status | lastupdate |
|---|---|---|---|---|
| 47 | qa-f0 | qa-f0 | unknown | 2026-09-25 16:44:47.587 |
| 50 | macias | macias | online | 2026-09-25 20:56:24.456 |
| 51 | Jeremy | jeremy | offline | (nulo) |
| 52 | Kevin | kevin | online | 2026-09-25 20:58:29.134 |
| 53 | Joseph | joseph | offline | 2026-09-22 17:07:12.136 |
| 54 | David | david | offline | (nulo) |
| 56 | Pilay | pilay | online | 2026-09-25 20:58:35.145 |
| 58 | Manzaba | manzaba | unknown | 2026-09-25 20:34:42.597 |
| 59 | Fernando | fernando | online | 2026-09-25 20:59:14.91 |

Atributos `mobile.*` existentes (solo nombres de clave y numero de equipos que
la tienen): `mobile.accuracy` (2), `mobile.ackTimeoutSeconds` (4),
`mobile.airplane` (2), `mobile.androidSdk` (2), `mobile.androidVersion` (2),
`mobile.angleDegrees` (2), `mobile.appVersion` (6),
`mobile.backgroundRestricted` (1), `mobile.battery` (2),
`mobile.batteryExempt` (2), `mobile.batteryHistory` (2),
`mobile.batteryOptimized` (1), `mobile.bootId` (2), `mobile.bufferEnabled` (2),
`mobile.bufferMax` (4), `mobile.bufferPolicy` (4), `mobile.cadenceMovingMs`
(2), `mobile.causeAt` (2), `mobile.client` (4), `mobile.degraded` (5),
`mobile.distanceMeters` (2), `mobile.fcmTokenPrefix` (6),
`mobile.fcmTokenRegistered` (6), `mobile.fcmUpdatedAt` (6),
`mobile.fixEnqueued` (2), `mobile.fixReceived` (2), `mobile.fixRejected` (2),
`mobile.gnssTotal` (2), `mobile.gnssUsed` (2), `mobile.gps` (5),
`mobile.gpsAt` (5), `mobile.gpsEnabled` (2), `mobile.gpsState` (5),
`mobile.healthAt` (2), `mobile.healthOutbox` (2), `mobile.healthState` (2),
`mobile.intervalSeconds` (4), `mobile.journeyEndedAt` (2), `mobile.journeyId`
(5), `mobile.lastCrashAt` (1), `mobile.lastEndedJourneyId` (5),
`mobile.lastFixTime` (2), `mobile.lastOtaAt` (1), `mobile.lastOtaCheckAt`
(8), `mobile.lastOtaError` (1), `mobile.lastOtaHttpCode` (1),
`mobile.lastOtaResult` (1), `mobile.lastOtaStage` (1), `mobile.lastOtaUa` (8),
`mobile.lastOtaUpdate` (8), `mobile.lastOtaVersionCode` (8),
`mobile.lastPositionAt` (5), `mobile.lastPresenceAt` (5), `mobile.maxRetries`
(4), `mobile.minIntervalSeconds` (2), `mobile.mockLocation` (2),
`mobile.model` (2), `mobile.motionState` (2), `mobile.mqttState` (5),
`mobile.netCause` (2), `mobile.netConf` (2), `mobile.netState` (5),
`mobile.network` (2), `mobile.outboxState` (5), `mobile.pending` (2),
`mobile.permBackground` (2), `mobile.permFine` (2), `mobile.pollActive` (2),
`mobile.presenceAt` (5), `mobile.presenceReason` (5), `mobile.presenceState`
(5), `mobile.rejectBreakdown` (2), `mobile.rom` (2), `mobile.rttMs` (2),
`mobile.sessionId` (2), `mobile.signal` (2), `mobile.speedSource` (2),
`mobile.standbyBucket` (1), `mobile.validated` (2), `mobile.vendor` (2),
`mobile.wifiEnabled` (2). (81 claves; no se volcaron valores salvo los
indicados abajo.)

Versiones de app por equipo. `mobile.appVersion` y, entre parentesis,
`lastDiagnostics.report.app.versionCode` (el valor `lastDiagnostics` es un
string JSON dentro de `attributes`, hay que parsearlo dos veces):

| id | name | appVersion | versionCode diagnostico |
|---|---|---|---|
| 47 | qa-f0 | 2.1.63 | no registrado |
| 50 | macias | 2.1.73 | 283 |
| 52 | Kevin | 2.1.55 | 265 |
| 54 | David | 1.1.13 | no registrado |
| 58 | Manzaba | 2.1.65 | 275 |
| 59 | Fernando | 2.1.65 | 275 |
| 51, 53, 56 | Jeremy, Joseph, Pilay | no registrado | no registrado |

GPS y mock:

| id | name | mobile.gps | mobile.mockLocation | mobile.gpsState |
|---|---|---|---|---|
| 47 | qa-f0 | off | false | stale |
| 50 | macias | on | false | no_fix |
| 52 | Kevin | on | (sin clave) | stale |
| 58 | Manzaba | on | (sin clave) | stale |
| 59 | Fernando | on | (sin clave) | stale |

Ningun equipo reporta `mobile.mockLocation=true`. El servidor tiene activo el
filtro `filter.mock` (descarta posiciones marcadas como mock).

Prefijos de token FCM (`mobile.fcmTokenPrefix`, valor ya truncado por diseno):
qa-f0 `aac3099d79d8`, macias `aac3099d79d8`, Kevin `f0774377648b`, David
`f0774377648b`, Manzaba `df893bb925c3`, Fernando `4c8d2f201d62`. Los tokens
completos viven en `tc_fcm_tokens` (no se copiaron).

Comandos:

```bash
docker exec -i dmj-db psql -U traccar -d traccar -c "select id, name, uniqueid, status, lastupdate from tc_devices order by id;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select key, count(*) from tc_devices, jsonb_each(attributes::jsonb) e where key like 'mobile%' group by key order by key;"
docker exec -i dmj-db psql -U traccar -d traccar -c "select id, name, attributes::jsonb->>'mobile.appVersion', (attributes::jsonb->>'lastDiagnostics')::jsonb#>>'{report,app,versionCode}' from tc_devices;"
```

## 8. APIs y contratos que usa la App actual

### 8.1 Protocolo OsmAnd en :5055

Evidencia en `fallback/app/src/main/java/org/traccar/client/ProtocolFormatter.kt`
y `RequestManager.kt`: la App construye una URL con parametros en el query
string y la envia por **HTTP POST** (el decoder OsmAnd del servidor tambien
acepta GET). Parametros que envia la App:

| Parametro | Origen | Notas |
|---|---|---|
| `id` | `position.deviceId` | Identificador de dispositivo (uniqueId, en minusculas: la App normaliza `deviceId` a minusculas) |
| `timestamp` | `position.time.time / 1000` | Segundos epoch |
| `lat`, `lon` | posicion | Obligatorios |
| `speed` | `position.speed` | Ya viene en NUDOS (comentario del codigo: no volver a multiplicar) |
| `bearing` | `position.course` | |
| `altitude` | posicion | |
| `accuracy` | posicion | |
| `batt` | `position.battery` | Nivel de bateria |
| `charge` | `position.charging` | Solo si esta cargando |
| `mock` | `position.mock` | Solo si es posicion simulada |
| `alarm` | opcional | Solo para eventos SOS/alarma |

Respuesta del servidor (decoder OsmAnd): **200 OK**; 400 si faltan `lat`/`lon`
o hay error de parseo; 404 para rutas no reconocidas. No se inventan otros
codigos.

### 8.2 Canal web :999 (`/api/mobile/v1/...`)

Evidencia en `fallback/.../DmujeresApi.kt`, `RemoteConfig.kt` y en los
recursos del servidor `Mobile*Resource.java`, `OtaResource.java`.

| Metodo y ruta | Cabeceras | Cuerpo / query | Respuestas |
|---|---|---|---|
| GET `/api/mobile/v1/config` | `X-Api-Key`, `X-Device-Id` (o `?deviceId=`) | - | 200 JSON; 400 sin id; 401 clave invalida; 404 dispositivo desconocido; 503 canal apagado |
| POST `/api/mobile/v1/journey` | `X-Api-Key` | JSON `{deviceId, action:"start"|"stop", journeyId, client}` | 200; 400; 401; 404 |
| POST `/api/mobile/v1/diagnostics` | `X-Api-Key`, `X-Device-Id` (o `?deviceId=`) | JSON con `report` (crash, salud, ota, etc.) | 200; 400; 401; 403; 404; 413 |
| GET `/api/mobile/v1/ota?deviceId=<id>&versionCode=<n>` | `X-Api-Key`, `User-Agent` | - | 200 `{version, versionCode, url, sha256, notes, minVersionCode?}`; 400; 401; 404 |
| POST `/api/mobile/v1/fcm-token` | `X-Api-Key`, `X-Device-Id` | JSON `{fcmToken, appVersion}` | 200; 400; 401; 403 |
| POST `/api/mobile/v1/recovery-ack` | `X-Api-Key`, `X-Device-Id` | JSON `{recoveryAttemptId, stage, priority, reason}` | 200; 400; 401; 403 |
| POST `/api/mobile/v1/health` | `X-Api-Key`, `X-Device-Id` | JSON | 200; 400; 401; 403; 404; 413 (existe en el servidor; la App fallback actual no lo usa) |
| POST `/api/mobile/v1/positions` | `X-Api-Key` | JSON (canal HTTP alternativo al OsmAnd) | 200; 400; 401; 404; 413; 503 (existe en el servidor; la App fallback usa OsmAnd :5055) |

Respuesta de `/config` (claves): `intervalSeconds`, `bufferMax`,
`bufferPolicy`, `ackTimeoutSeconds`, `maxRetries`, `distanceMeters`,
`angleDegrees`, `accuracy`, `bufferEnabled`, `l1_pending_intent_enabled`,
`store_all_enabled`, `l1_max_update_delay_ms`, `min_interval_seconds`. La App
solo aplica `intervalSeconds`, `distanceMeters`, `angleDegrees`, `accuracy` y
`bufferEnabled` (RemoteConfig.kt).

Codigos de login que interpreta la App (`checkLogin`): 200 = autorizado,
404 = usuario desconocido, 401/403 = clave incorrecta, 503 = canal
deshabilitado, otro = sin conexion.

### 8.3 FCM

- Registro de token: `onNewToken` -> POST `/api/mobile/v1/fcm-token`.
- Push de recuperacion: data message `type=TRACKING_RECOVERY_PROBE` +
  `recoveryAttemptId`. La App acusa `RECOVERY_RECEIVED`, arranca el servicio
  en foreground y luego acusa `RECOVERY_STARTED` (el servidor rechaza otros
  stages).
- Servidor: `fcm.recovery.enabled=true`, `fcm.recovery.cooldownSeconds=60`,
  `fcm.recovery.maxPerHour=5`. Auditoria en `tc_recovery_event` (9 631 filas).
- Credencial de servidor: variable `GOOGLE_APPLICATION_CREDENTIALS` en `.env`;
  apunta a `/home/opencode/.config/dmujeres/secrets/firebase-adminsdk.json`
  (JSON de service account de Firebase; existe, no se leyo su contenido).
- La app usa `google-services.json` compartido con la app nativa (misma
  `applicationId` `com.dmujeres.traccar` y misma firma).

### 8.4 MQTT

- La App fallback actual (cliente Plan B) **no usa MQTT**: no hay referencias
  a MQTT en `fallback/app/src`.
- La app anterior `mobile/` (fuera de uso) define los topics en
  `mobile/.../core/MobileProtocol.kt`: publica telemetria en
  `dmj/v1/devices/{deviceId}/telemetry` y consume ACK en
  `dmj/v1/devices/{deviceId}/ack` (raiz `dmj/v1/devices`).
- El servidor mantiene el consumidor MQTT activo
  (`MOBILE_MQTT_ENABLE=true`): se suscribe a `MOBILE_MQTT_TOPIC` y responde en
  `MOBILE_MQTT_ACK_TOPIC`, con QoS 1. Variables: `MOBILE_MQTT_URL`,
  `MOBILE_MQTT_USERNAME`, `MOBILE_MQTT_PASSWORD`, `MOBILE_MQTT_CLIENT_ID`,
  `MOBILE_MQTT_MAX_PAYLOAD`, `MOBILE_MQTT_WORKER_QUEUE`,
  `MOBILE_MQTT_LEASE_SECONDS`.
- Broker EMQX con autenticacion por `infrastructure/emqx/auth-file.csv` y ACL
  `infrastructure/emqx/acl-file.conf` (contienen usuarios y hashes; valores no
  copiados).

### 8.5 Claves compartidas (solo nombres)

- Cabeceras HTTP: `X-Api-Key`, `X-Device-Id`.
- Configuracion del servidor: `mobile.http.enable`, `mobile.http.apiKey`,
  `mobile.http.apiKeyPrevious` (rotacion).
- `.env`: `MOBILE_HTTP_ENABLE`, `MOBILE_HTTP_API_KEY`,
  `MOBILE_HTTP_API_KEY_PREVIOUS`, `MOBILE_MQTT_*`, `WEB_SECRET_TOKEN`,
  `ENCRYPTION_KEY`, `POSTGRES_PASSWORD`, `REDIS_PASSWORD`,
  `EMQX_DASHBOARD_PASSWORD`, `GOOGLE_MAPS_KEY`, `MAPTILER_KEY`,
  `BING_MAPS_KEY`, `DASH_ADMIN_PASSWORD`, `GOOGLE_APPLICATION_CREDENTIALS`.
- App Android: `BuildConfig.MOBILE_HTTP_API_KEY` (inyectado en compilacion
  desde `mobile/secrets.properties`, archivo ignorado por git). La App tambien
  permite sobrescribir la clave con la preferencia `password`.
- Atributos de dispositivo usados como contrato: `mobile.*` (seccion 7).

Archivos que alojan secretos (solo rutas): `/DMujeres-Tracking/.env` (permisos
`-rw-------`), `/DMujeres-Tracking/server/conf/traccar-dev.xml` (`600`),
`/DMujeres-Tracking/mobile/secrets.properties` (`600`, ignorado por git),
`/DMujeres-Tracking/infrastructure/emqx/auth-file.csv`,
`/var/backups/dmj/restore/env` y `/var/backups/dmj/restore/traccar-dev.xml`
(copias de respaldo con permisos `600`), y
`/home/opencode/.config/dmujeres/secrets/firebase-adminsdk.json`.

## 9. Web actual

- `dashboard/` es el fork del panel Traccar (`package.json`: nombre `traccar`
  6.14.5, `private`). Stack: React 19.2, Vite 8 (`vite build`), MUI 9,
  MapLibre GL 5, Redux Toolkit, PWA (`vite-plugin-pwa`).
- Se sirve como estatico desde el propio Traccar:
  `traccar-dev.xml` -> `web.path=../dashboard/build`, `web.port=999`,
  `web.address=0.0.0.0`, `web.sessionTimeout=604800`, `web.console=true`.
- `dashboard/build/` contiene `index.html`, `assets/`, `sw.js` (service
  worker), `manifest.webmanifest` y ademas los artefactos OTA: APKs
  historicos, `latest.json` (version 2.1.73, versionCode 283, URL
  `http://68.168.20.219:999/DMujeres-Tracking-2.1.73.apk`, sha256) y
  `rollout.json` (percent 100, allow con los 9 equipos).
- El plan maestro reemplaza este panel por la Web nueva en `/home/DMujeres-Tracking/apps/web`.
  Importante: mientras el OTA apunte a `web.path`, el directorio servido debe
  seguir publicando `latest.json`, `rollout.json` y los APK.
- El build actual pesa 885 MB (incluye ~100 APK); `node_modules` 714 MB.

Comandos:

```bash
grep -nE 'web\.(path|port|address)' /DMujeres-Tracking/server/conf/traccar-dev.xml
cat /DMujeres-Tracking/dashboard/package.json
ls /DMujeres-Tracking/dashboard/build/
cat /DMujeres-Tracking/dashboard/build/latest.json /DMujeres-Tracking/dashboard/build/rollout.json
```

## 10. TLS

- NO hay terminacion TLS en el sistema actual: Nginx no instalado, Caddy y
  Apache2 inactivos, sin listeners en 80/443.
- Traccar (`traccar-dev.xml`) no tiene entradas `ssl*`: Web/API en HTTP
  plano por 999 y OsmAnd en HTTP plano por 5055.
- Las URLs OTA publicadas usan `http://68.168.20.219:999/...`.
- EMQX tiene configurados `EMQX_CERTS_DIR=./emqx-certs`,
  `EMQX_CERTFILE=/opt/emqx/certs/tls.crt`, `EMQX_KEYFILE=/opt/emqx/certs/tls.key`,
  pero el directorio `infrastructure/emqx/emqx-certs` **no existe** y los
  puertos TLS de EMQX (8883/8084) no estan publicados. TLS en MQTT: no
  operativo.
- No se encontraron certificados del producto en el arbol (`.crt/.pem/.key`).

El plan maestro introduce Nginx + TLS en la plataforma nueva; la App actual
solo conoce `http://`, por lo que el endpoint publico compatible debe seguir
existiendo (o mantener redireccion/escucha HTTP) durante la transicion.

## 11. Jobs y respaldos

Cron (`/etc/cron.d/dmj-backup`):

```
0 3 * * *  opencode /DMujeres-Tracking/infrastructure/scripts/backup.sh >> /var/log/dmj/backup.log 2>&1
30 4 * * 0 opencode /DMujeres-Tracking/infrastructure/scripts/verify-backup.sh >> /var/log/dmj/backup.log 2>&1
```

- `backup.sh`: `pg_dump --format=custom` de la base `traccar`, verificacion
  con `pg_restore --list`, copia de `.env` y `traccar-dev.xml` a
  `restore/` con permisos `600`, `VERSIONS.txt` con commits de server y
  dashboard, y retencion `BACKUP_RETENTION_DAYS=30` (borra dumps con
  `-mtime +30`).
- `verify-backup.sh`: semanal, falla si no hay dump o si el ultimo tiene mas
  de 26 h; valida integridad sin tocar la base.
- `BACKUP_DIR=/var/backups/dmj` (fuera del arbol del proyecto). Contenido:
  dumps diarios desde 2026-09-22 mas dumps puntuales
  (`traccar-pre-purga-recorridos.dump` de 2026-09-22 02:57,
  `traccar-pre-cambio-usuarios.dump` del 09-22 14:55, `traccar-20260919-125422.dump`).
  **Hallazgo:** los dumps `traccar-20260920-030001.dump` y
  `traccar-20260921-030001.dump` tienen 0 bytes (respaldo fallido esos dos
  dias); el ultimo, `traccar-20260925-030001.dump` (3.3 MB), paso la
  verificacion (`dump OK` en `/var/log/dmj/backup.log`).
- `restore.sh` existe y es destructivo (DROP DATABASE) con confirmacion
  interactiva; no se ejecuto.
- `/DMujeres-Tracking/backups/` solo contiene
  `dmj-worktree-20260917-1352.tar.gz` (2.5 MB, snapshot del arbol de trabajo).
- `infrastructure/scripts/` incluye ademas: `build-pilot.sh` (compila APK,
  sube versionCode, publica OTA solo para `macias`), `publish-ota.sh`,
  `publish-github-release.sh`, `pack-project.sh`, `package-project.sh`,
  `db-timescale.sh`, `migrate.sh`, `mqtt-users.sh`, `crear-usuario.sh`,
  `create-collaborator.sh`, `r4_capture_session.sh`, `run-server-dev.sh`,
  `dev.sh` (levanta compose), `traccar-systemd.sh`, `traccar-watchdog.sh`.
- Logs: `/var/log/dmj/backup.log`, `/var/log/dmj/watchdog.log` y
  `server/logs/` (rotacion diaria `tracker-server.log.*`).
- No hay systemd timers de backup adicionales; el unico timer `dmj-*` es el
  watchdog.

## 12. Documentacion y pruebas existentes

Documentacion en `/DMujeres-Tracking/docs/`: `BASELINE.md`, `QA_MATRIX.md`,
`TESTING_STRATEGY.md`, `F2_TEST_MATRIX.md`, `PRODUCTION_RUNBOOK.md`,
`RUNBOOK-SANTIAGO.md`, `RECOVERY.md`, `FCM_RECOVERY.md`, `fcm-recovery.md`,
`SECURITY.md`, `SECURITY_BUILD.md`, `SENTRY.md`, `INCIDENT_DIAGNOSIS.md`,
`ARCHITECTURE_*.md`, `CURRENT_ARCHITECTURE.md`, `MODULE_BOUNDARIES.md`,
`DEPENDENCY_RULES.md`, `TECHNICAL_DEBT.md`, `GAP_ANALYSIS.md`,
`FINAL_PRODUCTION_AUDIT.md`, `MANUAL-JORNADA.md`, `COMPILAR-APK.md`,
`OEM_COMPATIBILITY.md`, `TRACKING_RELIABILITY.md`, `DEV_WORKFLOW.md`,
`REFACTORING_LOG.md`, `REFACTOR_BASELINE.md`, `HANDOFF-ROUTES-2026-09-08.md`,
`PRESENTACION_*.md`, `EMERGENCY_FALLBACK.md`, `COLABORACION_SERVIDOR.md`,
`ANDROID_ARCHITECTURE.md`; subcarpetas `architecture/` (SERVER.md, APP.md,
FALLBACK_APP.md, FLUJOS.md, README.md), `audit/` (38 informes R1-R8, OEM,
seguridad, transporte, replay, etc.) y `security/` (INCIDENT_secretos_zip.md).

Pruebas existentes:
- `fallback/app/src/test/java/org/traccar/client/` (9 archivos):
  `AdaptiveCadenceTest`, `RefreshOutcomeTest`, `LocationWatchdogTest`,
  `MotionSignalTest`, `DatabaseHelperTest`, `TurnDetectorTest`,
  `RequestManagerTest`, `ProtocolFormatterTest`, `OtaDecisionTest` (este
  ultimo figura como archivo sin seguimiento en git).
- `server/src/test/java/...`: 462 archivos `.java` de test. El build es
  Gradle (`server/gradlew`).
- `dashboard/src/**/*.test.js` (tests con `node --test`).
- `mobile/` (app anterior) mantiene su propia suite.

Comandos de test documentados en `docs/BASELINE.md` (ultima corrida
registrada; NO fueron re-ejecutados en esta auditoria de solo lectura):

```bash
cd /DMujeres-Tracking/server  && ./gradlew cleanTest test      # 821 tests / 0 fallos / 29 skipped
cd /DMujeres-Tracking/fallback && ./gradlew test               # unit JVM del cliente Plan B
cd /DMujeres-Tracking/mobile  && ./gradlew :app:testDebugUnitTest
cd /DMujeres-Tracking/dashboard && node --test src/map/util/ src/other/qualityLabel.test.js src/common/util/deviceHealth.test.js
```

## 13. Riesgos y compatibilidades necesarias para la migracion

1. **Identidad de dispositivos**: conservar `tc_devices.id`, `uniqueid` y
   `tc_users.id`; la App normaliza el `deviceId` a minusculas y lo envia como
   parametro `id` en OsmAnd. Un `uniqueid` cambiado rompe `X-Device-Id` en los
   canales `/api/mobile/v1/*` y el filtro de la App.
2. **Endpoint publico estable**: la App deriva la base web reemplazando
   `:5055` por `:999` sobre la URL configurada (default
   `http://68.168.20.219:5055`). El servidor nuevo debe seguir atendiendo
   ambos puertos y el mismo host, o la flota completa queda aislada hasta
   actualizar la App.
3. **Credencial compartida `X-Api-Key`**: la clave viaja compilada en la app
   (`BuildConfig.MOBILE_HTTP_API_KEY`) y el servidor acepta rotacion con
   `mobile.http.apiKeyPrevious`. La migracion debe conservar la misma clave o
   mantener ambas durante la transicion; perderla obliga a recompilar y
   reinstalar la app.
4. **Historico fragmentado**: `tc_positions` de produccion solo tiene
   2026-09-22 a 2026-09-25; `tc_positions_bak_20260903` (08-17 a 09-03) y
   `traccar_qa` (hasta 09-12) guardan el resto. El tramo 09-12 a 09-22 solo
   podria estar en `traccar-pre-purga-recorridos.dump` (no verificado). Hay
   que consolidar todo antes del corte para no perder historial.
5. **Particionado/hypertables**: `tc_positions`, `tc_events` y `tc_actions`
   son hypertables TimescaleDB 2.29.1 particionadas por tiempo
   (`fixtime`/`eventtime`/`actiontime`). La restauracion en un PostgreSQL 18
   sin TimescaleDB o con otra version exige plan explicito (extension,
   migracion de chunks o tabla particionada por rango).
6. **Contrato OsmAnd**: el decoder acepta `speed` en nudos; la App ya no
   multiplica. Un cambio en el parser (p. ej. asumir km/h) alteraria las
   velocidades y los reportes. La respuesta debe seguir siendo 200.
7. **FCM**: conservar `applicationId com.dmujeres.traccar`, firma de la app,
   `google-services.json` y el service account referenciado por
   `GOOGLE_APPLICATION_CREDENTIALS`; el push `TRACKING_RECOVERY_PROBE` y los
   stages `RECOVERY_RECEIVED`/`RECOVERY_STARTED` y los tokens de
   `tc_fcm_tokens` deben migrarse para que la recuperacion siga funcionando.
8. **MQTT**: el consumidor del servidor esta activo aunque la App fallback no
   lo use. Si se retira, decidir explicitamente porque la app anterior
   (`mobile/`) y equipos antiguos podrian depender de
   `dmj/v1/devices/+/telemetry`.
9. **Servicio de map-matching fuera del proyecto**: el jar y GraphHopper viven
   en `/opt/matchservice` y `MatchResource` apunta fijo a
   `127.0.0.1:8991`. La plataforma nueva debe incluirlo o los reportes/matching
   se degradan.
10. **OTA**: `latest.json`, `rollout.json` y los APK se sirven desde
    `dashboard/build` (el `web.path` de Traccar). La Web nueva debe asumir esa
    responsabilidad o la flota dejara de ver actualizaciones.
11. **Formato de jornada**: los eventos `start`/`stop` con `journeyId` epoch y
    el outbox de la App (max. 20, reintento ordenado) deben seguir siendo
    aceptados; `tc_mobile_messages` (5 560) y `tc_recovery_event` (9 631)
    contienen el estado operativo reciente.
12. **Discos al 95%**: `/` y `/home` comparten filesystem (78 GB, 4.5 GB
    libres). La restauracion de prueba, los dumps y el nuevo build compiten
    por el mismo espacio. Hay dumps de 0 bytes (20/21 sep) que evidencian
    fallos previos de respaldo.
13. **TLS ausente**: si la plataforma nueva fuerza HTTPS en el endpoint
    publico, la App actual (solo `http://`) no podra conectar. Mantener HTTP
    compatible o planificar actualizacion de flota.
14. **Puerto privilegiado 999**: el servicio corre como `opencode` y necesita
    `CAP_NET_BIND_SERVICE` (drop-in actual). Replicar esa capacidad o usar
    Nginx delante en el nuevo despliegue.
15. **Arboles sucios**: raiz con 29 archivos modificados y ambos submodulos
    `-dirty`; congela/commitear antes de migrar para tener un punto de
    partida reproducible.
16. **`server/traccar-web` sin inicializar**: si algun proceso lo requiere,
    hay que inicializar el submodulo anidado en la nueva instalacion.
17. **Marca de tiempo anomala en `tc_positions`**: 5 filas de `macias` con
    `servertime` identico `2026-09-25 22:03:27.289673` (posterior al reloj de
    recepcion) y `fixtime` de las 11:22; corregir o excluir en la migracion
    para no arrastrar rangos y reportes distorsionados.

## 14. Volumen aproximado

Filas por tabla principal: ver seccion 5 (tabla de conteos).

Disco usado por carpeta de `/DMujeres-Tracking` (datos al 2026-09-25):

| Carpeta | Tamano total | Observaciones |
|---|---|---|
| `/DMujeres-Tracking` | 4.6 GB | Incluye artefactos de build |
| `dashboard/` | 2.4 GB | `build/` 885 MB (APKs + web) y `node_modules/` 714 MB |
| `mobile/` | 437 MB | App anterior + cache Gradle; 11 MB sin `build/`/`.gradle` |
| `server/` | 324 MB | `target/` 162 MB, `build/` 40 MB; 122 MB el resto (99 MB sin build/target) |
| `fallback/` | 181 MB | `app/build/` 169 MB; 1.7 MB sin build |
| `dist/` | 103 MB | Zips de entrega del proyecto |
| `infrastructure/` | 14 MB | Scripts, compose, matchservice fuente |
| `backups/` | 2.5 MB | Snapshot del worktree (2026-09-17) |
| `docs/` | 1.1 MB | Documentacion |
| `/home/DMujeres-Tracking` | 644 KB | Andamiaje nuevo (2 archivos) |
| `/opt/matchservice` | 26 MB | Jar + fuente + grafo (fuera del arbol) |

Espacio libre (mismo filesystem para `/` y `/home`):

```
Filesystem      Size  Used Avail Use% Mounted on
/dev/sda1        78G   73G  4.5G  95% /
```

Comandos:

```bash
du -sh /DMujeres-Tracking
du -sh /DMujeres-Tracking/*
du -sh --exclude=build --exclude=node_modules --exclude=target --exclude=.gradle /DMujeres-Tracking/{server,dashboard,fallback,mobile,infrastructure,docs}
df -h / /home
```

## Anexo: alcance y limitaciones

- Todo dato numerico de este informe proviene de comandos ejecutados en esta
  sesion (listados en cada seccion). Nada se verifico por inferencia.
- No se consultaron valores de secretos; los archivos que los contienen se
  citan solo por ruta.
- No se ejecutaron los tests ni restauraciones: los resultados de pruebas son
  los registrados en `docs/BASELINE.md` y se citan como referencia historica.
- Queda como **no verificado**: el algoritmo exacto de hash de `tc_users`, el
  contenido del dump `traccar-pre-purga-recorridos.dump` para el tramo
  09-12 a 09-22, y la accesibilidad real desde Internet del puerto 5055.
