# DMujeres Traccar

![Logo](logo.png)

Sistema de tracking GPS para la empresa Dmujeres. Fork de Traccar v6.14.5.

## Qué tiene

- Server en Java con MQTT para que la app reporte ubicación
- Dashboard web (React) para ver los dispositivos en el mapa
- App Android que manda GPS aunque la pantalla esté apagada
- Docker Compose para levantar todo (TimescaleDB, Redis, EMQX)

## Estado actual (2026-09)

- App **1.1.3** (versionCode 113), firmada con clave de release, OTA en
  `dashboard/build/latest.json` (ver `docs/PRODUCTION_RUNBOOK.md`).
- Salud de flota E2E: la app sube snapshots a `tc_device_health` y el
  dashboard tiene la página **/reports/dmujeres** (flota + continuidad).
- Continuidad de jornada con denominadores explícitos
  (`GET /api/devices/{id}/continuity`): `docs/TRACKING_RELIABILITY.md`.
- Auditoría final y pendientes reales: `docs/FINAL_PRODUCTION_AUDIT.md`.
- Seguridad (rotación de clave, NSC, keystore): `docs/SECURITY.md`.
- OEM/ZTE cfreezer: `docs/OEM_COMPATIBILITY.md`; recovery:
  `docs/RECOVERY.md`; diagnóstico: `docs/INCIDENT_DIAGNOSIS.md`; QA:
  `docs/QA_MATRIX.md`.

Suites: server **821/0**, mobile **549/0**, dashboard **80/0**.

## Prerrequisitos

| Herramienta | Versión mínima | Para qué |
|---|---|---|
| Docker + Docker Compose v2 (`docker compose`) | 24+ | Infra dev/prod (TimescaleDB, Redis, EMQX) |
| Java (JDK) | 21 | Compilar y correr `server` |
| Node.js + npm | 20+ | Compilar `dashboard` (React 19, Vite) |
| Android SDK (platform 34/35 + build-tools) | 34+ | Compilar `mobile` (APK) |
| `curl`, `openssl`, `python3` | — | Scripts (`mqtt-users.sh`, `backup.sh`, …) |
| `shellcheck` (opcional) | — | Lintear `infrastructure/scripts/*.sh` |

## Clonar (submódulos)

`server/` y `dashboard/` son **submódulos** (forks con historia upstream propia).
Después de clonar, inicializarlos:

```bash
git clone <monorepo>
cd DMujeres-Tracking
git submodule update --init --recursive
```

Ver ramas/remotes en [.gitmodules](.gitmodules): forks en `github.com/sekaishopml`
(rama `dev`); upstream sigue como remote `upstream` dentro de cada submódulo.

## Configuración inicial

```bash
cp .env.example .env
# Editar .env y cambiar TODOS los CHANGE_ME por secretos reales
# (POSTGRES_PASSWORD, MOBILE_MQTT_PASSWORD, MOBILE_HTTP_API_KEY,
#  WEB_SECRET_TOKEN, ENCRYPTION_KEY, DASH_ADMIN_PASSWORD, …)
```

Nunca versionar `.env` (está en `.gitignore`). Solo `.env.example` se versiona.

## Entorno de desarrollo

```bash
./infrastructure/scripts/dev.sh up
cd server && ./gradlew build
cd .. && ./infrastructure/scripts/run-server-dev.sh start
```

El dashboard queda en http://localhost:999

### Scripts (`infrastructure/scripts/`)

| Script | Uso | Qué hace |
|---|---|---|
| `dev.sh {up\|down\|restart\|logs\|status\|psql\|redis\|mqtt}` | `./infrastructure/scripts/dev.sh up` | Opera compose (`COMPOSE_FILE` de `.env`, por defecto `docker-compose.yml` dev). `psql/redis/mqtt` abren consola contra cada servicio |
| `run-server-dev.sh {start\|stop\|restart\|logs\|make-config}` | `./infrastructure/scripts/run-server-dev.sh start` | Genera `server/conf/traccar-dev.xml` desde el template, inyecta secretos por env y arranca `tracker-server.jar`; espera `/api/health` con timeout |
| `migrate.sh` | `./infrastructure/scripts/migrate.sh` | Muestra los últimos changelogs Liquibase aplicados (el server migra al arrancar) |
| `mqtt-users.sh {hash\|add\|list\|del}` | `./infrastructure/scripts/mqtt-users.sh add <user> <pass>` | Gestiona usuarios MQTT en EMQX 5.8 vía API (`hash` genera línea CSV bootstrap con sha256(salt+pass)) |
| `create-collaborator.sh <usuario> <contraseña>` | `./infrastructure/scripts/create-collaborator.sh ana pass-segura` | Alta completa: dispositivo Traccar (`uniqueId=usuario`) + usuario MQTT en EMQX. Requiere `DASH_URL/DASH_ADMIN_EMAIL/DASH_ADMIN_PASSWORD` en `.env` |
| `backup.sh [tag]` | `./infrastructure/scripts/backup.sh` | Dump `pg_dump --format=custom` + copia `.env` y `traccar-dev.xml` (600) + `VERSIONS.txt` en `$BACKUP_DIR`; verifica con `pg_restore --list`; purga por `BACKUP_RETENTION_DAYS` |
| `restore.sh <archivo.dump>` | `./infrastructure/scripts/restore.sh /var/backups/dmj/traccar-….dump` | DESTRUCTIVO: detiene server, recrea BD y carga el dump con `pg_restore --no-owner`, verifica `tc_users/tc_devices` |

