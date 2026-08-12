# CURRENT_TASK — DMujeres Traccar Platform

> Archivo de continuidad. Última actualización: FASE 0, build infraestructura.
> Leer junto a PROJECT_CONTEXT.md, ROADMAP.md, TEST_STATUS.md.

## Fase 0 completada — 2026-08-12

### Log de cierre
Todas las tareas T-000..T-009 completadas con evidencia (ver TEST_STATUS.md).
FASE 0 cumple su definición de completado: entorno limpio + up + restore + server = sistema funcionando.

## Siguiente tarea (FASE 2 — GPS / MQTT)

**T-020: Diseño y elección del canal de alta frecuencia** — comparativa técnica MQTT QoS1
vs WebSocket vs HTTP batching vs lo existente en Traccar (BaseMqttProtocolDecoder,
PositionForwarderMqtt). Luego: protocolo propio + ACK + deduplicación + cola offline +
reintentos, integración con TimescaleDB y WebSocket, pruebas de carga 1/10/100/1000
dispositivos con métricas (latencia, pérdida, throughput, CPU, RAM, DB writes).

FASE 1 cerrada con evidencia: suite versionada en infrastructure/tests/ (PT-101..104).

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