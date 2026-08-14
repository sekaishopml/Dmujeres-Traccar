# EMQX auth/acl — acceso MQTT móvil (EMQX 5.8)

Archivos de configuración de acceso del broker (`emqx/emqx:5.8.5`) para el canal
móvil `dmj/v1/devices/...`. La autenticación y la ACL están **integradas de forma
permanente en el compose dev** (`infrastructure/compose/docker-compose.yml`), que
monta estos archivos y aplica la configuración vía variables de entorno `EMQX_*`.
Documentación completa de operación y seguridad: `docs/mqtt/emqx-security.md`.

| Archivo | Rol |
| --- | --- |
| `emqx.conf` | Template HOCON (authN `password_based`/built_in_database + authZ file, `no_match=deny`). En compose se aplica vía env vars `EMQX_*` (equivalentes documentadas al final del archivo). |
| `auth-file.csv` | Bootstrap de usuarios (CSV). **SOLO dev** — nunca versionar credenciales reales (ver abajo). |
| `acl-file.conf` | Reglas ACL (Erlang-term). `{deny, all}` final + `no_match=deny`. |

## Modelo de acceso (el username ES el deviceId)

- `dmj-consumer` (cliente MQTT del server): SUSCRIBE `dmj/v1/devices/+/telemetry`,
  PUBLISH `dmj/v1/devices/+/ack`.
- Cualquier móvil `${username}`: PUBLISH `dmj/v1/devices/${username}/telemetry`,
  SUSCRIBE `dmj/v1/devices/${username}/ack`. Sin wildcards para móviles.
- Todo lo demás denegado (`no_match=deny` + `{deny, all}`).

## auth-file.csv — restricciones del parser EMQX

El importador de EMQX 5.8 (`emqx_utils_stream:csv`) **no soporta comentarios ni
líneas en blanco**: la primera línea del archivo es la cabecera y cada línea debe
tener exactamente 4 campos separados por `,` (y sin espacios, porque también divide
por espacios).

```csv
user_id,password_hash,salt,is_superuser
<user_id>,<sha256(salt++password)>,<salt 32hex>,false
```

- `hash = sha256(salt ++ password)` con `salt_position = prefix` (ver `emqx.conf`).
- Generar línea: `./infrastructure/scripts/mqtt-users.sh hash '<password>'`.
- **Credenciales SOLO dev** (versionadas; cambiarlas no rompe nada en dev):

| user_id | password | Rol |
| --- | --- | --- |
| `dmj-consumer` | `dmj-consumer-dev-pass` | Cliente MQTT del server (igual en `.env.example`: `MOBILE_MQTT_USERNAME`/`MOBILE_MQTT_PASSWORD`) |
| `juan-001` | `Juan2026!` | Dispositivo móvil de prueba (para la app: usuario = deviceId) |

- Producción: generar credenciales reales con `mqtt-users.sh` (hash o add) y montar
  un archivo NO versionado (`EMQX_AUTH_FILE`, ver `docker-compose.prod.yml` y
  `.env.example`).
