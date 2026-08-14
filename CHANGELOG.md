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

## 2026-08-14 — App 1.0.3: lista para el colaborador

- ID de dispositivo AUTOGENERADO (estable por teléfono) + botón copiar; el administrador
  solo lo agrega al panel.
- Servidor preconfigurado: mqtt://64.176.219.221:1883 (el colaborador no configura nada).
- Asistente de primeros pasos al instalar: ubicación, notificaciones, batería (sin límite)
  y GPS — todo lo necesario para funcionar siempre.
- Panel de configuración completo: frecuencia (3-300 s), buffer máx (10-5000), servidor,
  credenciales opcionales.
- Ultra-resistencia: reconexión MQTT agresiva (10 s), onTaskRemoved + WorkManager de
  recuperación cada 15 min, buffer con descarte del más antiguo, timeout de ACK y máximo
  de reintentos configurables.
- Puerto MQTT 1883 abierto al exterior (bind 0.0.0.0 + ufw); server con consumidor MQTT
  activo por defecto. Verificado el ciclo completo por IP pública (accepted).
- Riesgo documentado: MQTT sin autenticación hasta Fase 5 (ver docs).

## 2026-08-14 — App 1.0.4: acceso por usuario+contraseña + fix ONLINE

- NUEVO modelo de identidad: el colaborador ingresa usuario+contraseña creados por el
  administrador; el usuario ES el dispositivo (topic/envelope/uniqueId). Se elimina el ID
  autogenerado. Cero pasos para el colaborador.
- EMQX: autenticación y ACL permanentes (integrado en el compose dev; anónimo rechazado).
  ACL: cada móvil solo publica/suscribe en sus propios topics; server con dmj-consumer.
- `scripts/create-collaborator.sh <usuario> <pass>`: crea el dispositivo en Traccar y el
  usuario MQTT de una vez (el administrador entrega los datos al colaborador).
- FIX (raíz del "fuera de línea"): el canal móvil ahora marca el dispositivo ONLINE con
  hora actual al aceptar una posición (antes solo el canal Netty lo hacía); el offline por
  timeout automático de Traccar sigue funcionando.
- E2E completo con evidencia (infrastructure/database/collaborator-e2e-evidence.md):
  crear colaborador, 3 posiciones accepted, dedupe (duplicate), ACL deniega topics ajenos,
  dispositivo online en panel.

## 2026-08-14 — App 1.0.5: provisión desde Dispositivos + ONLINE

- El formulario de Dispositivos incorpora **Acceso del colaborador**: usuario, contraseña,
  frecuencia y buffer. Botón "Crear acceso y dispositivo" llama a `/api/mobile/provision`.
- Endpoint admin de provisión: crea dispositivo Traccar + usuario MQTT EMQX; nunca guarda ni
  devuelve la contraseña en atributos/GET. Guarda solo preferencias no sensibles.
- App 1.0.5: el colaborador ingresa usuario+contraseña; desaparece el requisito de ID
  autogenerado. Servidor sigue preconfigurado `mqtt://64.176.219.221:1883`.
- Fix H-1 validado: posición móvil aceptada marca el dispositivo `online` y actualiza
  `lastUpdate`; `panel-maria` E2E confirmado online en el dashboard.

## 2026-08-14 — App 1.0.6: estado de conexión y alta limpia desde Dispositivos

- La app muestra explícitamente `Conectando...`, `Conectado al servidor` o `Sin conexión`
  en la notificación/estado (antes mostraba Tracking activo aunque MQTT hubiera fallado).
- El dashboard ya no muestra el bloque **Obligatorio** (Nombre/Identificador); el alta se
  realiza únicamente desde **Acceso del colaborador**, como pidió el usuario.
- El panel crea dispositivo + usuario MQTT + preferencias sin exponer la contraseña.
- E2E verificado: `panel-maria` → MQTT autenticado → ACK accepted → dashboard ONLINE.

## 2026-08-14 — App 1.0.7: tolerancia de reloj y messageId únicos

- Server: tolerancia a reloj del teléfono adelantado (de 5 min a 24 h). Antes, un teléfono
  con hora adelantada era rechazado (invalid) y quedaba 'fuera de línea' aunque conectara.
- App: messageId único por posición (hora+secuencia+dispositivo), estable en reintentos.
- Verificado: posición con reloj +10 min → accepted; dispositivo test → ONLINE.
