# Runbook — dispositivo `santiago` con ~3957 mensajes pendientes

> Alcance: lado **servidor + repo**. La app Android (`mobile/app/src`) la lleva
> otro agente y **no se toca aquí**. Desde este entorno **no hay credenciales
> SSH del VPS**: lo ejecutable en el VPS lo corre el operador en orden.
> **No versionar ni publicar secretos reales**: usar los placeholders `<...>`.

## 1. Diagnóstico verificado (2026-09-03)

| Hecho | Evidencia en repo / sondeo |
|---|---|
| EMQX solo conoce `dmj-consumer` y `juan-001` | `infrastructure/emqx/auth-file.csv` (bootstrap; `santiago` no existe) |
| `santiago` falla en MQTT por credenciales | Sin usuario en EMQX el CONNACK es `Not authorized`; la app acumula en su cola local y reintenta |
| `mobile.http.enable=false` por defecto | `server/.../config/Keys.java` (`MOBILE_HTTP_ENABLE`, default `false`); el fallback solo vive si se activa por config/env |
| Puerto `999` no alcanzable desde internet | Sondeo público sin credenciales: `68.168.20.219:1883` **ABIERTO**, `68.168.20.219:999` **conexión rechazada** (solo abre 22 y 1883) |
| Consumer Java con lease 60 s | `server/.../mobile/MobileMqttConsumer.java` + `Keys.MOBILE_MQTT_LEASE_SECONDS` (default `60`); `MobileMessage` (`tc_mobile_messages`: `status`, `leaseuntil`, `attempts`) |
| Clave fallback dev coherente app↔server | App: `AppConfig.HTTP_API_KEY = "dmj-dev-fallback-key"` (solo lectura); server ejemplo dev: `server/conf/traccar-dev.xml.example` (`mobile.http.enable=true`, misma clave de ejemplo); runtime dev además por env `MOBILE_HTTP_ENABLE/MOBILE_HTTP_API_KEY` (`run-server-dev.sh`, `.env.example`) |

Consecuencia: aunque el fallback HTTP estuviera activo en el server, la app no
puede usarlo desde internet mientras `999` siga cerrado; y aunque `999` se
abra, la ingesta por MQTT seguirá fallando hasta crear el usuario `santiago`.
Hay que hacer **ambas cosas** (pasos 2 y 4), en orden.

## 2. Runbook VPS (ejecutar en orden, con placeholders)

Precondiciones en el VPS: acceso shell, `.env` del proyecto, compose prod
(`infrastructure/compose/docker-compose.prod.yml`), `curl`, `python3`,
`openssl`, `mosquitto_sub/pub` (opcional, para la prueba MQTT).

### Paso 0 — Salud base (antes de tocar nada)

```bash
cd /ruta/a/DMujeres-Tracking
docker compose --env-file .env -f infrastructure/compose/docker-compose.prod.yml ps
docker exec <emqx-container> emqx ctl status
curl -s -o /dev/null -w 'health local: %{http_code}\n' http://127.0.0.1:999/api/health
tail -50 /ruta/a/logs/traccar/server.log  # ajustar ruta real del log
```

### Paso 1 — Crear el dispositivo `santiago` en Traccar (`uniqueId=santiago`)

La vía recomendada lo hace todo (dispositivo + usuario MQTT):

```bash
./infrastructure/scripts/create-collaborator.sh santiago '<MQTT_PASS_SANTIAGO>'
# Requiere en .env: DASH_URL, DASH_ADMIN_EMAIL, DASH_ADMIN_PASSWORD
```

Equivalente manual (si el script no aplica en el VPS):

```bash
# 1a) Dispositivo vía API (sesión admin)
COOKIE=$(mktemp)
curl -s -c "$COOKIE" -X POST '<DASH_URL>/api/session' \
  -d 'email=<DASH_ADMIN_EMAIL>&password=<DASH_ADMIN_PASSWORD>' -o /dev/null
curl -s -b "$COOKIE" -X POST '<DASH_URL>/api/devices' \
  -H 'Content-Type: application/json' \
  -d '{"name":"santiago","uniqueId":"santiago","category":"default"}'
rm -f "$COOKIE"
# 1b) Usuario MQTT (ver paso 2)
```

Verificar en BD (ver paso 5 para conexión psql):

```sql
SELECT id, uniqueid, name FROM tc_devices WHERE uniqueid = 'santiago';
```

### Paso 2 — Crear el usuario MQTT `santiago` en EMQX

```bash
# Alta en caliente (built_in_database, EMQX 5.8)
./infrastructure/scripts/mqtt-users.sh add santiago '<MQTT_PASS_SANTIAGO>'
# Requiere EMQX_API_URL + (EMQX_API_KEY/EMQX_API_SECRET o dashboard admin en dev)

# Verificar que ya existe
./infrastructure/scripts/mqtt-users.sh list | grep -w santiago
```

