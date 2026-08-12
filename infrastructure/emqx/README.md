# EMQX auth/acl — acceso MQTT móvil (EMQX 5.8)

Archivos de configuración de acceso del broker (`emqx/emqx:5.8.5`) para el canal
móvil `dmj/v1/devices/...`. Documentación completa de operación y seguridad:
`docs/mqtt/emqx-security.md`.

| Archivo | Rol |
| --- | --- |
| `emqx.conf` | Template HOCON (authN `password_based`/built_in_database + authZ file, `no_match=deny`). En compose se aplica vía env vars `EMQX_*` (equivalentes documentadas al final del archivo). |
| `auth-file.csv` | Bootstrap de usuarios (CSV). Credenciales de EJEMPLO solo dev (ver abajo). |
| `acl-file.conf` | Reglas ACL (Erlang-term). `{deny, all}` final + `no_match=deny`. |

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
- Dev (solo local, bind 127.0.0.1): `dmj-consumer / dmj-consumer-dev-pass` y
  `dmj-device-demo / dmj-device-demo-dev-pass`.
- Producción: generar con `mqtt-users.sh` y montar un archivo NO versionado.
