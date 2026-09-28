# FASE 4 (parte 2) - `services/tracking`

Receptor compatible con la App actual que escribe en la base nueva. Fecha:
2026-09-25. Autor: agente BACKEND/TRACKING.

## 1. Alcance y límites

- Implementa el protocolo OsmAnd y el canal `/api/mobile/v1/*` del contrato
  congelado (`docs/api/COMPATIBILIDAD-APP.md`) traduciendo los efectos al modelo
  `dmt_*` (`database/schema/`).
- Escribe **solo** en `dmt-db` (`127.0.0.1:5443`, base `dmujeres`). No lee ni
  escribe `dmj-db`/`traccar`, no reinicia ni depende de `dmj-traccar`; del árbol
  de producción solo lee (solo lectura) el manifiesto OTA.
- No se hizo ningún commit.

## 2. Arranque

```bash
cd /home/DMujeres-Tracking/services/tracking
npm24 install                 # dependencia unica: pg
node24 src/servidor.js
node24 src/smoke.mjs          # prueba de humo (PASS/FAIL)
```

Escucha en `127.0.0.1` (solo local) en `DMJ_TRACKING_PORT`.
**Puerto dev:** el plan fija 5056, pero en este host `dmj-traccar` escucha en
todo el rango 5000-5100 (verificado con `ss -ltnp`: pid java de
`dmj-traccar.service`), por lo que el `.env` de desarrollo usa `6066` y queda
el comentario para volver a 5056 tras el corte. El código mantiene 5056 como
default y la prueba de humo toma un puerto libre efímero.

Ejemplo systemd (transitorio, sin exponer secretos; el `.env` se lee por
`DMJ_ENV_FILE`):

```ini
[Service]
ExecStart=/usr/local/bin/node24 /home/DMujeres-Tracking/services/tracking/src/servidor.js
User=opencode
EnvironmentFile=/home/DMujeres-Tracking/.env
```

## 3. Variables de entorno

Se cargan del `.env` nuevo (`/home/DMujeres-Tracking/.env`, 600) y
`process.env` tiene precedencia. Nunca se registran valores secretos.

| Variable | Default | Uso |
|---|---|---|
| `DMJ_TRACKING_HOST` | `127.0.0.1` | interfaz de escucha |
| `DMJ_TRACKING_PORT` | `5056` | puerto (en este host, 6066 por colisión) |
| `DMJ_CANAL_MOVIL` | `1` | `0` apaga el canal (config → 503) |
| `DMJ_CLAVE_MOVIL` | - | `X-Api-Key` actual (copiada del `.env` legacy) |
| `DMJ_CLAVE_MOVIL_ANTERIOR` | - | clave de rotación (copiada del legacy) |
| `DMJ_OTA_DIR` | `/DMujeres-Tracking/dashboard/build` | carpeta de `latest.json` (solo lectura) |
| `DMJ_ENV_FILE` | raíz del proyecto `.env` | ruta alternativa del `.env` |
| `POSTGRES_USER/PASSWORD/DB`, `DMJ_DB_HOST`, `DMJ_DB_PORT` | - / `127.0.0.1` / `5443` | conexión a `dmujeres` (o `DMJ_DB_URL`) |

Las claves móviles se copiaron a `DMJ_CLAVE_MOVIL` /
`DMJ_CLAVE_MOVIL_ANTERIOR` con `grep`/`sed` directo al archivo y se verificaron
por comparación binaria sin imprimirlas.

## 4. Compatibilidad implementada

### OsmAnd (`GET`/`POST /`)

- Parámetros: `id`/`deviceid`, `timestamp` (epoch ms; si es menor a
  `Integer.MAX_VALUE` se interpreta en segundos), `lat`, `lon`, `speed`
  (nudos × 1.852 → `velocidad_kmh`), `bearing`/`heading`, `altitude`,
  `accuracy`, `batt`, `charge`, `mock`, `alarm`.
- Inserta en `tracking.dmt_posicion` (`id` de la secuencia nueva,
  `dispositivo_id`, `protocolo='osmand'`, `registrado_en` y `fijado_en` con el
  mismo timestamp UTC, `valida = !mock`, `atributos` con `charge`, `mock`,
  `alarm` e `id_legado: null`), hace upsert en `tracking.dmt_posicion_actual` y
  marca el dispositivo `estado='online'`, `ultima_conexion_en` y
  `ultima_posicion_id`.
- Crea de forma idempotente la partición mensual de `dmt_posicion` si el mes no
  existía (misma plantilla de `03_tracking.sql`).
- Respuestas: 200 sin cuerpo; 400 si faltan `id`, `lat`, `lon` o `timestamp`;
  404 si el dispositivo no existe (ver §6); 503 si falla el almacenamiento
  (recuperable para el cliente, que conserva el punto en su buffer).