La misma `<MQTT_PASS_SANTIAGO>` debe quedar configurada en la app
(`username=santiago`, esa contraseña). La ACL ya lo cubre sin cambios:
`infrastructure/emqx/acl-file.conf` permite a cualquier usuario autenticado
publicar en `dmj/v1/devices/${username}/telemetry` y suscribirse a
`dmj/v1/devices/${username}/ack`.

### Paso 3 — Verificar EMQX (auth + conectividad como `santiago`)

```bash
docker exec <emqx-container> emqx ctl clients list | grep -i santiago
docker exec <emqx-container> emqx ctl listeners
# Prueba de credenciales (no muta nada del server):
mosquitto_sub -h 127.0.0.1 -p 1883 -u santiago -P '<MQTT_PASS_SANTIAGO>' \
  -t 'dmj/v1/devices/santiago/ack' -q 1 -C 1 -W 10 -v
```

Si el SUB conecta y queda esperando: credenciales OK. Si responde
`Connection Refused: Not authorized`: el usuario no existe o el password no
coincide → repetir paso 2.

### Paso 4 — Abrir y verificar el fallback HTTP (`:999`)

1. El bind ya es `0.0.0.0:999` (`web.address` en `traccar-dev.xml.example`;
   en prod `CONFIG_WEB_ADDRESS=0.0.0.0`, `CONFIG_WEB_PORT=999`).
2. Confirmar que el fallback está activo en el VPS (env mandan sobre el XML):

```bash
grep -E 'MOBILE_HTTP_ENABLE|MOBILE_HTTP_API_KEY' .env
# Debe existir: MOBILE_HTTP_ENABLE=true y MOBILE_HTTP_API_KEY=<clave-real-no-ejemplo>
```

3. Si se cambió `.env`, reiniciar el server para que tome las claves y mirar
   el log de arranque.
4. Abrir el firewall y comprobar escucha:

```bash
sudo ufw allow 999/tcp
sudo ufw status numbered
ss -ltnp | grep -E '999|1883'
```

5. Sonda no-mutante del endpoint (clave errónea a propósito: `401` = endpoint
   vivo y habilitado; `404` = `mobile.http.enable` sigue en `false`):

```bash
curl -s -o /dev/null -w 'fallback con clave mala: %{http_code} (esperado 401)\n' \
  http://127.0.0.1:999/api/mobile/v1/positions \
  -H 'X-Api-Key: clave-mala-a-proposito' \
  -H 'Content-Type: application/json' -d '[]'
curl -s -o /dev/null -w 'health: %{http_code} (esperado 200)\n' \
  http://127.0.0.1:999/api/health
```

6. Repetir ambas sondas contra la IP pública desde fuera del VPS.

### Paso 5 — Revisar logs del server (consumer Mobile, lease, PG)

```bash
grep -i 'Mobile MQTT consumer started\|Mobile message pending\|lease\|Mobile HTTP' \
  /ruta/a/logs/traccar/server.log | tail -30
# Esperado tras el fix: "Mobile MQTT consumer started", sin rachas de
# "connection failed" del consumer; los "pending" deben tender a 0.
```

### Paso 6 — SQL de verificación (`tc_devices`, `tc_mobile_messages`)

```bash
# psql según despliegue (dev local): 
./infrastructure/scripts/dev.sh psql
# o directo en el VPS contra el contenedor red de prod.
```

```sql
-- id del dispositivo santiago
SELECT id, uniqueid, name FROM tc_devices WHERE uniqueid = 'santiago';

-- pendientes por estado (sustituir <ID> por el id anterior)
SELECT status, COUNT(*) FROM tc_mobile_messages WHERE deviceid = <ID> GROUP BY status;

-- los ~3957 pendientes
SELECT COUNT(*) AS pending FROM tc_mobile_messages WHERE deviceid = <ID> AND status = 'pending';

-- detalle de lease / reintentos (lease 60 s del consumer)
SELECT messageid, sequence, attempts, leaseuntil, updated
FROM tc_mobile_messages
WHERE deviceid = <ID> AND status = 'pending'
ORDER BY sequence LIMIT 20;

-- ¿están entrando posiciones ya? (últimos 15 min)
SELECT COUNT(*) FROM tc_positions WHERE deviceid = <ID> AND fixtime > NOW() - INTERVAL '15 minutes';
```

Si alguna columna difiriera de nombre, inspeccionar antes con
`\d tc_mobile_messages` (los campos vienen de
`server/.../model/MobileMessage.java`: `deviceid, messageid, sequence,
status, positionid, payloadhash, created, updated, leaseuntil, leasetoken,
attempts`).

### Paso 7 — Confirmar que `santiago` ya drena

