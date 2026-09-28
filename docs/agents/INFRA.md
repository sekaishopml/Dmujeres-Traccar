# DMujeres Tracking — INFRA (FASE 1, estado actual)

Fecha: 2026-09-25. Autor: agente INFRA.
Alcance: inventario y copia de configuracion/servicios/scripts sin tocar produccion.
No se detuvo, reinicio ni modifico ningun servicio. No se hicieron commits.

Evidencia y copias: `/home/DMujeres-backups/migration/20260925-205714/`
(`config/` = copias, `evidence/` = hashes, listados y fuentes; 49 archivos con
SHA-256 verificado).

## 1. Servicios systemd

| Unit | Estado | Usuario | Arranque | Notas |
|---|---|---|---|---|
| `dmj-traccar.service` | active (running) | opencode:opencode | `ExecStartPre=/DMujeres-Tracking/infrastructure/scripts/dev.sh up`; `ExecStart=/DMujeres-Tracking/infrastructure/scripts/traccar-systemd.sh`; `WorkingDirectory=/DMujeres-Tracking/server` | `Restart=always`; `Requires=docker.service`; `SuccessExitStatus=143` |
| `dmj-match.service` | active (running) | opencode | `/usr/bin/java -Xmx2g -jar /opt/matchservice/target/matchservice.jar` | GraphHopper 8; `Restart=on-failure` |
| `dmj-traccar-watchdog.service` | inactive (dead, oneshot) | root (default) | `/DMujeres-Tracking/infrastructure/scripts/traccar-watchdog.sh` | Health check a `localhost:5055` |
| `dmj-traccar-watchdog.timer` | active (waiting) | root | cada 5 min (`OnBootSec=2min`) | dispara el watchdog |

Drop-in existente: `/etc/systemd/system/dmj-traccar.service.d/10-net-bind.conf`
otorga `AmbientCapabilities=CAP_NET_BIND_SERVICE` y
`CapabilityBoundingSet=CAP_NET_BIND_SERVICE` para poder enlazar el puerto
privilegiado 999 sin root (el `setcap` del binario java se perdio tras una
actualizacion de OpenJDK el 2026-09-23).

`systemctl show dmj-traccar -p EnvironmentFiles -p Environment` no declara
ninguna variable ni `EnvironmentFile`: los secretos llegan por
`traccar-systemd.sh`, que hace `source /DMujeres-Tracking/.env` y exporta
`DATABASE_PASSWORD`, `DATABASE_USER`, `WEB_SECRET_TOKEN` y variables
`MOBILE_MQTT_*` / `MOBILE_HTTP_API_KEY`. `dmj-match.service` no usa entorno.

Copias: `config/systemd/` (units, drop-in y salida de `systemctl cat`).

## 2. Contenedores (Docker)

| Contenedor | Imagen | Puertos publicados | Estado |
|---|---|---|---|
| `dmj-db` | `timescale/timescaledb:latest-pg17` | `127.0.0.1:5433->5432` | healthy |
| `dmj-redis` | `redis:7-alpine` | `127.0.0.1:6379->6379` | healthy |
| `dmj-mqtt` | `emqx/emqx:5.8.5` | `0.0.0.0:1883->1883` (MQTT), `127.0.0.1:8083`, `127.0.0.1:18083` | healthy |
| `cyhotel-*` (4) | CyHotel | `0.0.0.0:8000-8002` | fuera de alcance DMujeres |

Definicion en `/DMujeres-Tracking/infrastructure/compose/docker-compose.yml` y
`docker-compose.prod.yml`; arranque via `infrastructure/scripts/dev.sh` (copia en
`config/containers/`). `docker inspect` de `dmj-db`, `dmj-redis` y `dmj-mqtt`
guardado en `config/containers/` con todas las variables de entorno redactadas
(`CLAVE=***`).

## 3. Puertos y bindings actuales

| Puerto | Proceso | Binding | Expuesto a Internet |
|---|---|---|---|
| 999 | Java (Traccar web/API, `web.port`) | `*:999` (0.0.0.0) | Si (ufw ALLOW Anywhere) |
| 5055 | Java (protocolo OsmAnd de la App) | `*:5055` (0.0.0.0) | Si (ufw ALLOW Anywhere) |
| 1883 | docker-proxy (EMQX MQTT) | 0.0.0.0 | Si (ufw ALLOW Anywhere) |
| 8083 / 18083 | docker-proxy (EMQX WS / dashboard) | 127.0.0.1 | No |
| 5433 | docker-proxy (PostgreSQL) | 127.0.0.1 | No |
| 6379 | docker-proxy (Redis) | 127.0.0.1 | No |
| 5088-5103 | Java (protocolos Traccar) | `*:0.0.0.0` | No (sin regla ufw; default deny) |
| 4096 | opencode (AI) | 0.0.0.0 | Si (ufw ALLOW Anywhere) |
| 8000-8002 | docker-proxy (CyHotel) | 0.0.0.0 | Si (ufw ALLOW), fuera de alcance |

