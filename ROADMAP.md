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
- [x] Transacción JDBC posición+dedupe y lease/recovery (`leaseuntil`/`leasetoken`/`attempts`)
- [x] Templates ACL/authN/TLS EMQX 5.8 + override dev `docker-compose.emqx-auth.yml` (validado aislado)
- [x] HTTP fallback batch `/api/mobile/v1/positions` con hash canónico e idempotencia cruzada
- [x] Carga end-to-end MQTT 200/200 accepted (PT-211)
- [ ] TLS real de producción (certs por .env) en un despliegue externo
- [ ] Protocolo propio + ACK + deduplicación + offline + reintentos
- [ ] Integración Traccar + TimescaleDB + WebSocket
- [ ] Pruebas de carga (1/10/100/1000 dispositivos simulados) y métricas

## FASE 3 — Android ✔ EN CURSO (MVP compilado 2026-08-12)
- [x] App Kotlin: foreground service (tipo location), Fused Location Provider, MQTT QoS1
      con ACK de aplicación, Room offline queue con backoff exponencial, watchdog con
      estados, boot receiver, notificaciones, toggle de tracking (user opt-in)
- [x] APK debug compilado (SDK 34) y revisión técnica (críticos corregidos)
- [ ] targetSdk 35 antes de Play Store
- [ ] Prueba en teléfono físico (GPS/MQTT reales)

## FASE 4 — Dashboard (optimización sin rediseño) ✔ EN CURSO 2026-08-12
- [x] División de vendors: carga inicial 1.6MB→124KB, total JS 7.0→6.8MB, 227→158 chunks
- [x] Compresión gzip en server: transferencia JS+CSS -69% (6.6MB→2.0MB)
- [x] MapProvider abstraído (default OpenFreeMap); Google sin key ya no usa tiles no oficiales
- [x] API keys hardcodeadas eliminadas (LocationIQ/OrdnanceSurvey, ahora por configuración)
- [x] Sin regresión: WS/CRUD 10/10 + 10/10
- [x] (Decisión D-012) SIN empresas/multi-tenancy: entorno privado de una sola empresa

## Optimización de datos (D-014) ✔ COMPLETADA 2026-08-13
- [x] Compresión TimescaleDB en tc_positions (chunk mensual, comprimir >1 día, sin borrado)
- [x] Medición real: ratio 5.19x (80.7%), consultas 2-4ms; scripts versionados
- [x] Proyección 5 años documentada: 10 dispositivos @10s ≈ 7.5GB (hasta ~134 @10s en 100GB)
- [ ] Ajustar intervalo de la app (30s/60s) si la flota crece (decisión de negocio)

## FASE 5 — Hardening y Producción (entorno privado de UNA empresa)
- [ ] Seguridad de acceso: TLS/HTTPS, auth fuerte, firewall/VPN, mínima exposición pública
- [ ] RBAC plano validado (admin → usuarios/grupos) con tests de seguridad
- [ ] Backups/restore probados y recuperación real en máquina limpia
- [ ] Observabilidad: logs, métricas, health checks, alertas
- [ ] Despliegue reproducible (dominios estables, certs por .env) y prueba de recuperación

## Requisitos transversales
- Multi-tenancy: **DESCARTADO** (D-012) — plataforma privada y confidencial de una sola empresa
- MapProvider abstracto — MapLibre ya abstrae providers; formalizado en Fase 4
- Infraestructura portable — VPS reemplazable vía Docker + scripts + backups
- Dominios estables para API/dashboard/MQTT (nunca IPs en la app)
