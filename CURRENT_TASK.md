# CURRENT_TASK — DMujeres Traccar Platform

> Archivo de continuidad. Última actualización: FASE 0, build infraestructura.
> Leer junto a PROJECT_CONTEXT.md, ROADMAP.md, TEST_STATUS.md.

## Tarea actual

**T-005: Infraestructura Docker dev (TimescaleDB, Redis, MQTT broker) + .env.example**

### En progreso
- [x] T-000 Auditoría (entorno + server + web) — completada, ver ARCHITECTURE.md
- [x] T-001 Monorepo + ramas `dev` (server/, dashboard/, docs/, infrastructure/)
- [x] T-002 Documentación fundacional (7 archivos)
- [x] T-003 JDK 21 instalado (apt, openjdk-21.0.11)
- [x] T-004 Clones completos @ v6.14.5 (master=upstream, dev=fork)
- [ ] T-005 docker-compose dev + .env.example + scripts
- [ ] T-006 Build del server (./gradlew build) + evidencia
- [ ] T-007 Config server → PostgreSQL + arranque + healthcheck
- [ ] T-008 Build dashboard (npm ci + build) + servido por server
- [ ] T-009 Prueba E2E Fase 0 + TEST_STATUS + commit

### Bloqueos
- Ninguno por el momento.

### Pendiente de decisión humana (no bloqueante aún)
- Nombre/dominio público del producto (necesario en despliegue, Fase 5).