## Canal móvil (MQTT + HTTP fallback)

Tópicos (configurables en `.env`):

- Subida app → server: `dmj/v1/devices/{id}/telemetry` (QoS 1)
- ACK server → app: `dmj/v1/devices/{id}/ack` (`accepted|duplicate|rejected|…`)

HTTP fallback (mismo envelope, misma idempotencia):

- `POST /api/mobile/v1/positions` (batch JSON, header `X-Api-Key`)
- `POST /api/mobile/provision` (solo admin; crea dispositivo + usuario MQTT)

Detalles del envelope (`schema:1`, tipos `position|presence|ack`) en
[`server/openapi.yaml`](server/openapi.yaml) y manual de jornada en
[`docs/MANUAL-JORNADA.md`](docs/MANUAL-JORNADA.md).

## Backup / restore

```bash
./infrastructure/scripts/backup.sh            # snapshot en $BACKUP_DIR (def. /var/backups/dmj)
./infrastructure/scripts/restore.sh <dump>    # restaura (pide confirmación, destruye datos actuales)
```

Qué se guarda: dump custom de PostgreSQL, `.env`, `server/conf/traccar-dev.xml`
y versiones de los submódulos. Retención: `BACKUP_RETENTION_DAYS` (def. 30).

## Para compilar la app Android

Necesitás Android SDK 34/35 y JDK 21.

```bash
cd mobile
./gradlew assembleDebug
```

El APK queda en `mobile/app/build/outputs/apk/debug/app-debug.apk`

## Estructura

```
server/          server Traccar (Java 21, Gradle)
dashboard/       panel web (React 19, Vite)
mobile/          app Android (Kotlin)
infrastructure/  Docker Compose y scripts
docs/            manuales (MANUAL-JORNADA.md)
```

## Cómo funciona

La app manda posiciones por MQTT al server. El server las guarda en PostgreSQL y las manda al dashboard por WebSocket. Si no hay internet, la app guarda las posiciones en una cola local y las manda cuando se reconecta.

## Troubleshooting

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| `dev.sh up` falla: `POSTGRES_PASSWORD requerido` | Falta `.env` o variable vacía | `cp .env.example .env` y completar secretos |
| `run-server-dev.sh start`: `/api/health` nunca OK | Jar sin compilar, puerto 999 ocupado o BD caída | `cd server && ./gradlew build`; `dev.sh status`; `curl -v http://localhost:999/api/health`; `run-server-dev.sh logs` |
| App no conecta MQTT | Usuario MQTT inexistente o password desfasado vs `auth-file.csv` | `mqtt-users.sh list`; `mqtt-users.sh add <user> <pass>`; revisar `MOBILE_MQTT_PASSWORD` y ACL `infrastructure/emqx/acl-file.conf` |
| Dispositivo con miles de pendientes (caso `santiago`) / fallback HTTP inaccesible | Usuario MQTT inexistente + `mobile.http.enable=false` y/o `:999` cerrado en el VPS | Runbook exacto en [`docs/RUNBOOK-SANTIAGO.md`](docs/RUNBOOK-SANTIAGO.md): alta con `create-collaborator.sh`, verificación EMQX, apertura de `999`, logs y SQL |
| Posiciones duplicadas / `duplicate` en ACK | Normal: replay offline reenviado; el server deduplica por `sequence/messageId` | No hacer nada; verificar `tc_mobile_messages.status='accepted'` |
| `HTTP 503 Retry-After` en fallback | Pico de ingesta (pool Hikari lleno, 20 conc.) | La app reintenta con backoff; no paralelizar flushes |
| EMQX dashboard 18083 sin acceso | Bindea a `127.0.0.1` en dev; no se publica en prod | Dev: `http://127.0.0.1:18083`; prod: túnel SSH `ssh -L 18083:127.0.0.1:18083 user@host` |
| Dashboard vacío / login falla | Server caído o `WEB_SECRET_TOKEN` cambió (invalida sesiones) | Revisar logs del server; reloguear tras regenerar el token |
| Restore falla con permisos | `pg_restore --no-owner` necesita dueño correcto | Verificar `POSTGRES_USER/DB` en `.env` coinciden con el dump |

Puertos dev (todos en `127.0.0.1`): PG `5433`, Redis `6379`, MQTT `1883`,
EMQX-WS `8083`, EMQX-dash `18083`, web `999`. Ver excepción para exponer
1883 en [`infrastructure/compose/docker-compose.yml`](infrastructure/compose/docker-compose.yml).

## Licencia

Apache 2.0 (mismo que Traccar upstream).
