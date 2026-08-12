# Runbook de despliegue y recuperación — DMujeres Traccar Platform

## Despliegue dev (VPS actual)

```bash
cd /DMujeres-Traccar
cp .env.example .env          # y ajustar todos los CHANGE_ME_* (generar: openssl rand -hex 32)
./infrastructure/scripts/dev.sh up          # TimescaleDB, Redis, EMQX
./infrastructure/scripts/run-server-dev.sh  # build del server + arranque (java -jar)
# dashboard: cd dashboard && npm ci && npm run build
```

## Migrar de VPS#1 a VPS#2 (objetivo de la plataforma)

1. **En VPS#1**:
   ```bash
   ./infrastructure/scripts/backup.sh pre-migracion
   ```
   Produce: `traccar-<tag>.dump` + `restore/` (env, traccar-dev.xml, VERSIONS.txt).

2. **Copiar** a VPS#2 (scp/rsync, canal seguro).

3. **En VPS#2** (máquina limpia):
   ```bash
   git clone <repo-de-la-plataforma> && cd <plataforma>
   # (si el monorepo usa submódulos) git submodule update --init --recursive
   cp /ruta/restore/env .env                 # secretos originales
   chmod 600 .env
   ./infrastructure/scripts/dev.sh up        # o compose prod
   ./infrastructure/scripts/restore.sh /ruta/traccar-pre-migracion.dump
   ./infrastructure/scripts/run-server-dev.sh  # (dev) — en prod: docker compose up traccar
   ```

4. **Verificar**: `curl http://<dominio>/api/health` → `OK`; login en el dashboard;
   consultar posiciones del histórico.

## Contenido del backup

| Artefacto | Qué cubre |
|---|---|
| `traccar-<tag>.dump` | Datos BD (custom pg_dump; incluye hipertablas TimescaleDB) |
| `restore/env` | Secretos (BD, Redis, MQTT, tokens) — permisos 600 |
| `restore/traccar-dev.xml` | Config del server (sin secretos; se inyectan por env) |
| `restore/VERSIONS.txt` | Commits exactos de server y dashboard (reproducibilidad) |

**Nota**: el código (server/dashboard builds) NO va en el backup — se reconstruye
desde git en el VPS nuevo (`npm ci && npm run build` + `./gradlew build`).
La BD y los secretos SÍ se restauran tal cual.

## Prueba de recuperación (PT-009) — evidencia 2026-08-12

- `docker compose down -v` (simula VPS nuevo: volúmenes destruidos)
- `docker compose up -d` (initdb fresco)
- `restore.sh traccar-fase0-test.dump`
- Resultado: 1 usuario, 1 dispositivo, 1 posición, 33 changelogs Liquibase
- `run-server-dev.sh` → `/api/health` OK, login OK, posiciones visibles por API

## Retención y política

- `BACKUP_RETENTION_DAYS=30` (por defecto)
- Restaurar al menos 1 vez al mes en un entorno de prueba (no solo producción)
- Cron sugerido: `0 3 * * * /DMujeres-Traccar/infrastructure/scripts/backup.sh`
