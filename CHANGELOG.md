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

## 2026-08-12 — FASE 2.2 parcial: consumidor MQTT experimental

- Consumidor HiveMQ MQTT 5 desactivado por defecto; QoS1, manual acknowledgement,
  cola acotada, serialización por dispositivo, validación de topic/envelope y ACK de
  aplicación posterior a `PositionPipeline`.
- E2E local: `accepted` con posición `dmj-mqtt` persistida; redelivery idéntica `duplicate`
  sin segunda posición física. Velocidad km/h convertida a nudos.
- Refactor `PositionPipeline` invocable sin `ChannelHandlerContext`, preservando el
  adapter Netty y liberando colas ante excepciones.
- Limitaciones explícitas: no atomicidad JDBC posición+dedupe, no lease/recovery automático
  de `processing`, EMQX dev anónimo y sin ACL/TLS de producción. Este consumidor no se
  considera listo para producción.

## 2026-08-12 — FASE 2.2: atomicidad, lease y seguridad MQTT

- `MobileAtomicPersistence`: posición + dedupe en una transacción JDBC (QueryBuilder puede
  reutilizar una conexión sin cerrarla). Crash antes del commit revierte; después deja
  `accepted` para redelivery `duplicate`.
- Lease/recovery: columnas `leaseuntil`, `leasetoken`, `attempts` (changelog 6.14.1/6.14.2)
  y reclamación de reservas vencidas. Validado: attempts 5→6 con lease expirado.
- EMQX 5.8: templates de authN (`built_in_database`+`bootstrap_file`) y ACL por archivo,
  override dev `docker-compose.emqx-auth.yml`, `mqtt-users.sh` vía API, docs de seguridad.
  Detectadas incompatibilidades 5.x: `EMQX_ALLOW_ANONYMOUS` es no-op; no existe `backend=file`;
  no hay `emqx ctl` para usuarios.
- Pendiente Fase 2.3+: HTTP fallback, TLS real de producción, carga end-to-end.

## 2026-08-12 — FASE 2.2: HTTP fallback, hash canónico y carga end-to-end

- `MobileIngestionService` compartido: MQTT y HTTP usan la misma validación, reserva,
  lease y persistencia atómica.
- `MobileHttpResource` (`POST /api/mobile/v1/positions`, X-Api-Key): batch con el mismo
  envelope; `accepted`/`duplicate`/`rejected`/`invalid`/`expired` y 503 con `error` para
  reintento.
- **Corrección de idempotencia**: el hash de deduplicación pasó a ser canónico (orden de
  campos del contrato) en vez de bytes crudos del transporte, que rompía la dedup cruzada
  MQTT↔HTTP. Verificado: aceptado por MQTT → HTTP `duplicate`.
- Carga end-to-end: 20 dispositivos × 10 mensajes = 200/200 `accepted`, 0 pérdidas,
  0 duplicados (~124 msg/s con ACK de aplicación).
- Scripts de prueba: `mqtt-e2e-load.mjs`, `http-e2e.mjs` (idempotentes con nonce).

## 2026-08-12 — FASE 3: app Android MVP

- App Kotlin `com.dmujeres.traccar` (minSdk 26 / targetSdk 34): foreground service de
  ubicación, Fused Location Provider, MQTT Paho QoS1 con ACK de aplicación, cola offline
  Room con backoff exponencial y techo de reintentos, watchdog con estados, boot receiver
  con opt-in, notificaciones y toggle de tracking.
- Corregidos en revisión: re-suscripción al ACK tras reconexión (C1), reintentos sin
  backoff/bloqueo de cola (C2), volatilidad de estado, guard de trackingEnabled,
  try/catch de startForeground Android 14, scope recreado.
- APK debug compilado con Android SDK 34; documentación en docs/android/README.md.

## 2026-08-12 — FASE 4: dashboard optimizado sin cambiar el diseño

- Vite manualChunks: carga inicial 1.6MB→124KB; total JS 7.0→6.8MB; 227→158 chunks.
- Server: CompressionHandler de Jetty configurado (gzip) envolviendo el servlet (en
  upstream era inerte); transferencia JS+CSS -69%.
- MapProvider (`src/map/provider/MapProvider.js`) con default OpenFreeMap; Google solo con
  API key (se eliminaron tiles no oficiales de mt*.google.com); API keys hardcodeadas de
  LocationIQ/OrdnanceSurvey retiradas (ahora por configuración).
- Sin regresiones: WS/CRUD 10/10; build OK.

## 2026-08-13 — Optimización de datos: compresión TimescaleDB (D-014)

- Scripts `infrastructure/database/timescale-compression.sql` y `scripts/db-timescale.sh`
  (chunk mensual, compresión segment_by=deviceid order_by=fixtime DESC, política >1 día,
  retención desactivada: se conserva TODO).
- Mediciones reales con 2.7M filas: compresión 5.19x (80.7%), consultas 2-4ms.
- Proyección 5 años/100GB documentada (10 dispositivos @10s ≈ 7.5GB).
- Decisión D-014: sin borrado automático; histórico completo mes a mes.

## 2026-08-13 — Google Maps restaurado por defecto (D-015)

- Google Carreteras/Satélite/Híbrido disponibles siempre: con API key si existe, y sin key
  mediante los tiles clásicos `mt0-3.google.com/vt/...` (comportamiento del Traccar
  original que el cliente usa desde hace años). Google Carreteras pasa a ser el mapa por
  defecto y aparece primero en el selector de capas.
- Verificado: endpoints mt0-3 responden 200 desde el VPS; build OK; dashboard 200.