### Canal móvil `/api/mobile/v1/*`

| Ruta | Comportamiento |
|---|---|
| `GET config` | 503 canal apagado; 401 clave; 400 sin dispositivo; 404 desconocido; 200 JSON con los mismos 13 campos y defaults del servidor actual, leídos de `mobile.*` |
| `POST journey` | 404 canal apagado; 401; 400 cuerpo/acción; 404 desconocido; 200 `{"ok":true}`. `start` cierra jornadas abiertas previas, abre fila en `operations.dmt_jornada` (usuario desde `operations.dmt_asignacion` activa) y fija `mobile.journeyId`/`mobile.client`; `stop` cierra, fija `mobile.journeyId=0`, `mobile.lastEndedJourneyId` y `mobile.journeyEndedAt` |
| `POST diagnostics` | 404/401/400/403 (`deviceId` del cuerpo ≠ autenticado)/413 (>10 000 bytes); 204. Guarda `lastDiagnostics` (cadena JSON, igual que el servidor actual), `lastDiagnosticsAt` y atajos `mobile.permBackground`, `mobile.batteryExempt`, `mobile.mockLocation`, `mobile.gps`, `mobile.gpsAt`, `mobile.lastCrashAt`; inserta muestra en `telemetry.dmt_bateria` si hay `report.power.battery` (0-100) con `ts` o hora del servidor. Antirrebote de 20 s (igual que el actual) → 204 sin escribir |
| `GET ota` | 404 canal apagado o sin `latest.json`; 401; 400 sin `deviceId`/`versionCode`; 404 desconocido; 200 con el manifiesto si `latest.versionCode > versionCode`; si no, `{"update":false}`. Auditoría `mobile.lastOta*` limitada a 1/min. Transitorio: lee el `latest.json` de producción, sin `rollout.json` |
| `POST fcm-token` | 401; 400 sin `X-Device-Id` o token inválido (>512); 403 desconocido; guarda `mobile.fcmTokenRegistered`, `mobile.fcmTokenPrefix` (SHA-256 recortado a 12 hex), `mobile.fcmUpdatedAt`, `mobile.appVersion`; responde 200 `{"success":true,"status":"registered"}`. El token completo nunca se registra |
| `POST recovery-ack` | 401; 400; 403 desconocido; registra `recoveryAttemptId`/`stage`/`priority` en log; responde 200 `{"success":true,"status":"accepted"}`. Sin máquina de estados (4b) |

Búsqueda de dispositivo por `identificador` **case-insensitive** (índice único
`uq_dmt_dispositivo_identificador` sigue siendo case-sensitive; durante la
migración no hay identificadores que difieran solo en mayúsculas).

## 5. Evidencia de verificación

### `npm24 install` (solo `pg`)

```
added 14 packages, and audited 15 packages in 698ms
found 0 vulnerabilities
```

### `node24 --check` de cada archivo

```
check OK: src/db.js
check OK: src/entorno.js
check OK: src/movil.js
check OK: src/osmand.js
check OK: src/servidor.js
check OK: src/smoke.mjs
```

### `node24 src/smoke.mjs` (PASS, limpieza confirmada)

```
[smoke] dispositivo qa-f0 id=47; antes: posiciones=5283 actual=1 jornadas=6 baterias=4612 abiertas=2
OK    - el servidor arranca en un puerto de prueba
OK    - OsmAnd GET responde 200 sin cuerpo (estado=200 cuerpo=0b)
OK    - OsmAnd POST responde 200
OK    - OsmAnd sin lon responde 400 (estado=400)
OK    - OsmAnd con dispositivo desconocido responde 404 (estado=404)
OK    - dmt_posicion guarda las dos posiciones (filas=2)
OK    - velocidad convertida de nudos a km/h (velocidad=23.15 km/h)
OK    - rumbo, altitud y precision
OK    - bateria en dmt_posicion
OK    - fijado_en = registrado_en
OK    - atributos charge/alarm/id_legado ({"mock":false,"alarm":"humo-smoke","charge":true,"id_legado":null})
OK    - posicion real valida
OK    - posicion mock no valida y guarda el atributo
OK    - dmt_posicion_actual apunta a la ultima posicion
OK    - dispositivo online con last seen y ultima posicion
OK    - config responde 200 con JSON compatible (estado=200)
OK    - config con clave invalida responde 401 (estado=401)
OK    - config con dispositivo desconocido responde 404 (estado=404)
OK    - config acepta la clave anterior en rotacion (estado=200)
OK    - journey start responde 200 {ok:true}
OK    - operations.dmt_jornada abre la jornada
OK    - mobile.journeyId actualizado al iniciar
OK    - journey stop responde 200 {ok:true}
OK    - operations.dmt_jornada cierra la jornada
OK    - journeyId a 0 y lastEndedJourneyId
OK    - diagnostics responde 204 (estado=204)
OK    - diagnostics persistido en lastDiagnostics
OK    - atajos mobile.* del diagnostico
OK    - telemetry.dmt_bateria recibe la muestra
OK    - diagnostics mayor a 10 KB responde 413 (estado=413)
OK    - diagnostics con deviceId ajeno responde 403 (estado=403)
OK    - diagnostics con JSON invalido responde 400 (estado=400)
[smoke] limpieza aplicada
OK    - limpieza: posiciones igual que antes (5283 -> 5283)
OK    - limpieza: posicion actual igual que antes (1 -> 1)
OK    - limpieza: jornadas igual que antes (6 -> 6)
OK    - limpieza: baterias igual que antes (4612 -> 4612)
OK    - limpieza: atributos del dispositivo restaurados
PASS
```

