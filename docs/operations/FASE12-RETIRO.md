# FASE 12 — Retiro del legado (COMPLETADA)

Apagado y limpieza de la instalación vieja (`/DMujeres-Tracking`) tras
asentarse el corte del 2026-09-26 22:02. Ejecutada con autorización del dueño
el 2026-09-27. El rollback vive en la copia fría de `legado-final/`.

## Estado final

| Componente | Estado |
|---|---|
| `dmj-traccar`, watchdog, `dmj-match` | units eliminadas; autostart ya estaba desactivado |
| `dmj-db`, `dmj-redis`, `dmj-mqtt` | contenedores, volúmenes e imágenes eliminados |
| Cron `/etc/cron.d/dmj-backup` | eliminado |
| Árbol `/DMujeres-Tracking` (4,6 GB) | eliminado |
| `/opt/matchservice` (26 MB) | eliminado |
| `/var/backups/dmj` (36 MB) | eliminado |
| Imagen `timescale/timescaledb:latest-pg17` (2,01 GB) | eliminada |

Disco: 94% (5,2 GB libres) → **71% (24 GB libres)**.

## Ejecutado

### 12a (2026-09-27) — neutralizar el riesgo de reinicio

`dmj-traccar.service` y `dmj-traccar-watchdog.timer` seguían `enabled`: un
reinicio habría levantado el Traccar viejo (pelea por 5055/999) y el watchdog
podía reiniciarlo al no responder el receptor nuevo. Quedaron `disabled` sin
tocar el rollback manual.

### 12b (2026-09-27) — apagado

1. `systemctl disable --now dmj-match`.
2. Cron `/etc/cron.d/dmj-backup` eliminado.
3. `docker stop dmj-db dmj-redis dmj-mqtt`.
4. Verificado: puertos 5433/6379/1883/8991 cerrados, 0 contenedores `dmj-`
   corriendo, `dmt-db` y servicios nuevos intactos, **E2E 24/24 PASS** (el paso
   de conteos se adaptó: compara con `tc_positions` solo si el legado corre;
   si no, exige posiciones en la base nueva).

### 12c (2026-09-27) — limpieza final

1. Contenedores, volúmenes (`compose_dmj-pgdata/redisdata/mqttdata`) e
   imágenes (`pg17`, `redis:7-alpine`, `emqx:5.8.5`) eliminados.
2. Árbol, `/opt/matchservice`, backups viejos, logs viejos y units eliminados
   (`daemon-reload`).
3. Verificado: nada del legado existe ni escucha; **E2E 24/24 PASS**.

## Evidencia (`/home/DMujeres-backups/legado-final/`)

- `traccar-final-20260927-013004.dump` (4,2 MB, `pg_restore --list` OK, SHA-256).
- `DMujeres-Tracking-20260927-013147.tar.gz` (3,2 GB, 107.207 entradas, SHA-256).
- `pre-12b/`: cron, units de systemd (incluye drop-in `10-net-bind.conf`).
- `matchservice/` (26 MB), `var-backups-dmj/` (36 MB), `var-log-dmj/`.

Rollback (si hiciera falta volver al panel viejo): restaurar el tar y el dump,
reinstalar units/cron desde `pre-12b/` y `systemctl enable --now dmj-traccar
dmj-traccar-watchdog.timer dmj-match`; la base se recrea con el dump final.
