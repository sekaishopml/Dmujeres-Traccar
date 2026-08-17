# CURRENT_TASK — DMujeres Traccar Platform

> Archivo de continuidad. Última actualización: 2026-08-17 — build 1.0.29 en entorno limpio.
> Leer junto a PROJECT_CONTEXT.md, ROADMAP.md, TEST_STATUS.md.

## 2026-08-17 — Build 1.0.29 desde entorno limpio

- Servidor Traccar 6.14.5 compilado (Gradle 9.5.1 / JDK 21) y arrancado:
  health `/api/health` HTTP 200, dashboard servido en `:8082`, consumer MQTT conectado.
- Infraestructura Docker Compose up: TimescaleDB (pg17), Redis, EMQX 5.8.5 — health OK.
- Dashboard compilado con Vite 8; fix de build rolldown (binding nativo linux-x64-gnu).
- App Android 1.0.29 (versionCode 30) compilada con SDK 35: `app-debug.apk` (6.9 MB).
- `latest.json` actualizado → 1.0.29. Release v1.0.29 publicado en GitHub con el APK.
- Detalle completo: CHANGELOG.md (2026-08-17).

## 2026-08-17 — App 1.0.30: fix permiso de ubicación "siempre"

- Corregido el onboarding: el botón "Permitir ubicación (siempre)" no hacía nada en
  Android 10+ porque `ACCESS_BACKGROUND_LOCATION` no puede pedirse junto con los
  permisos de primer plano. Ahora se pide en dos fases (primero plano → fondo) y si ya
  se rechazó antes se abre directamente los ajustes de la app.
- APK 1.0.30 (versionCode 31) compilado; latest.json → 1.0.30; release v1.0.30 publicado.

## 2026-08-17 — App 1.0.31: servidor por defecto al entorno actual

- `AppConfig.DEFAULT_SERVER` → `tcp://68.168.20.219:1883` (IP pública actual).
- APK 1.0.31 (versionCode 32) compilado; latest.json → 1.0.31; release v1.0.31 publicado.

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
MapProvider con default OpenFreeMap, keys hardcodeadas eliminadas).

Optimización de datos (D-014): compresión TimescaleDB activa y medida (5.19x, consultas
2-4ms); se conserva TODO el histórico (sin borrado). Proyección: ~134 dispositivos @10s
caben en 100GB/5 años.

Pendiente: FASE 5 (hardening/producción) cuando el usuario la autorice.

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
