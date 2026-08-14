# Seguridad MQTT / EMQX — canal móvil dmj

Estado: config de acceso EMQX 5.8 para el canal MQTT móvil (`dmj/v1/devices/...`).
No sustituye a `docs/mqtt/protocol-v1.md` (contrato) ni a `docs/security/`.

## Alcance

- Broker: `emqx/emqx:5.8.5` (compose dev y prod).
- Topics (canal móvil):
  - publish móvil → `dmj/v1/devices/{deviceId}/telemetry`
  - publish server / subscribe móvil → `dmj/v1/devices/{deviceId}/ack`
  - subscribe server → `dmj/v1/devices/+/telemetry`
- Config de acceso: `infrastructure/emqx/` (`emqx.conf`, `auth-file.csv`, `acl-file.conf`)
  y scripts `infrastructure/scripts/mqtt-users.sh`.

## Comportamiento EMQX 5.8 (validado contra la imagen 5.8.5)

| Supuesto común | Realidad en 5.8 |
| --- | --- |
| `EMQX_ALLOW_ANONYMOUS` controla el anonimato | **No existe en el schema 5.x y se ignora.** El anonimato lo determina (a) la cadena `authentication` y (b) `authorization.no_match`. |
| Autenticación "por archivo" con `backend = file` | No existe `backend = file`. El equivalente es `backend = built_in_database` + `bootstrap_file` (CSV) con `bootstrap_type = hash`/`plain`. |
| `EMQX_AUTHENTICATION__1__USERNAME/PASSWORD` | Inválido en 5.8 (schema de 4.x). Usar `EMQX_AUTHENTICATION__1__MECHANISM/BACKEND/...`. |
| `emqx ctl` para crear usuarios MQTT | No existe en 5.8 (solo `admins` del dashboard). Usar la API HTTP o el dashboard. |

### Modelo de autenticación (authN)

- Mecanismo `password_based`, backend `built_in_database` (Mnesia), `user_id_type = username`.
- Hash: `sha256`, `salt_position = prefix` → `hash = sha256(salt ++ password)`, `salt` = 32 hex.
- Seed por archivo: `auth-file.csv` (bootstrap, `bootstrap_type = hash`). Formato:
  `user_id,password_hash,salt,is_superuser`. Se importa solo al crear el authenticator
  (arranque). Cambios posteriores: API HTTP (runtime) — ver rotación.

### Modelo de autorización (authZ)

- Fuente por archivo: `acl-file.conf` (formato Erlang-term de EMQX), `authorization.no_match = deny`,
  `deny_action = ignore`.

### ACL exacta por topic

| Usuario / patrón | Acción | Topic | Resultado |
| --- | --- | --- | --- |
| `dmj-consumer` (server) | subscribe | `dmj/v1/devices/+/telemetry` | allow |
| `dmj-consumer` (server) | publish | `dmj/v1/devices/+/ack` | allow |
| cualquier móvil `${username}` | publish | `dmj/v1/devices/${username}/telemetry` | allow |
| cualquier móvil `${username}` | subscribe | `dmj/v1/devices/${username}/ack` | allow |
| cualquier otro | publish/subscribe | cualquier topic (incl. `$SYS/#`, wildcards) | deny |

El placeholder `${username}` ata el topic al usuario autenticado: un móvil con
`user_id = <id>` solo toca `dmj/v1/devices/<id>/...` (el username ES el deviceId).
Sin wildcards para móviles. El server nunca publica telemetry ni se suscribe a acks
de un dispositivo concreto.

## Credenciales por dispositivo

Modelo: **un `user_id` por colaborador/dispositivo, y ese `user_id` ES el deviceId**.

- El `user_id` se usa como identidad: en el ACL, `${username}` deriva el topic; en la
  app debe ser estable en el tiempo y coincidir con el `deviceId` que publica.
- El `deviceId` del envelope MQTT debe coincidir con el `user_id` autenticado
  (validación de aplicación también — el ACL no inspecciona el payload).
- `is_superuser = false` siempre para móvil y para el server consumer.

Crear un dispositivo (alta en caliente, sin reiniciar el broker):

```bash
./infrastructure/scripts/mqtt-users.sh add juan-001 'Juan2026!'
# Añade el usuario con password (EMQX lo hashea en el servidor) vía API.
# Nada más que hacer: el ACL por ${username} ya lo habilita a su topic.
```

Provisionar el seed de arranque (auth-file.csv) con un hash:

```bash
./infrastructure/scripts/mqtt-users.sh hash 'secreto'
# user_id,password_hash,salt,is_superuser
# <user_id>,<sha256>,<salt>,false
```

### Rotación

- **Credencial de un dispositivo**: re-generar el password con
  `mqtt-users.sh add <user_id> <nuevo>` (sobrescribe) o `del` + `add`. Efecto inmediato.
