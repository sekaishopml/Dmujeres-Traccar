# CURRENT_TASK — DMujeres Traccar Platform

> Archivo de continuidad. Última actualización: FASE 0, build infraestructura.
> Leer junto a PROJECT_CONTEXT.md, ROADMAP.md, TEST_STATUS.md.

## Fase 0 completada — 2026-08-12

### Log de cierre
Todas las tareas T-000..T-009 completadas con evidencia (ver TEST_STATUS.md).
FASE 0 cumple su definición de completado: entorno limpio + up + restore + server = sistema funcionando.

## Siguiente tarea (FASE 2.2 — consumidor MQTT)

**T-021: Consumidor MQTT embebido y pipeline común** — implementar `MobileMqttConsumer`
con HiveMQ MQTT 5, manual acknowledgement, validación del envelope, resolución de
dispositivo, persistencia idempotente y ACK de aplicación sólo después del commit.
Antes de aceptar mensajes reales debe extraerse una frontera `PositionPipeline` invocable
sin `ChannelHandlerContext`, preservando el comportamiento de los protocolos Netty.

FASE 2.1 cerrada: ADR-002, contrato v1, migración aplicada en TimescaleDB, validator
unitario y baseline MQTT broker-only versionado en `infrastructure/load-tests/`.
FASE 2.2 cerrada: consumidor con persistencia atómica posición+dedupe y lease/recovery
validados (PT-205/206); ACL/authN EMQX 5.8 con override dev validado aislado (PT-207/208);
HTTP fallback con hash canónico e idempotencia cruzada (PT-209/210); carga end-to-end
200/200 accepted (PT-211).
FASE 3: app Android MVP compilada (APK debug, SDK 34). Críticos de revisión corregidos
(re-suscripción ACK tras reconexión, backoff/techo de reintentos). Pendiente: prueba en
teléfono físico y subir targetSdk a 35 para Play Store.

Pendiente transversal: TLS real de producción con certificados y despliegue externo.

FASE 4: dashboard optimizado sin rediseño (chunk inicial 1.6MB→124KB, gzip -69%,
MapProvider con default OpenFreeMap, keys hardcodeadas eliminadas). Pendiente:
multi-tenancy/empresas (requiere trabajo de server) para reportes por empresa.

### En progreso
- [x] T-000 Auditoría (entorno + server + web) — completada, ver ARCHITECTURE.md
- [x] T-001 Monorepo + ramas `dev` (server/, dashboard/, docs/, infrastructure/)
- [x] T-002 Documentación fundacional (7 archivos)
- [x] T-003 JDK 21 instalado (apt, openjdk-21.0.11)
- [x] T-004 Clones completos @ v6.14.5 (master=upstream, dev=fork)
- [x] T-005 docker-compose dev + .env.example + scripts
- [x] T-006 Build del server (./gradlew build) + evidencia
- [x] T-007 Config server → PostgreSQL + arranque + healthcheck
- [x] T-008 Build dashboard (npm ci + build) + servido por server
- [x] T-009 Prueba E2E Fase 0 + TEST_STATUS + commit

### Bloqueos
- Ninguno por el momento.

### Pendiente de decisión humana (no bloqueante aún)
- Nombre/dominio público del producto (necesario en despliegue, Fase 5).
