# MQTT transport baseline

Este benchmark mide únicamente el transporte MQTT broker -> PUBACK. No mide todavía
persistencia en Traccar, ACK de aplicación, deduplicación ni latencia de TimescaleDB.
Es una línea base previa al adapter de ingesta de Fase 2.

## Ejecución

```bash
cd infrastructure/load-tests
npm ci
npm run mqtt:baseline -- --devices 1 --duration 30
npm run mqtt:baseline -- --devices 10 --duration 30
npm run mqtt:baseline -- --devices 100 --duration 30
npm run mqtt:baseline -- --devices 1000 --duration 30
npm run mqtt:e2e
```

Variables opcionales:

- `MQTT_URL` (default `mqtt://127.0.0.1:1883`)
- `MQTT_USER`
- `MQTT_PASSWORD`
- `MQTT_INTERVAL_MS` (default `10000`, una posición por dispositivo cada 10s)
- `MQTT_QOS` (default `1`)

La salida incluye intentos, PUBACKs, errores, p50/p95/p99 de ACK, mensajes por
segundo y pérdida de transporte observada. El benchmark no afirma entrega final en
BD; esa medición empieza cuando exista ACK de aplicación.

> El broker dev exige autenticación (ver `infrastructure/emqx/`). Todos los scripts
> aceptan `MQTT_USER`/`MQTT_PASSWORD`; sin credenciales la conexión se rechaza.
> El username ES el deviceId: el dispositivo `juan-001` solo puede publicar en
> `dmj/v1/devices/juan-001/telemetry`.

> **mqtt:baseline** publica con IDs falsos (`load-N`), así que con la ACL estricta
> (cada usuario solo publica en su propio topic) necesita un usuario cuyo username
> coincida con el ID que simula:
> `./infrastructure/scripts/mqtt-users.sh add load-1 '<pass>'` y luego
> `MQTT_USER=load-1 MQTT_PASSWORD='<pass>' npm run mqtt:baseline -- --devices 1 --duration 30`
> (un usuario por ID simulado; para N dispositivos: `load-1`..`load-N`).

## E2E experimental

Con el server arrancado temporalmente con `MOBILE_MQTT_ENABLE=true` y el broker dev
local, `mqtt:e2e` publica un envelope para `demo-001`, espera el ACK de aplicación y
muestra `accepted`. Repetir con el mismo `MQTT_MESSAGE_ID`, `MQTT_SEQUENCE`,
`MQTT_SENT_AT` y `MQTT_OBSERVED_AT` debe devolver `duplicate` sin crear otra posición.

Uso con credenciales (el usuario debe coincidir con el deviceId por la ACL):

```bash
MQTT_USER=juan-001 MQTT_PASSWORD='Juan2026!' MQTT_DEVICE_ID=juan-001 npm run mqtt:e2e
```

`MQTT_DEVICE_ID` (default `demo-001`) fija el topic; `MQTT_MESSAGE_ID`,
`MQTT_SEQUENCE`, `MQTT_SENT_AT` y `MQTT_OBSERVED_AT` permiten repetir el mismo
envelope para validar deduplicación (`duplicate`).

Este flujo demuestra integración, no producción: todavía existe una ventana no atómica
entre `tc_positions` y `tc_mobile_messages`.