`traccar-dev.xml` fija `web.address=0.0.0.0`, `web.port=999`,
`web.sessionTimeout=604800`, `web.console=true`, `filter.mock=true` y
`database.url=jdbc:postgresql://localhost:5433/traccar` (sin password en el XML:
`config.useEnvironmentVariables=true`).

## 4. Nginx y TLS

No instalado y no existe `/etc/nginx`. No hay nada escuchando en 80/443. No hay
`cloudflared` ni tunel local: la terminacion TLS del plan (Cloudflare -> Nginx)
no esta desplegada en este host. `/etc/letsencrypt` no existe y `/etc/ssl` no
tiene material propio de DMujeres. El dominio publico se sirve hoy directo
contra la IP: `dashboard/build/latest.json` publica
`http://68.168.20.219:999/...` (HTTP plano). Sin certificados copiados
(`config/tls/SIN-CERTIFICADOS.txt`).

## 5. Firewall

`ufw` activo, politica `deny (incoming)`, `allow (outgoing)`.
Reglas de entrada: 22, 4096, 8000, 8082, 1883, 999 y 5055 (TCP, v4 y v6).
`iptables -P INPUT DROP` y `FORWARD DROP`; cadenas Docker y tailscale presentes.
Riesgo conocido: los puertos publicados por Docker (`1883`) evitan parte del
filtrado de ufw salvo integracion explicita; en este host 1883 tambien esta
permitido de forma explicita. Detalle en `evidence/firewall.txt`.

## 6. Cron y timers

- `/etc/cron.d/dmj-backup` (root:root 644):
  - `0 3 * * *` opencode `/DMujeres-Tracking/infrastructure/scripts/backup.sh`
    -> `/var/log/dmj/backup.log`
  - `30 4 * * 0` opencode `/DMujeres-Tracking/infrastructure/scripts/verify-backup.sh`
    -> `/var/log/dmj/backup.log`
- Crontab de `opencode`: solo tareas de CyHotel.
- Timer `dmj-traccar-watchdog.timer` cada 5 minutos -> `/var/log/dmj/watchdog.log`.
- Backups en `/var/backups/dmj` (700, opencode).

Copias: `config/jobs/cron.d-dmj-backup`, `config/jobs/cron.txt`,
`config/jobs/systemd-timers.txt`.

## 7. Rutas clave

- Produccion: `/DMujeres-Tracking` (server, dashboard, mobile, infrastructure, .env).
- Raiz nueva: `/home/DMujeres-Tracking` (documentacion y estructura objetivo).
- Match service: `/opt/matchservice` (+ cache GraphHopper `/opt/graphhopper`).
- Secretos fuera del arbol: `/home/opencode/.config/dmujeres/secrets/`.
- Backups de produccion: `/var/backups/dmj`.
- Backups de migracion: `/home/DMujeres-backups/migration/<ts>/`.

## 8. Que falta para FASE 8 (infra)

1. Crear units nuevas `dmujeres-tracking-api.service` y
   `dmujeres-tracking-web.service` (Node 24 / estaticos) con
   `EnvironmentFile=` propio, usuario dedicado, `ProtectSystem`/`NoNewPrivileges`
   y sin `CAP_NET_BIND_SERVICE` (escuchar en puerto alto y 127.0.0.1).
2. Instalar Nginx como unico punto expuesto, con TLS (Cloudflare o Let's
   Encrypt) y proxy a API/Web; retirar la exposicion directa de 999.
3. Firewall: cerrar 999, 5055, 1883 y 4096 a Internet; permitir solo 80/443
   (o solo Cloudflare) y SSH restringido; revisar publicacion Docker.
4. Migrar secretos a `EnvironmentFile` 600 fuera del repo (o systemd
   credentials) y retirar el `source .env` de scripts.
5. Job de backup/verificacion apuntando a la raiz nueva, con rotacion y
   verificacion de restauracion (ya existe `backup.sh`/`verify-backup.sh`).
6. Access log de Nginx/Jetty para forense y `journald` con retencion definida.
7. Ensayar rollback y documentarlo en `docs/deployment/`.

## 9. Dudas

- Confirmar si el corte usara Nginx local o tunel Cloudflare, porque no hay
  ningun componente TLS en el host.
- Confirmar version objetivo de PostgreSQL (hoy TimescaleDB pg17 en `dmj-db`;
  el plan menciona PostgreSQL 18.6).
- `filter.mock=true` en produccion: confirmar si es intencional (afecta datos).
