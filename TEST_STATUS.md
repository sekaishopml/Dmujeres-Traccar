# TEST STATUS — DMujeres Traccar Platform

> Estado de pruebas con evidencia. Nunca marcar "verificado" sin evidencia.
> Última actualización: 2026-08-12 (Fase 0 completada).

## FASE 0

| ID | Prueba | Estado | Evidencia |
|---|---|---|---|
| PT-001 | JDK 21 instalado y funcional | ✔ PASÓ | `java -version` → OpenJDK 21.0.11 |
| PT-002 | Clones upstream íntegros (server+web) | ✔ PASÓ | `git log`: v6.14.5 (5c5e710 / 695a473) |
| PT-003 | Build server Gradle (tests + checkstyle + jar) | ✔ PASÓ | BUILD SUCCESSFUL; **597 tests, 0 fallos, 0 errores** (27 skipped, 424 suites); target/tracker-server.jar |
| PT-004 | Arranque server + `/api/health` | ✔ PASÓ | HTTP 200 "OK"; Jetty 12.1.8 en 0.0.0.0:8082 |
| PT-005 | Migraciones Liquibase sobre PostgreSQL/TimescaleDB | ✔ PASÓ | 33 changesets aplicados en BD limpia; log "Update command completed successfully" |
| PT-006 | Build dashboard Vite | ✔ PASÓ | vite build OK; 238 entradas PWA precacheadas; build/index.html |
| PT-007 | Server sirve dashboard (E2E minimal) | ✔ PASÓ | `GET /` → HTTP 200 (web.path → dashboard/build) |
| PT-008 | Compose infraestructura levanta (PG/Redis/MQTT) | ✔ PASÓ | dmj-db/dmj-redis/dmj-mqtt `healthy`; EMQX 5.8.5 |
| PT-009 | Recuperación en entorno limpio (backup/restore) | ✔ PASÓ | `down -v` (volúmenes destruidos) → `up` → `restore.sh` → 1 usuario, 1 dispositivo, 1 posición, 33 changelogs → server: health 200, login 200, posición visible |
| PT-010 | Pipeline GPS E2E (registro→login→device→posición) | ✔ PASÓ | POST /api/users (admin auto), /api/session 200, /api/devices 200, OsmAnd `5055/?id=demo-001` → tc_positions fila OK + REST /api/positions 200 |
| PT-011 | TimescaleDB hipertablas activas | ✔ PASÓ | `tc_positions`, `tc_events`, `tc_actions` hypertables (timescaledb 2.29.1) |
| PT-012 | Backup verificado post-generación | ✔ PASÓ | `pg_restore --list` OK en backup.sh; artefactos: dump + env(600) + conf(600) + VERSIONS.txt |

## Hallazgos y correcciones registradas

1. **Bug upstream menor** (documentado): `POST /api/users` sin sesión con `administrator:true`
   → NPE en `PermissionsService.checkAdmin`. Flujo correcto: omitir el flag (el primer
   usuario recibe admin automáticamente, UserResource.add L102-127).
2. **Bug propio corregido**: `dev.sh down` no reenviaba argumentos (`-v` se perdía) → los
   volúmenes sobrevivían y un password rotado no tomaba efecto. Corregido con `shift`.
3. **Entorno**: puerto 5432 ocupado por otro proyecto (sekai-dev-db) → dev usa 5433 externo.

## Fases 1-5
- Sin pruebas definidas todavía (no iniciadas).
- Fase 2: carga con 1/10/100/1000 dispositivos (tool `test-generator.py` upstream) + métricas.