# ROADMAP — DMujeres Traccar Platform

Estado: **EN CURSO — FASE 0**

## FASE 0 — Auditoría y Fundaciones
Objetivo: proyecto reproducible desde cero en una máquina nueva.

- [x] Auditoría entorno (VPS, Java, Node, Docker)
- [x] Auditoría Traccar v6.14.5 (server)
- [x] Auditoría traccar-web v6.14.5 (dashboard)
- [x] Clones completos en `server/` y `dashboard/` con rama `dev`
- [x] Documentación fundacional
- [ ] Infraestructura Docker dev (timescaledb, redis, mqtt) + .env.example
- [ ] Build del server (Gradle) con evidencia
- [ ] Server arrancando con PostgreSQL + healthcheck OK
- [ ] Build del dashboard (npm/vite) + servido por server
- [ ] Prueba E2E básica (login + página principal)
- [ ] Prueba de recuperación infraestructura en entorno limpio

**Definición de completado Fase 0**: máquina nueva + `docker compose up` + `./scripts/...` → server y dashboard funcionando.

## FASE 1 — Server (baseline Traccar)
- [ ] Config oficial: PostgreSQL, migraciones Liquibase
- [ ] Autenticación, API, dispositivos, usuarios, WebSocket realtime
- [ ] Validación de que el core upstream funciona sin modificar

## FASE 2 — GPS / MQTT (canal alta frecuencia)
- [ ] Diseño y elección del canal (MQTT QoS1 vs WebSocket vs HTTP batch) con comparativa
- [ ] Protocolo propio + ACK + deduplicación + offline + reintentos
- [ ] Integración Traccar + TimescaleDB + WebSocket
- [ ] Pruebas de carga (1/10/100/1000 dispositivos simulados) y métricas

## FASE 3 — Android
- [ ] App Kotlin: foreground service, Fused Location, MQTT QoS1, Room offline queue,
      watchdog, boot receiver, notificaciones, toggle de tracking

## FASE 4 — Dashboard empresarial
- [ ] Fork profundo de traccar-web, MapProvider abstraído, empresas/equipos/reportes,
      optimización de rendimiento (virtualización, clustering, code splitting)

## FASE 5 — Hardening y Producción
- [ ] RBAC, multi-tenant validado por tests de seguridad, auditoría, backups/restore
      probados, observabilidad, despliegue, prueba de recuperación real

## Requisitos transversales
- Multi-tenancy (empresas) — NO existe en upstream, se diseñará en Fase 1/2
- MapProvider abstracto — MapLibre ya abstrae providers; formalizar en Fase 4
- Infraestructura portable — VPS reemplazable vía Docker + scripts + backups
- Dominios estables para API/dashboard/MQTT (nunca IPs en la app)