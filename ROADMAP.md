# ROADMAP — DMujeres Traccar Platform

Estado: **FASE 2.1 COMPLETADA — siguiente: consumidor MQTT y pipeline común**

## FASE 0 — Auditoría y Fundaciones
Objetivo: proyecto reproducible desde cero en una máquina nueva.

- [x] Auditoría entorno (VPS, Java, Node, Docker)
- [x] Auditoría Traccar v6.14.5 (server)
- [x] Auditoría traccar-web v6.14.5 (dashboard)
- [x] Clones completos en `server/` y `dashboard/` con rama `dev`
- [x] Documentación fundacional
- [ ] Infraestructura Docker dev (timescaledb, redis, mqtt) + .env.example
- [x] Build del server (Gradle) con evidencia (597 tests OK)
- [x] Server arrancando con PostgreSQL/TimescaleDB + healthcheck OK
- [x] Build del dashboard (npm/vite) + servido por server
- [x] Prueba E2E básica (login + dispositivo + posición)
- [x] Prueba de recuperación infraestructura en entorno limpio (PT-009)

**Definición de completado Fase 0**: máquina nueva + `docker compose up` + `./scripts/...` → server y dashboard funcionando.

## FASE 1 — Server (baseline Traccar) ✔ COMPLETADA 2026-08-12
- [x] Config oficial: PostgreSQL, migraciones Liquibase (PT-005, 33 changesets)
- [x] Autenticación (cookie/token/Basic) + API + dispositivos + usuarios (PT-102/103)
- [x] WebSocket realtime: posiciones y eventos en tiempo real (PT-101/104a)
- [x] Persistencia tras reinicio (PT-104b)
- [x] Evidencia ejecutable versionada en infrastructure/tests/ (10/10 + 10/10 + eventos)
- [x] Config dev generada desde template sin secretos; token de firma estable (WEB_SECRET_TOKEN)

## FASE 2 — GPS / MQTT (canal alta frecuencia)
- [x] Diseño y elección del canal (MQTT QoS1 vs WebSocket vs HTTP batch) con comparativa
- [x] ADR-002 + contrato `docs/mqtt/protocol-v1.md`
- [x] Tabla `tc_mobile_messages` para deduplicación `(deviceid, sequence)` + `messageid`
- [x] Validación envelope v1 con tests unitarios
- [x] Baseline MQTT broker-only 1/10/100/1000 (PUBACK; no equivale a persistencia)
- [x] Consumidor MQTT embebido experimental con ACK de aplicación posterior a persistencia
- [x] E2E experimental `accepted`/`duplicate` con una posición física
- [x] Pipeline común invocable sin `ChannelHandlerContext` (refactor + manejo excepcional)
- [ ] Endpoint HTTP batch con envelope/idempotencia compartidos
- [ ] Lease/recovery para mensajes `processing`
- [ ] Transacción JDBC posición+dedupe
- [ ] TLS, autenticación y ACL de EMQX para producción
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
