# FASE 4b - `services/tracking`: OTA propia, FCM y eventos de jornada

Complemento de `docs/operations/FASE4-TRACKING.md` (Fase 4a). Fecha: 2026-09-26.
Autor: agente BACKEND/TRACKING. Sin commits.

## 1. Alcance

Cierra los pendientes de 4a que la flota necesita para funcionar contra la
plataforma nueva sin Traccar:

1. **OTA propia** con politica de despliegue (`latest.json` + `rollout.json`) en
   `DMJ_OTA_DIR`, por defecto `/home/DMujeres-Tracking/ota`.
2. **FCM real**: el token completo se guarda en `iam.dmt_token_fcm` (ya no solo
   el prefijo).
3. **`recovery-ack` persistente** en `operations.dmt_alerta`.
4. **Eventos de jornada** (`mobileJourneyStarted`/`mobileJourneyEnded`) en
   `tracking.dmt_evento`, sin duplicados.
5. Arranque: `DMJ_OTA_DIR` nuevo por defecto; `DMJ_TRACKING_HOST`/`PORT` siguen
   configurables (el orquestador pondra `0.0.0.0:5055` en el corte; el `.env`
   de desarrollo no cambia).

Escribe **solo** en `dmt-db`/`dmujeres`. No toca produccion ni `dmj-*`.

## 2. Cambios por archivo

### `src/entorno.js`

- `OTA_DIR_POR_DEFECTO = '/home/DMujeres-Tracking/ota'` (linea 22), con el
  comentario de que el orquestador copia ahi `latest.json`, `rollout.json` y el
  APK. `DMJ_OTA_DIR` sigue ganando sobre el default.
- Sin cambios en `DMJ_TRACKING_HOST`/`DMJ_TRACKING_PORT` (defaults 127.0.0.1 /
  5056 para desarrollo).

### `src/movil.js`

- **OTA** (`src/movil.js:378-462`):
  - `bucketRollout(deviceId)`: SHA-256 del `identificador` del equipo, dos
    primeros bytes big-endian modulo 100 (mismo criterio que
    `OtaRolloutPolicy` del servidor anterior: decision estable por equipo).
  - `leerRollout(dir)`: lee `rollout.json` (`percent`, `paused`, `allow`);
    ausente/ilegible/fuera de rango = fail-open `100/false/[]`.
  - `permitirActualizacion(...)`: politica, en orden:
    1. instalado >= publicado -> no hay actualizacion (sin downgrade);
    2. `allow` no vacia es decisiva: solo sus equipos reciben manifiesto
       (vence pausa y porcentaje);
    3. instalado < `minVersionCode` -> forzada (se sirve aunque este en pausa);
    4. `paused` -> denegada;
    5. `percent < 100` -> `bucket(deviceId) < percent`.
  - `atenderOta`: 503 canal apagado, 401 clave invalida, 400 sin
    `deviceId`/`versionCode`, 404 dispositivo desconocido, 404 sin
    `latest.json`, 200 `{"update":false}` o el manifiesto completo.
  - Sigue auditando `mobile.lastOtaCheckAt`, `mobile.lastOtaVersionCode`,
    `mobile.lastOtaUpdate` y `mobile.lastOtaUa` (rate-limit 1/min, best-effort)
    con el resultado ya decidido por la politica.
- **fcm-token** (`src/movil.js:472-510`): valida el token (<=512), llama a
  `guardarTokenFcm` (fila completa en `iam.dmt_token_fcm`) y luego fija
  `mobile.fcmTokenRegistered`/`mobile.fcmUpdatedAt` (y `mobile.fcmTokenPrefix`
  y `mobile.appVersion` como en el contrato). Responde
  `200 {"ok":true,"success":true,"status":"registered"}`.
- **recovery-ack** (`src/movil.js:512-552`): valida `recoveryAttemptId`/`stage`,
  recorta `priority`/`reason` y persiste la alerta `recovery_ack` con atributos
  `{attemptId, stage, priority, reason}`. Responde
  `200 {"ok":true,"success":true,"status":"accepted"}`.

### `src/db.js`

- `nombreParticionEvento` (`src/db.js:33`) y `#asegurarParticionEvento`
  (`src/db.js:100`): crea de forma idempotente la particion mensual de
  `tracking.dmt_evento` (misma plantilla de `03_tracking.sql`) para que un
  evento de un mes sin particion no falle.
- `#insertarEvento` (`src/db.js:117`): inserta `mobileJourneyStarted` /
  `mobileJourneyEnded` dentro de la transaccion de la jornada, con
  `ocurrido_en = now()` y `atributos = {journeyId, mobileSeverity:'info',
  battery?}`; la deduplicacion es por `(dispositivo_id, tipo,
  atributos->>'journeyId')`.
- `abrirJornada` (`src/db.js:274`) y `cerrarJornada` (`src/db.js:305`) invocan
  el inserto de evento.
