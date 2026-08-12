# CHANGELOG — DMujeres Traccar Platform

## 2026-08-12 — FASE 0 (auditoría y fundaciones)

### Auditado
- Entorno VPS: Ubuntu 24.04, 8c/31GB, Docker 29.1.3, Node 20.20.2, sin Java (instalado JDK 21.0.11 via apt)
- traccar/traccar v6.14.5: Java 21, Gradle 9.5.1, Jetty 12 EE10, Jersey 4, Guice 7, Netty 4.2,
  Liquibase 5.0.3, 267 protocolos, PostgreSQL driver 42.7.11, sin multi-tenancy, Apache 2.0
- traccar/traccar-web v6.14.5: React 19.2, MUI 9, RTK 2.12, MapLibre GL 5.24, Vite 8 + PWA,
  61 idiomas, sin tests, Apache 2.0

### Creado
- Monorepo `/DMujeres-Traccar`: `server/` y `dashboard/` (clones completos, rama `dev`),
  `docs/` (10 carpetas), `infrastructure/`
- Documentación: PROJECT_CONTEXT.md, ROADMAP.md, CURRENT_TASK.md, ARCHITECTURE.md,
  DECISIONS.md (10 decisiones), CHANGELOG.md, TEST_STATUS.md

### Infraestructura
- JDK 21 instalado en el VPS
- [EN PROGRESO] docker-compose dev (TimescaleDB, Redis, MQTT) + .env.example

## Pendiente en esta sesión
- Completar infraestructura Docker, build/arranque del server con PostgreSQL,
  build del dashboard, verificación E2E y primer commit de Fase 0.
## 2026-08-12 — FASE 0 completada (2ª pasada)
- Corregido por revisión de auditor: secretos fuera de conf/ (env vars + gitignore),
  puertos dev bindeados a 127.0.0.1, password BD rotado, restore robusto (drop/create),
  backup extendido (env+conf+versiones, verificación pg_restore --list), prod compose creado,
  submódulos formalizados (.gitmodules), runbook de recuperación, docs de seguridad.
- Bugs corregidos: dev.sh down sin forward de args; mapeo DATABASE_PASSWORD en launcher dev.
- PT-009 (recuperación en entorno limpio) validado con evidencia real.

## 2026-08-12 — FASE 1 completada (server baseline)

### Validado (upstream sin modificar)
- WebSocket realtime `/api/socket`: auth por token firmado, push de posiciones en tiempo real
  (PostProcessHandler→ConnectionManager), eventos deviceOnline con mensaje, keepalive 55s.
- Auth: cookie, token ECDSA (POST /api/session/token), Basic.
- CRUD completo (grupos, dispositivos, geocercas, usuarios) + aislamiento de permisos.
- Persistencia tras reinicio (posiciones, usuarios, notificaciones, eventos).

### Entregables
- Suite de integración versionada: infrastructure/tests/ (ws-test, crud-test, event-test,
  README, lockfile) — idempotente, credenciales por env, resultados registrados.
- server/conf/traccar-dev.xml.example (template sin secretos) + make-config.
- run-server-dev.sh: inyecta WEB_SECRET_TOKEN (sesiones estables entre reinicios),
  log en server/logs/, web.address=127.0.0.1 en dev.
- Hallazgo documentado: notificaciones web requieren always=true y vínculo por permisos
  para llegar por WS (CacheManager.getDeviceNotifications); keepalive 55s.

## 2026-08-12 — FASE 2.1: contrato y baseline de ingesta

- ADR-002: MQTT 5/TLS QoS1 primario + ACK de aplicación post-persistencia + HTTP batch
  fallback; WebSocket queda como salida al dashboard.
- Contrato v1 en `docs/mqtt/protocol-v1.md` con `messageId`, `deviceId`, `sequence`,
  timestamps, envelope y estados de ACK.
- Migración Liquibase `tc_mobile_messages` con deduplicación persistente y modelo
  `MobileMessage`. Validada en TimescaleDB real; el FK a `tc_positions` se descartó
  por incompatibilidad con hypertables.
- `MobileEnvelopeValidator` + tests unitarios.
- Benchmark MQTT broker-only versionado: 1/10/100/1000 dispositivos, QoS1, 0 pérdidas
  PUBACK; p99 2.1/7.3/10.8/53.6 ms. No representa todavía persistencia end-to-end.