1. Repetir el `COUNT(*) ... status='pending'` del paso 6 hasta ver
   **`pending → 0`** (el replay de la app + lease de 60 s lo vacía solo).
2. En la app (pantalla de Diagnóstico, sin cambios de código): **pendientes
   locales → 0** y **`last_ack_at` fresco** (crece con cada ACK
   `accepted/duplicate`).
3. En el panel: el dispositivo `santiago` vuelve a marcar en línea y el
   contador de pendientes (<50 ok, >100 rojo) se vacía.

## 3. Cambios en el repo (este fix, lado servidor+repo)

- `server/conf/traccar-dev.xml.example`: bloque explícito del canal móvil —
  `mobile.mqtt.*` (consumer `dmj-consumer`, lease 60 s) y
  `mobile.http.enable=true` + `mobile.http.apiKey=dmj-dev-fallback-key`
  (clave de ejemplo dev, coherente con `AppConfig.HTTP_API_KEY`; en VPS/prod
  manda `MOBILE_HTTP_API_KEY` real de `.env`).
- `docs/RUNBOOK-SANTIAGO.md` (este archivo): runbook exacto del VPS.
- `README.md`: fila de troubleshooting que apunta a este runbook.
- **No tocado**: `mobile/app/src/**`, secretos (solo placeholders),
  `auth-file.csv`/`acl-file.conf` (el alta de `santiago` es runtime vía
  `mqtt-users.sh`, no se versionan passwords).

## 4. Verificación de lo cambiable (local, sin SSH)

- `docker compose -f infrastructure/compose/docker-compose.yml config --quiet`
  → OK (compose dev válido; no se versiona nada roto).
- `bash -n` sobre scripts referenciados → OK (no se modificaron scripts).
- Sondeo público sin credenciales (2026-09-03): `68.168.20.219:1883` abierto,
  `68.168.20.219:999` rechazado → confirma el §1 y motiva el paso 4.

## 5. Evidencia 2026-09-08 — causa raíz confirmada: GNSS sin fix (no red)

El alta del usuario ya se hizo (existe en EMQX: `mqtt-users.sh list` →
`santiago`, y `dmj-santiago-*` conectado desde 181.199.63.6). El problema
RESTANTE no es de conexión: los datos llegan pero son basura para ruta.

Evidencia SQL (2026-09-07, `tc_positions` + `tc_devices.attributes`):

| Hecho | Evidencia |
|---|---|
| Jornada de trabajo 06:00–08:00: 497 posiciones TODAS en la misma coordenada (-2.2428/-79.9149), speed 0 | `group by latitude,longitude` → 1 cluster |
| Trayecto real 20:24–20:49 UTC: SOLO 5 puntos en 25 min, todos speed=0, con huecos de 4–10 min | fila a fila en tc_positions |
| El server deduplicaba por messageId, NO por contenido: el mismo fix de red reencolado pasaba como aceptado | `tc_mobile_messages` status=accepted 1799 |
| App: `fixReceived=986, fixRejected=0, fixEnqueued=986` — el filtro de la app no filtraba nada | atributos device |
| `gnssUsed=0, gnssTotal=1, simPresent=false` | atributos device |

**Causa raíz:** el teléfono (Innovatech P8) no fija satélites GNSS (sospecha
hardware/antena; verificar en campo al aire libre). El FusedLocationProvider
reentrega indefinidamente el último fix de wifi cacheado: clusters en lugares
quietos y ruta vacía en carretera. El "fuera de conexión" es secundario (sin
SIM depende del wifi).

**Parches aplicados (2026-09-08):**
- App: `stale_relay` en FixFilter (no encolar re-entrega exacta <5 min),
  `provider`/`fixAgeSec` en el envelope, GNSS forzado + fallback GPS_PROVIDER
  con aviso "Sin señal GPS" (v1.0.68).
- Server: `MobileQualityFilter.Verdict.DUPLICATE` (coordenada exacta + speed 0
  en <120 s no se guarda; ACK `duplicate` drena la cola igual), atributos
  `provider`/`fixAgeSec` en la posición y evento `mobileStalled` (jornada
  activa ≥15 min sin coordenadas nuevas).
- Dashboard: `MapRoutePath`/`MapDeviceTrail` excluyen puntos con
  `attributes.provider in {network, unknown}` de la geometría.

**Pendiente operador:** instalar v1.0.68 al colaborador y prueba en campo al
aire libre. Si `gnssUsed` sigue 0 con cielo despejado → GNSS del P8 muerto;
cambiar dispositivo. El clientId MQTT ahora es estable (antes cada reconexión
creaba una sesión nueva en EMQX: se observaron 50+ `dmj-santiago-*` concurrentes;
las antiguas quedarán hasta expirar en el broker).