- `guardarTokenFcm` (`src/db.js:354`): upsert por `token` (UNIQUE) con
  `ultimo_uso_en = now()`, `activo=true`, `invalido=false`.
- `registrarAlertaRecuperacion` (`src/db.js:373`): inserta en
  `operations.dmt_alerta` con `origen='recuperacion'`, `tipo='recovery_ack'`,
  `estado='nueva'`, `ocurrido_en=now()` y los atributos recibidos.

### `src/smoke.mjs`

- Crea un `DMJ_OTA_DIR` temporal (`/tmp/dmj-ota-humo-*`), escribe
  `latest.json`/`rollout.json` y arranca el servidor apuntando ahi.
- Pasos nuevos: jornada con eventos y dedupe (4), OTA (17), FCM (3),
  recovery-ack (2), limpieza de las tres tablas nuevas (3).
- Verifica y limpia `tracking.dmt_evento`, `iam.dmt_token_fcm` y
  `operations.dmt_alerta`; borra la carpeta temporal.

### `src/servidor.js`

- Sin cambios: el enrutado `/api/mobile/v1/ota` ya existia y `HOST`/`PORT`
  ya eran configurables.

## 3. Variables de entorno (actualizado)

| Variable | Default | Uso |
|---|---|---|
| `DMJ_TRACKING_HOST` | `127.0.0.1` | interfaz (corte: `0.0.0.0`) |
| `DMJ_TRACKING_PORT` | `5056` | puerto (corte: `5055`; el `.env` de dev sigue en `6066`) |
| `DMJ_CANAL_MOVIL` | `1` | `0` apaga el canal (OTA -> 503) |
| `DMJ_OTA_DIR` | `/home/DMujeres-Tracking/ota` | carpeta de `latest.json`/`rollout.json` |

Las claves `DMJ_CLAVE_MOVIL`/`DMJ_CLAVE_MOVIL_ANTERIOR` y la conexion
`POSTGRES_*`/`DMJ_DB_*` no cambian.

## 4. Contrato de OTA

`latest.json` (obligatorio):

```json
{"version":"1.1.17","versionCode":117,"url":"...apk","notes":"...","sha256":"...","minVersionCode":100}
```

`rollout.json` (opcional, fail-open):

```json
{"percent":50,"paused":false,"allow":["macias"]}
```

| Caso | Respuesta |
|---|---|
| `versionCode` instalado >= publicado | `200 {"update":false}` |
| `allow` no vacia y el equipo no esta | `200 {"update":false}` |
| `allow` no vacia y el equipo esta | `200` manifiesto (vence pausa/percent) |
| instalado < `minVersionCode` | `200` manifiesto (forzada) |
| `paused` y no forzada | `200 {"update":false}` |
| `percent` parcial | `200` manifiesto si `bucket(deviceId) < percent` |
| canal apagado | `503` |
| clave invalida | `401` |
| sin `deviceId`/`versionCode` | `400` |
| dispositivo desconocido | `404` |
| sin `latest.json` | `404` |

## 5. Decisiones y divergencias

1. **`ok:true` + contrato congelado**: las respuestas de `fcm-token` y
   `recovery-ack` conservan `{"success":true,"status":...}` del contrato y
   anaden `"ok":true` pedido en la fase; ningun cliente existente se rompe.
2. **OTA canal apagado = 503** (antes 404 en `services/tracking`; el contrato
   congelado §3 listaba 404). Lo pide el enunciado de 4b y es coherente con
   `config`; se documenta aqui.
3. **`rollout.json` opcional** (fail-open 100/false), igual que el servidor
   anterior; solo la ausencia de `latest.json` produce 404.
4. **Allowlist vence a pausa y porcentaje**, igual que `OtaRolloutPolicy`; la
   pausa solo bloquea a los equipos con version >= `minVersionCode`.
5. **Upsert de token por `token`** (no por dispositivo): un mismo token
   re-registrado actualiza su fila; no se desactivan otros tokens del equipo
   (FCM puede tener varias instalaciones y el envio real llega despues).
6. **`recovery_ack` con `estado='nueva'` y `reconocida_en=null`**: la fila es
   el registro del ack del equipo, no una revision del operador.
7. **Evento de jornada por `journeyId` del cliente**: un reintento de
   start/stop con el mismo `journeyId` no duplica; el `journeyId` de BD todavia
   no existe al decidir, y el cliente ya usa el suyo como identidad estable.
8. **Particion mensual de `dmt_evento` automatica**, igual que la de
   `dmt_posicion`.
9. **El envio de push de recuperacion (servicio FCM con cuenta de servicio)
   queda para la fase siguiente**: aqui solo se guardan token y acks.

## 6. Verificacion (ejecutada)

`node24 --check` de `movil.js`, `db.js`, `entorno.js`, `servidor.js`,
`smoke.mjs`: OK.

`node24 src/smoke.mjs` (PASS, 65 comprobaciones, limpieza confirmada):