Comprobación posterior con psql: `tracking.dmt_posicion` total 80809 (sin
cambios), `qa-f0` con sus dos jornadas abiertas originales (ids 14 y 22,
`fin_en` nulo), `mobile.journeyId` original y su fila de `dmt_posicion_actual`
(85726) restaurada con los mismos timestamps. `fcm-token`, `ota` y
`recovery-ack` se verificaron aparte (200/200/200) restaurando los atributos
exactos después. Nota: `dmt_posicion_actual` pasó de 0 a 7 filas durante la
sesión por poblado de datos ajeno a este servicio (el humo solo la toca de
forma transitoria y la restaura).

### Producción intacta

- `docker exec dmj-db psql -U traccar -d traccar -tAc "select count(*) from tc_positions;"`:
  pasó de 26893 a 26911 durante la sesión **por tráfico real de la App**
  (`pilay`, `kevin`); el receptor nuevo no escribió ni una fila ahí.
- `dmt-db` healthy, `dmj-db` healthy, `dmj-traccar`/`dmj-match` activos.
- Sin claves ni tokens en el código, documentos o salidas.

## 6. Decisiones y divergencias respecto al contrato

1. **OsmAnd, dispositivo desconocido = 404** (el contrato del fork respondía
   400). Lo pidió el enunciado de la fase; para el cliente ambos son 4xx no
   recuperables (no borra el punto). Se documenta aquí por si el E2E prefiere
   400.
2. **`journey`, `fcm-token` y `recovery-ack` con 200 JSON** y no 204: manda el
   contrato congelado §3 (el resumen de la fase decía 204). `diagnostics` sí es
   204, como el servidor actual.
3. **`valida=false` con `mock=true`**: el fork guardaba solo el atributo; aquí
   además la marca de inválida (lo pidió el enunciado).
4. **`atributos.id_legado = null`** en cada posición nueva: las filas nuevas no
   tienen id legado. `dmt_posicion` no tiene columna `id_legado`, por eso va en
   `atributos`.
5. **Nombres de columnas `dmt_*`**: `estado` y `ultima_conexion_en`
   (no `status`/`last_seen_at`, que eran los nombres del borrador del plan).
6. **Fallos de almacenamiento → 503** (recuperable) en lugar de 500, alineado
   con el contrato de errores recuperables.
7. **OTA** lee `DMJ_OTA_DIR` (default `dashboard/build`, el que usa
   `publish-ota.sh`); el fork usaba también `dashboard/public`. Sin
   `rollout.json` por ahora.

## 7. Pendiente (fuera de esta fase)

- **FCM completo (4b):** envío real, tabla `iam.dmt_token_fcm`, máquina de
  estados de `recovery-ack` (`RECEIVED` → `GPS_CONFIRMED`), política de
  recuperación y cuenta de servicio.
- **OTA propio:** manifiesto y rollout nuevos (`rollout.json`, `paused`,
  `allow`), sin leer de producción.
- **MQTT:** consumidor `mobile.*` (hoy solo HTTP).
- **Eventos:** `mobileJourneyStarted/Ended` en `tracking.dmt_evento` y alertas
  (`operations.dmt_alerta`).
- **Panel/replay:** consumidores de `dmt_posicion_actual` y del histórico.
- **Pruebas automatizadas** por encima del humo (unitarias del parseo y
  normalización) y contrato E2E con la App real.

## 8. Dudas

- ¿Se acepta la divergencia 404 (OsmAnd desconocido) o se alinea a 400 del
  contrato? Es un cambio de una línea en `osmand.js`.
- ¿La clave móvil anterior debe seguirse aceptando indefinidamente? Hoy se
  acepta mientras exista `DMJ_CLAVE_MOVIL_ANTERIOR` (contrato de rotación).
- El puerto 5056 del plan es inutilizable en este host mientras `dmj-traccar`
  esté activo; confirmar si el dev queda en 6066.