- **Regenerar todo el seed (auth-file.csv)**: editar el CSV con `mqtt-users.sh hash` y
  recrear el contenedor (`dev.sh up` / `docker compose up -d`); el bootstrap solo se
  importa al arranque del authenticator.
- **ACL**: el archivo se relee bajo demanda con caché (TTL por defecto 1m);
  `emqx ctl` no tiene recarga forzada de ACL, pero los cambios son efectivos al
  expirar la caché (o recrear el servicio).
- **Dashboard admin**: rotar `EMQX_DASHBOARD_PASSWORD` y recrear el contenedor.

## TLS (producción)

- Listener `ssl:default` en `8883` (ya habilitado por defecto en la imagen).
- Rutas de certs por variables (`docker-compose.prod.yml`), secretos no versionados:
  - `EMQX_CERTS_DIR` → directorio host montado en `/opt/emqx/certs:ro`.
  - `EMQX_CERTFILE`, `EMQX_KEYFILE` → rutas dentro del contenedor (requeridos).
  - `EMQX_CACERTFILE` → opcional (cadena separada o mTLS). No fijar vacío.
- El dashboard (18083) no se publica en prod; exponer solo vía túnel SSH o con
  firewall a IP de admin (ver comentarios en el compose).

## Pasos de prueba

Con la auth/ACL activa en el compose dev (integrada por defecto en `docker-compose.yml`):

```bash
# 1) Broker arriba
./infrastructure/scripts/dev.sh up          # recrea mqtt con auth+ACL (dev por defecto)

# 2) Confirmar authN y authZ aplicados (dashboard API)
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"public"}' http://127.0.0.1:18083/api/v5/login
# → token; luego:
#   GET /api/v5/authentication          → password_based:built_in_database (+bootstrap)
#   GET /api/v5/authorization/settings  → {"no_match":"deny", ...}

# 3) Clientes conectados
./infrastructure/scripts/dev.sh mqtt ctl clients list
#   → lista user_id/clientid conectados (vacio si nadie)

# 4) Prueba de conexión + ACL (mosquitto_pub, o el harness node)
#    Server consumer — subscribe a wildcard telemetry y publish ack:
mosquitto_sub  -h 127.0.0.1 -p 1883 -u dmj-consumer -P dmj-consumer-dev-pass \
  -t 'dmj/v1/devices/+/telemetry' -q 1
mosquitto_pub  -h 127.0.0.1 -p 1883 -u dmj-consumer -P dmj-consumer-dev-pass \
  -t 'dmj/v1/devices/demo-001/ack' -m '{"status":"accepted","messageId":"01..."}' -q 1

#    Dispositivo — publish propio + subscribe a su ack (usuario = deviceId):
mosquitto_pub  -h 127.0.0.1 -p 1883 -u juan-001 -P 'Juan2026!' \
  -t 'dmj/v1/devices/juan-001/telemetry' -m '{"schema":1,"type":"position",...}' -q 1
mosquitto_sub  -h 127.0.0.1 -p 1883 -u juan-001 -P 'Juan2026!' \
  -t 'dmj/v1/devices/juan-001/ack' -q 1

#    Negativos esperados (deben fallar):
mosquitto_pub -h 127.0.0.1 -u dmj-consumer -P dmj-consumer-dev-pass \
  -t 'dmj/v1/devices/juan-001/telemetry' ...   # → "Not authorized" (consumer no publica telemetry)
mosquitto_pub -h 127.0.0.1 -u juan-001 -P 'Juan2026!' \
  -t 'dmj/v1/devices/otro/telemetry' ...       # → "Not authorized" (solo su propio deviceId)
mosquitto_sub -h 127.0.0.1 -u juan-001 -P 'Juan2026!' \
  -t 'dmj/v1/devices/+/telemetry' ...          # → "Not authorized" (sin wildcards para móviles)
#    Anónimo / password incorrecto:
mosquitto_pub -h 127.0.0.1 ... -t 'dmj/v1/devices/demo/telemetry'  # → "Not authorized" (no autentica)

# 5) Load-tests con credenciales (username = deviceId por la ACL):
MQTT_USER=juan-001 MQTT_PASSWORD='Juan2026!' MQTT_DEVICE_ID=juan-001 npm run mqtt:e2e
#    mqtt:baseline usa IDs falsos load-N: necesita un usuario por ID simulado
#    (mqtt-users.sh add load-1 ...) — ver infrastructure/load-tests/README.md

# 6) TLS (prod): conectar al 8883 con cert de la CA de confianza
mosquitto_pub --cafile /path/ca.pem -h mqtt.dmujeres.example -p 8883 -u juan-001 -P ... -t '...' -m '...'
```

Referencias oficiales:
- AuthN built-in DB (bootstrap): https://docs.emqx.com/en/emqx/latest/access-control/authn/mnesia.html
- AuthZ file (ACL): https://docs.emqx.com/en/emqx/latest/access-control/authz/file.html
- TLS listeners: https://docs.emqx.com/en/emqx/latest/network/emqx-mqtt-tls.html
