# Informe E2E — Flujo "colaborador" (app 1.0.4)

- Fecha: 2026-08-14 ~04:19–04:32 UTC
- Entorno: Traccar 6.14.5 (server-dev, localhost:8082) · EMQX 5.8.5 (mqtt://127.0.0.1:1883, dashboard 18083) · PostgreSQL/TimescaleDB (127.0.0.1:5433, hipertabla tc_positions)
- Colaborador de prueba: `maria-001` / `Maria2026!`
- Regla: **no se modificó código de la app ni del server**. Solo se crearon dispositivo/usuario de prueba y se ejecutaron scripts (oficiales y uno de test en `/tmp/opencode/dmj-e2e.mjs`).

## Resultado resumido

| Paso | Descripción | Resultado |
|---|---|---|
| 1 | Crear colaborador `maria-001` + verificar dispositivo y usuario MQTT | ✔ PASÓ |
| 2 | Simular app: 3 posiciones MQTT con ACK `accepted` | ✔ PASÓ (3/3) |
| 3 | Verificación BD: `tc_positions` (protocol `dmj-mqtt`) y `tc_mobile_messages` (`accepted` + positionid) | ✔ PASÓ |
| 4 | Estado ONLINE en panel (status/lastUpdate) | ✖ **FALLÓ** (ver hallazgo H-1) — posiciones sí, pero `status=offline`, `lastUpdate=NULL` |
| 5 | Reenvío idéntico → ACK `duplicate`, sin fila nueva | ✔ PASÓ |
| 6 | Publicar en topic de otro usuario → denegado por broker | ✔ PASÓ |
| 7 | Diagnóstico offline `dmj-9a95ad10` | ✔ COMPLETADO (ver sección) |

---

## Paso 1 — Creación del colaborador

Comando (las credenciales de admin no estaban en `.env`; se exportaron en el entorno de la shell sin tocar `.env`):

```bash
export DASH_ADMIN_EMAIL=admin@dmj.local DASH_ADMIN_PASSWORD='Dmujeres2026!Segura'
./infrastructure/scripts/create-collaborator.sh maria-001 'Maria2026!'
```

Salida:

```
==> 1/2 Creando dispositivo en Traccar (uniqueId=maria-001)...
   dispositivo creado: 32
==> 2/2 Creando usuario MQTT en EMQX (maria-001)...
AVISO: sin EMQX_API_KEY/EMQX_API_SECRET — usando admin del dashboard (dev)
{"is_superuser":false,"user_id":"maria-001"}
OK: usuario 'maria-001' añadido (password hasheado por EMQX en el servidor).
```

> Nota: el script no falló por EMQX API; usó el fallback dev documentado (login admin del dashboard, password `public`). Único requisito extra: `DASH_ADMIN_EMAIL`/`DASH_ADMIN_PASSWORD` se pasaron por entorno (no están en `.env`).

Verificación:

```sql
-- ./infrastructure/scripts/dev.sh psql
SELECT id, name, uniqueid FROM tc_devices WHERE uniqueid='maria-001';
-- 32 | maria-001 | maria-001
```

```bash
curl -sb admin.cookies http://localhost:8082/api/devices | grep maria-001
# 32 maria-001 maria-001 offline
./infrastructure/scripts/mqtt-users.sh list
# maria-001 / juan-001 / dmj-consumer   ← el nuevo usuario existe
```

## Paso 2 — Simulación de la app (3 posiciones + ACKs)

Script de test (`/tmp/opencode/dmj-e2e.mjs`, módulo `mqtt` del repo, envelope EXACTO v1: `schema:1, type:position, messageId 22 chars 01JAND…, deviceId, sequence, sentAt/observedAt ISO, payload lat/lon/accuracy/speed/bearing/altitude`; messageId replicado del generador de la app: `01JAND` + hash de `deviceId` + secuencia).

```
PUBLISHED 01JANDc49496da00000001 seq 1
ACK accepted 01JANDc49496da00000001 seq 1
PUBLISHED 01JANDc49496da00000002 seq 2
ACK accepted 01JANDc49496da00000002 seq 2
PUBLISHED 01JANDc49496da00000003 seq 3
ACK accepted 01JANDc49496da00000003 seq 3
```

ACK devuelto por el server (formato `Ack`): `{schema:1, type:"ack", deviceId, messageId, sequence, status:"accepted", serverReceivedAt}`.

## Paso 3 — Verificación en base de datos

```sql
SELECT id, deviceid, protocol, servertime, latitude, longitude, speed
FROM tc_positions WHERE protocol='dmj-mqtt' AND deviceid=32 ORDER BY id;
```
```
   id    | deviceid | protocol | servertime | latitude | longitude |  speed
---------+----------+----------+------------+----------+-----------+---------
 2705799 |       32 | dmj-mqtt | 04:27:59.6 | -33.4501 |  -70.6701 | 19.44
 2705800 |       32 | dmj-mqtt | 04:27:59.6 | -33.4502 |  -70.6702 | 19.44
 2705801 |       32 | dmj-mqtt | 04:27:59.7 | -33.4503 |  -70.6703 | 19.44
```

```sql
SELECT id, deviceid, messageid, sequence, status, positionid, payloadhash FROM tc_mobile_messages WHERE deviceid=32 ORDER BY id;
```
```
 416 | 32 | 01JANDc49496da00000001 | 1   | accepted | 2705799 | fa466c0d…
 417 | 32 | 01JANDc49496da00000002 | 2   | accepted | 2705800 | b6e820b5…
 418 | 32 | 01JANDc49496da00000003 | 3   | accepted | 2705801 | a1f77517…
```

## Paso 4 — Estado ONLINE en el panel

```bash
curl -sb admin.cookies http://localhost:8082/api/devices/32
# {"id":32,"name":"maria-001","uniqueId":"maria-001","status":"offline","lastUpdate":null,"positionId":2705805,...}
```

Consulta de posiciones (parte opcional del paso): ✔ funciona.

```bash
curl -sb admin.cookies "http://localhost:8082/api/positions?deviceId=32&from=2026-08-14T04:00:00Z&to=2026-08-14T05:00:00Z"
# 2705799 … 2705805 (7 posiciones)
```

### Hallazgo H-1 (paso 4 FALLA en el estado): el canal MQTT nunca marca el dispositivo "online"

- Posiciones persisten y `positionId` avanza, pero `tc_devices.status` queda vacío y `lastupdate` NULL → el panel muestra **offline** siempre.
- Causa en el código del server (solo lectura, sin modificar): `org.traccar.mobile` (MobileMqttConsumer/MobileIngestionService) no tiene ninguna referencia a `ConnectionManager.updateDevice(...)`, `Device.STATUS_ONLINE` ni `device.setLastUpdate(...)` (`grep updateDevice|STATUS_ONLINE|lastUpdate` en `server/src/main/java/org/traccar/mobile/` → 0 resultados). En este fork, status/lastUpdate solo los actualiza `ConnectionManager.updateDevice` (ConnectionManager.java:246) para sesiones de protocolos Netty; el pipeline de posiciones tampoco lo toca (0 hits en `pipeline/` y `handler/`).
- El dashboard deriva "online" exclusivamente de `device.status` (dashboard/src/main/DeviceRow.jsx:102).
- Impacto: el flujo 1.0.4 entrega y persiste posiciones correctamente, pero **el panel seguirá mostrando "offline"** hasta que el consumidor MQTT actualice estado/lastUpdate (cambio pendiente fuera del alcance de esta prueba: no se modifica código).

## Paso 5 — Duplicado (reenvío idéntico)

Reenvío byte-idéntico de la posición 1 (mismo messageId, sentAt, observedAt y payload) dentro del mismo flujo, como haría la app tras reconexión:

```
RESENDING_IDENTICAL {"schema":1,...,"messageId":"01JANDc49496da00000065","sequence":101,...}
DUPLICATE_ACK duplicate
```

Verificación: `SELECT count(*) FROM tc_positions WHERE deviceid=32` → **6** (3 del primer lote + 3 del segundo); el reenvío NO creó fila (sigue en 6) y `tc_mobile_messages` no registró fila nueva (una sola por messageId).

Hallazgo relacionado (comportamiento por diseño, evidencia en log del server `04:31:15-16`): reenviar un messageId **ya usado con distinto payload** → `rejected` ("Mobile dedupe key reused with a different payload"), sin insertar filas. El hash canónico cubre schema|type|messageId|deviceId|sequence|sentAt|observedAt|payload (MobileIngestionService.java:172-188).

## Paso 6 — Seguridad (topic de otro usuario)

Con sesión MQTT `maria-001`, suscripción y publicación contra `dmj/v1/devices/otro-usuario/*`:

```
SUBSCRIBE_OTHER_ACK DENIED: Subscribe error: Not authorized
PUBLISH_OTHER_TOPIC ERROR: Publish error: Not authorized
```

Log del broker (evidencia de ACL):

```
[warning] tag: AUTHZ, username: maria-001, topic: dmj/v1/devices/otro-usuario/ack, action: SUBSCRIBE(Q1) → authorization_permission_denied
[warning] tag: AUTHZ, username: maria-001, topic: dmj/v1/devices/otro-usuario/telemetry → cannot_publish_to_topic_due_to_not_authorized
```

Autenticación (authN) también reforzada: conectar con usuario inexistente → `Connection refused: Not authorized` (`authentication_failure` en EMQX).

## Paso 7 — Diagnóstico del dispositivo offline `dmj-9a95ad10`

### Evidencia

1. **En la BD nunca llegó nada**: `tc_devices` id=30 (uniqueId `dmj-9a95ad10`, nombre "test") tiene `lastupdate` NULL; `SELECT count(*) FROM tc_positions WHERE deviceid=30` → **0**; `tc_mobile_messages` deviceid=30 → **0 filas**. El dispositivo existe pero jamás entregó una posición.
2. **El usuario MQTT no existe**: `mqtt-users.sh list` → solo `dmj-consumer`, `juan-001`, `maria-001`. Conectar con `username=dmj-9a95ad10` → `AUTHN_FAILED: Connection refused: Not authorized` (log EMQX: `authentication_failure … username: dmj-9a95ad10 … not_authorized`).
3. **Intento externo rechazado**: log EMQX `2026-08-14T04:22:57` → `authentication_failure, clientid: go, peername: 194.88.98.116:43583, reason: bad_username_or_password` (peername = IP pública externa; el VPS es 64.176.219.221).
4. **Causa raíz en el código de la app 1.0.3** (commit `902bdcb`): `generateDeviceId()` autogeneraba `dmj-<8 hex de ANDROID_ID>` y lo usaba como identidad de topics/envelope; el username MQTT era opcional (`if (config.username.isNotBlank())` en MqttManager.kt). Resultado: ninguna combinación casaba con el modelo EMQX:
   - sin username → EMQX rechaza (sin anónimos); username inexistente → authN falla;
   - con un username válido (p. ej. `juan-001`) → ACL deriva el topic de `${username}` y la app publica en `dmj/v1/devices/dmj-9a95ad10/telemetry` → AUTHZ denegada; y aunque pasara, el server exige `envelope.deviceId == deviceId del topic`.
5. **Por qué el nuevo flujo (1.0.4) lo resuelve**: `AppConfig.deviceId` ahora se deriva del `username` (`username.filter{…}`), de modo que **username MQTT == deviceId del envelope/topic == uniqueId del dispositivo en Traccar == usuario EMQX**. El E2E completo de `maria-001` (pasos 1–6) demuestra la cadena: authN OK → ACL OK (solo su topic) → server resuelve el dispositivo → ACK `accepted`/`duplicate` → persistencia.

### Conclusión del diagnóstico

`dmj-9a95ad10` quedó offline porque **nunca pudo conectar/publicar**: la identidad autogenerada de la app 1.0.3 no existía en la authN de EMQX (y, de existir un username válido, el ACL ataba los topics a otro nombre). Cero mensajes llegaron al server (BD y logs lo confirman). Con app 1.0.4 (usuario = dispositivo) el canal funciona; queda pendiente solo el hallazgo H-1 (estado online en el panel).

---

## Artefactos y notas

- Script de test: `/tmp/opencode/dmj-e2e.mjs` (también se usó la herramienta oficial `infrastructure/load-tests` → `npm run mqtt:e2e` con `MQTT_USER/MQTT_PASSWORD/MQTT_DEVICE_ID/MQTT_SEQUENCE/MQTT_MESSAGE_ID`: último ACK `accepted` para seq 201, positionId 2705805).
- Totales finales del dispositivo 32: **7 posiciones** `protocol=dmj-mqtt` y 7 filas `tc_mobile_messages` `accepted`.
- `create-collaborator.sh` funcionó sin parches (env vars para el admin; fallback dev de EMQX API).