```
[smoke] dispositivo qa-f0 id=47; antes: posiciones=5283 actual=1 jornadas=6 baterias=4612 abiertas=2 eventos=1113 tokens=2 alertas=2069
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
OK    - dmt_evento registra mobileJourneyStarted con journeyId/severidad/bateria
OK    - journey start repetido no duplica el evento (eventos=1)
OK    - journey stop responde 200 {ok:true}
OK    - operations.dmt_jornada cierra la jornada
OK    - journeyId a 0 y lastEndedJourneyId
OK    - dmt_evento registra mobileJourneyEnded con journeyId/severidad
OK    - journey stop repetido no duplica el evento (eventos=1)
OK    - diagnostics responde 204 (estado=204)
OK    - diagnostics persistido en lastDiagnostics
OK    - atajos mobile.* del diagnostico
OK    - telemetry.dmt_bateria recibe la muestra
OK    - diagnostics mayor a 10 KB responde 413 (estado=413)
OK    - diagnostics con deviceId ajeno responde 403 (estado=403)
OK    - diagnostics con JSON invalido responde 400 (estado=400)
OK    - OTA con instalado igual al publicado responde {"update":false} (estado=200)
OK    - OTA con instalado mayor al publicado responde {"update":false} (estado=200)
OK    - OTA con instalado menor entrega el manifiesto completo (estado=200)
OK    - OTA audita mobile.lastOta* en el dispositivo (checkAt=1790477922840 update=false)
OK    - OTA en pausa con instalado >= minVersionCode responde {"update":false} (estado=200)
OK    - OTA con instalado bajo minVersionCode se sirve aunque este en pausa (forzada) (estado=200)
OK    - OTA con allowlist sin el equipo responde {"update":false} (estado=200)
OK    - OTA con allowlist que incluye al equipo entrega el manifiesto (vence la pausa) (estado=200)
OK    - OTA con percent 0 responde {"update":false} (estado=200)
OK    - OTA con percent por encima del bucket estable (26) entrega el manifiesto (estado=200)
OK    - OTA con percent igual al bucket estable (25) responde {"update":false} y de forma estable (estado=200)
OK    - OTA con percent 100 entrega el manifiesto (estado=200)
OK    - OTA sin latest.json responde 404 (estado=404)
OK    - OTA con clave invalida responde 401 (estado=401)
OK    - OTA sin versionCode responde 400 (estado=400)
OK    - OTA con dispositivo desconocido responde 404 (estado=404)
OK    - OTA con canal apagado (DMJ_CANAL_MOVIL=0) responde 503 (estado=503)
OK    - fcm-token responde 200 {ok:true} (estado=200)
OK    - iam.dmt_token_fcm guarda el token completo con ultimo_uso_en (filas=1)
OK    - fcm-token repetido hace upsert (una fila) y marca mobile.fcm* (filas=1)
OK    - recovery-ack responde 200 {ok:true} (estado=200)
OK    - operations.dmt_alerta registra recovery_ack con attemptId/stage/priority/reason (filas=1)
[smoke] limpieza aplicada
OK    - limpieza: posiciones igual que antes (5283 -> 5283)
OK    - limpieza: posicion actual igual que antes (1 -> 1)
OK    - limpieza: jornadas igual que antes (6 -> 6)
OK    - limpieza: baterias igual que antes (4612 -> 4612)
OK    - limpieza: eventos igual que antes (1113 -> 1113)
OK    - limpieza: tokens FCM igual que antes (2 -> 2)
OK    - limpieza: alertas igual que antes (2069 -> 2069)
OK    - limpieza: atributos del dispositivo restaurados
PASS
```

Los pasos de 4a (37 comprobaciones) siguen en PASS; los 28 nuevos cubren
jornada/eventos (4), OTA (16), FCM (3), recovery-ack (2) y limpieza (3).

## 7. Pendiente (fase siguiente)

- **Envio de push FCM** de recuperacion con cuenta de servicio
  (`GOOGLE_APPLICATION_CREDENTIALS` o equivalente) y maquina de estados de
  recuperacion sobre las filas `recovery_ack` ya registradas.
- **Orquestacion del corte**: `0.0.0.0:5055` (OsmAnd) y `:999` enrutando
  `/api/mobile/*` al servicio; copiar `latest.json`/`rollout.json`/APK a
  `/home/DMujeres-Tracking/ota/`.
- **MQTT** (`mobile.*`) y consumidores de panel/replay.

## 8. Dudas

- El contrato congelado §3 documenta 404 para OTA con canal apagado; aqui se
  implemento 503 por el enunciado de 4b. Si el E2E exige 404, es un cambio de
  una linea en `atenderOta`.
- `recovery_ack` guarda una fila por ack (etapas repetidas del mismo
  `attemptId` generan filas nuevas). Si el panel prefiere una fila por intento
  con la ultima etapa, habria que upsert por `atributos->>'attemptId'`.
- La allowlist compara texto exacto (igual que el servidor anterior); no hay
  normalizacion de mayusculas.
