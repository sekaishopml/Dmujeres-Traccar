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