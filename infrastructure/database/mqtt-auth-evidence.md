# Evidencia — autenticación y ACL MQTT permanentes en el entorno dev

Fecha: 2026-08-14. Broker: `emqx/emqx:5.8.5` (contenedor `dmj-mqtt`).
Cambio: authN `password_based/built_in_database` (bootstrap CSV) + authZ por archivo
(`no_match=deny`) integrados de forma permanente en `infrastructure/compose/docker-compose.yml`
(antes: override opcional `docker-compose.emqx-auth.yml`, eliminado).

## 1. `docker compose config` válido tras los cambios

```bash
$ docker compose --env-file .env -f infrastructure/compose/docker-compose.yml config --quiet
COMPOSE CONFIG: OK
```

Config aplicada al servicio `mqtt` (extracto):

```yaml
environment:
  EMQX_AUTHENTICATION__1__MECHANISM: password_based
  EMQX_AUTHENTICATION__1__BACKEND: built_in_database
  EMQX_AUTHENTICATION__1__USER_ID_TYPE: username
  EMQX_AUTHENTICATION__1__PASSWORD_HASH_ALGORITHM__NAME: sha256
  EMQX_AUTHENTICATION__1__PASSWORD_HASH_ALGORITHM__SALT_POSITION: prefix
  EMQX_AUTHENTICATION__1__BOOTSTRAP_FILE: /opt/emqx/etc/auth-file.csv
  EMQX_AUTHENTICATION__1__BOOTSTRAP_TYPE: hash
  EMQX_AUTHORIZATION__NO_MATCH: deny
  EMQX_AUTHORIZATION__DENY_ACTION: ignore
  EMQX_AUTHORIZATION__SOURCES__1__TYPE: file
  EMQX_AUTHORIZATION__SOURCES__1__PATH: /opt/emqx/etc/acl-file.conf
volumes:
  - ../emqx/auth-file.csv:/opt/emqx/etc/auth-file.csv:ro
  - ../emqx/acl-file.conf:/opt/emqx/etc/acl-file.conf:ro
```

Contenedor recreado (`./infrastructure/scripts/dev.sh up`) y healthy:

```
dmj-mqtt Up 27 seconds (healthy)
```

## 2. Usuarios bootstrap importados (API /api/v5/authentication/.../users)

```
juan-001
dmj-consumer
```

`auth-file.csv` (SOLO dev): `dmj-consumer / dmj-consumer-dev-pass`,
`juan-001 / Juan2026!` (hash `sha256(salt++password)`, salt 32 hex, `salt_position=prefix`).

## 3. Pruebas de conexión y ACL (cliente real MQTT v5 contra 127.0.0.1:1883)

```
PASS  1-anonymous-connect-rejected
PASS  2a-consumer-connect                (dmj-consumer: connect + subscribe dmj/v1/devices/+/telemetry + publish dmj/v1/devices/juan-001/ack)
PASS  3a-juan-publish-own-ok             (juan-001 -> dmj/v1/devices/juan-001/telemetry)
PASS  3b-juan-subscribe-own-ack-ok       (juan-001 subscribe dmj/v1/devices/juan-001/ack)
PASS  4-juan-publish-other-denied        (juan-001 -> dmj/v1/devices/otro/telemetry: DENEGADO)
PASS  5-juan-subscribe-wildcard-denied   (juan-001 subscribe dmj/v1/devices/+/telemetry: DENEGADO)
PASS  6-juan-publish-other-ack-denied    (juan-001 -> dmj/v1/devices/otro/ack: DENEGADO)

7/7 checks PASSED
```

## 4. Gestión de usuarios en runtime (mqtt-users.sh vía API)

```
$ ./infrastructure/scripts/mqtt-users.sh add runtime-test-001 'TestPass!1'
AVISO: sin EMQX_API_KEY/EMQX_API_SECRET — usando admin del dashboard (solo dev)
{"is_superuser":false,"user_id":"runtime-test-001"}
OK: usuario 'runtime-test-001' añadido (password hasheado por EMQX en el servidor).

$ ./infrastructure/scripts/mqtt-users.sh list
runtime-test-001
juan-001
dmj-consumer

$ ./infrastructure/scripts/mqtt-users.sh del runtime-test-001
OK: usuario 'runtime-test-001' eliminado.
```

## 5. Consumidor del server arranca autenticado

Reinicio con `./infrastructure/scripts/run-server-dev.sh restart` (exporta
`MOBILE_MQTT_USERNAME=dmj-consumer`, `MOBILE_MQTT_PASSWORD=dmj-consumer-dev-pass`):

```
AVISO: MOBILE_MQTT_PASSWORD usa el default dev (dmj-consumer-dev-pass).
       Definir MOBILE_MQTT_PASSWORD en .env si el CSV cambió.
OK
health HTTP 200
```

Log (`server/logs/tracker-server.log`):

```
2026-08-14 04:19:43  INFO: Mobile MQTT consumer started
```

## 6. Flujo end-to-end con credenciales (mqtt:e2e, usuario = deviceId)

Dispositivo `juan-001` creado en `tc_devices` (fixture de prueba).

```bash
MQTT_USER=juan-001 MQTT_PASSWORD='Juan2026!' MQTT_DEVICE_ID=juan-001 \
  MQTT_MESSAGE_ID=01JDMJTEST00000005 MQTT_SEQUENCE=424245 \
  MQTT_SENT_AT=2026-08-14T04:10:00Z MQTT_OBSERVED_AT=2026-08-14T04:10:00Z \
  npm run mqtt:e2e
```

- RUN 1 (primer envío): `"status": "accepted"`
- RUN 2 (mismo messageId+sequence+sentAt+observedAt): `"status": "duplicate"`

Con `MQTT_MESSAGE_ID` auto-generado (corregido para cumplir el patrón del server,
16+ chars):

```
"messageId": "01JDMJ00MSSFWCFH",
"status": "accepted",
```

Nota: con `messageId` distinto pero misma secuencia el server devuelve `rejected`
(guarda de reutilización de clave de dedupe con distinto payload) — comportamiento
previo esperado, no relacionado con la auth.

## 7. mqtt:baseline (transporte) bajo la ACL estricta

Publica IDs falsos `load-N`, que la ACL per-user deniega:

```
$ MQTT_USER=juan-001 ... npm run mqtt:baseline -- --devices 1 --duration 2
"attempted": 2, "acknowledged": 0, "errors": 2
```

Funciona si el username coincide con el ID simulado (usuario por dispositivo):

```
$ ./infrastructure/scripts/mqtt-users.sh add load-1 'LoadTest2026!'
$ MQTT_USER=load-1 MQTT_PASSWORD='LoadTest2026!' ... npm run mqtt:baseline -- --devices 1 --duration 2
"attempted": 2, "acknowledged": 2, "errors": 0
```

## 8. Resultado

- Conexión anónima: RECHAZADA (authN presente + `authorization.no_match=deny`).
- `dmj-consumer`: subscribe `dmj/v1/devices/+/telemetry` y publish `dmj/v1/devices/+/ack` OK.
- Móvil `${username}`: solo publish propio `.../${username}/telemetry` y subscribe propio
  `.../${username}/ack`; todo lo demás DENEGADO (sin wildcards para móviles).
- Server arranca conectado con credenciales (`Mobile MQTT consumer started`).
- Alta/baja de usuarios en runtime vía API (mqtt-users.sh).
