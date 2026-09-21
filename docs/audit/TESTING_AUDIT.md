# TESTING_AUDIT.md — Auditoría de testing R3 (Agente H, 2026-09-17)

> Solo lectura de código/tests. Las suites se EJECUTARON hoy para verificar (sin modificarlas).
> Alcance: `/DMujeres-Tracking` (mobile + server + dashboard). Sin commits.

## 0. Ejecución verificada hoy

| Suite | Comando | Resultado |
|---|---|---|
| Mobile unit (JVM) | `./gradlew :app:testDebugUnitTest -q` | **588 tests / 0 fallos** (83 archivos `*Test.kt` + 1 `testutil`) |
| Server (JVM) | `./gradlew test -q` (cache) | **821 / 0 / 29 skipped** (geocoder, FCM smoke, protocolos: gated por red/credenciales) |
| Dashboard | `node --test src` | **80 / 0** (7 archivos) |
| Instrumented | — | 1 archivo, 3 `@Test` (`HealthSnapshotMigrationTest`, migración Room 7→8) |

Corrección al brief: **MIGRATION_8_9 YA EXISTE** (`AppDatabase.kt:140`, versión 9, ALTERs
`uploadedAt`/`healthState`). Lo que NO existe es su **androidTest** (el instrumented actual solo
recorre 7→8). Cobertura real de migraciones: 1 de 8 migraciones.

---

## 1. Mapa de cobertura por paquete (mobile: 83 test vs 108 main)

Patrón dominante del repo: cada dominio extrae **Policy objects puros** y los tests atacan
esas políticas; el **glue Android-bound** (Service, DAOs, controllers, notificaciones, receivers)
queda sin test por diseño JVM. La fila "hueco" es comportamiento de runtime sin red de
seguridad, no póliza sin test.

| Paquete | main | tests | @Test | Estado / hueco severo |
|---|---|---|---|---|
| tracking | 16 | 9 | 38 | Policy cubiertas (ForegroundGuard/Claim, Presence, Network/Notification/State, Telemetry, Journey*). **HUECO: TrackingService (1382 LOC) orquestación completa sin caracterización** (pipeline onNewLocation→outbox, watchdog, controllers, ConnectivityObserver) |
| transport | 5 | 7 | 75 | DispatchPolicy (16) + backoff/jitter (11) sólidos. **HUECO: MqttManager.dispatchLoop (ACK→delete/quarantine, restauración de backoff, espera con wake) sin ningún test a nivel orquestación; no existe fake de `ControlQueueStore`**; `RoomControlQueueStore` (SQL del puerto) 0 tests |
| config | 1 | 3 | 10 | `clampRemoteBufferMax` cubierto (3). **HUECO: `AppConfig.applyRemote` (9 params, guards >0, clamp) sin test directo**; RemoteConfig server→móvil sin test de contrato |
| data | 11 | 6 | 22 | Policies (buffer/drain/retention/order) cubiertas. **HUECO: AppDatabase (migraciones 2..9 JVM-0), HealthSnapshotDao, RemoteConfig sin test**; PositionDao real solo se toca en instrumented |
| location | 10 | 9 | 99 | La mejor cubierta del repo (FixFilter, FixTime, SpeedEstimator-adyacente, LocationQuality, AdaptiveInterval, fusión, GNSS fallback, ActivePollPolicy). Glue LocationEngine/GnssState sin test directo |
| core | 10 | 5 | 33 | Protocol, RttMeter, BootId, SpeedEstimator, JourneyDisplay cubiertos. **HUECO: MqttStatus, MqttServerNormalizer, RecoveryOutcome, TrackingState parser sin test nominal** |
| recovery | 8 | 8 | 59 | 1:1 archivos (SessionKeeper, StuckStop, FcmRecovery, Schedule, BootIntents, Journal, Status). Glue: TrackingRecoveryWorker, BootReceiver, FcmRecoveryMessagingService sin test |
| outbox | 5 | 6 | 50 | Dispatcher/coordinator/microbatch/HttpFlushPolicy cubiertos con FakePositionDao + `now` inyectable. HUECO: RoomControlQueueStore y PendingAlert→Room sin test |
| diagnostics | 6 | 6 | 52 | 1:1 (Telephony/Link/Silence×2/NetCause/Collector). Bien |
| readiness | 6 | 4 | 43 | Policies cubiertas; DeviceReadinessChecker/ContinuityTracker (glue Android) sin test |
| ui (5+7 sub) | 12 | 4 | 37 | Solo policies de estado (Onboarding, GpsDiag, FitFontScale, WidgetState). **0 tests de Compose/Activities: MainActivity 1472, Onboarding 980, Diagnostics 890 LOC sin red** |
| oem | 5 | 4 | 20 | DeviceCaps, Guidance, Protection, VendorSettingsZTE cubiertos; DeviceCapabilityProfile (data class) sin test nominal |
| platform | 5 | 2 | 14 | AlertPolicy + UpdateManager. **HUECO: UpdateChecker, LocationState, Notifications, SentryLog sin test** (borde Android: aceptable, pero UpdateChecker tiene lógica de decisión) |
| health | 4 | 3 | 16 | Uploader (contrato JSON exacto, sin PII), Monitor, Policy. HealthStateProvider glue sin test |
| sensors | 3 | 3 | 14 | Motion/Gyro/SensorCoordinator policies. Glue MotionSensor/GyroSensor sin test |
| dashboard | — | 7 | 80 | Solo `map/util`, `common/util`, `other`. **0 tests: CachingController, SocketController, UpdateController, Navigation, ErrorBoundary, store, reports** (P2: no bloquea el refactor móvil) |
| server | 455 arch | — | 821 | Suite upstream completa + `org/traccar/mobile` 22 archivos/168 tests: QualityFilter(11), MotionStateV2Engine(20), Presence/Ingestion/Continuity/Envelope/ApiKey/LwtThrottle/Sweep. 29 skips = opt-in de red (correcto) |

**Clases glue sin referencia alguna en tests** (glue Android-bound, sin test posible en JVM
sin refactor; flag para el plan instrumented): MainActivity, SplashActivity,
DebugDesignActivity, JourneyWidget, DmujeresApp, Notifications, SentryLog, LocationState,
UpdateChecker, TrackingService, MqttManager, PresenceController, TrackingSessionController,
TrackingNotificationController, TrackingWatchdog, ConnectivityObserver, JourneySummaryPresenter,
DeviceTelemetrySampler, RoomControlQueueStore, AppDatabase, HealthSnapshotDao, RemoteConfig,
ContinuityTracker, DeviceReadinessChecker, HealthStateProvider, TrackingRecoveryWorker,
BootReceiver, FcmRecoveryMessagingService, LocationEngine, GnssState, MotionSensor, GyroSensor,
MqttStatus, MqttServerNormalizer, TelephonyState (glue del archivo), ControlQueue (interfaz).

---

## 2. Falsa confianza (detección por grep + lectura)

Confirmadas y corregidas:

1. **`unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:116`, con comentario
   que lo admite). Efecto real hoy: riesgo **latente, no activo** — grep de tests que cargan
   `android.util.Log` = 1 archivo; no hay mocks (0 mockito/mockk) y 0 `runCatching`-only.
   Si un test futuro carga código Android-bound, fallará **silenciosamente en verde**.
2. **`npm test` del dashboard NO corre todo**: script = `node --test src/map/util/` — excluye
   `other/qualityLabel.test.js` y `common/util/{dmujeresFleet,deviceHealth}.test.js`
   (~24 tests que hoy pasan solo porque alguien corrió `node --test src`). CI-invisible.
3. **Dependencia de reloj real en tests**: 4 archivos usan `System.currentTimeMillis` directo
   (JourneyDisplayElapsed, PositionOutboxDispatcher, OutboxCoordinator, OfflineRouteOrder) y
   `PositionOutboxMicroBatchTest:212` duerme tiempo real (`delay(debounce*2 + 200*cycles)`)
   → flakes de CI y no-determinismo en noches de carga.
4. **Dead-letter 29 skipped server**: correcto (opt-in), pero deben etiquetarse como
   `PENDING REAL-WORLD VALIDATION`, nunca sumarse a un PASS inferido.
5. **Descartadas como falsas**: no hay mocks de Log (patrón es fakes propios), no hay tests
   "solo no-throw", no hay Robolectric ni mockito — la estrategia de políticas puras+interfaces
   es real y verificada por lectura.

Aserciones débiles: no se detectaron patrones sistemáticos (los tests son `assertEquals`
contra contratos exactos; muestra verificada: `HealthUploaderTest` JSON exacto + no-PII,
`FcmTokenRegistrarTest` privacidad, `MobileQualityFilterTest` verdicts).

---

## 3. Caracterización faltante (R32) — prioridades

| # | Objetivo | Qué congelar (oro antes de tocar) | Cómo (JVM real, sin Android) |
|---|---|---|---|
| C1 | **TrackingService.onNewLocation** (línea 864→1180) | Pipeline captura: FixFilter→anti-livelock R1→regla OR Traccar (24 m/15°)→sequence→envelope→insertWithinLimit→lastFixAt/heartbeat→requestFlush. Debe congelarse ANTES de partir TrackingService (deuda A-1) | Extraer `CapturePipeline` puro (ya es casi factible: FixFilter/FixTime/SpeedEstimator/LocationQuality son puros) con entradas `Location-fix` DTO + reloj inyectado; aserciones sobre `PendingPosition` resultante y contadores `incRejected` |
| C2 | **MqttManager.dispatchLoop** (línea 414) | Presencia: dueControls→selectNext→publishWithAck; **ACK/duplicate→delete+recordConfirmedPosition; NACK rejected/invalid/expired→quarantine (jamás delete); restauración de `retryAt` persistido; semántica de espera wake/timeout** | Requiere fake de `ControlQueueStore` + fake de publisher (extraer puerto `ControlPublisher`). Congela el contrato ACK/NAK antes de tocar el monolito MQTT (deuda A-4) |
| C3 | **AppConfig.applyRemote** | 9 params: null-skips, guards `>0`, clamps buffer; qué queda y qué se ignora con payload parcial | JVM puro ya (prefs fake). Tabla de truthiness: cada campo × (null, válido, 0/negativo, extremo) |
| C4 | **RoomControlQueueStore** | SQL: `dueControls` (isControl=1, retryAt<=now, FIFO), `minFutureRetryAt`, `quarantine` con motivo | Instrumented (no JVM): el C2 usa la interfaz; el SQL real se cubre con el plan §4 |

Orden de congelamiento por fase del refactor: **C1 antes de** romper TrackingService;
**C2 antes de** tocar MqttManager; **C3 antes de** rebanar AppConfig (A-3).

---

## 4. Instrumentation — qué hay, qué falta, plan mínimo por fase

**Hay:** 1 archivo (`HealthSnapshotMigrationTest.kt`, 3 @Test): construye BD v7 con SQL puro,
abre con Room + MIGRATION_7_8, valida supervivencia + runtime (idempotencia/retención).

**Falta (ordenado por fase):**

| Fase del refactor | Test instrumented mínimo |
|---|---|
| Ahora (pre-cambio) | **M-1a: migración 8→9** (mismo patrón del 7→8: ALTERs `uploadedAt`/`healthState`, default 0/'UNKNOWN'); Room 9→10 cuando exista la próxima migración |
| Partir TrackingService | M-1b: **Service smoke en dispositivo**: startForegroundService→state notified→stop; notification channel/foreground intacto (lo único no-JVM del ciclo) |
| Rebajar AppConfig | M-1c: no requiere instrumented nuevo (applyRemote ya es JVM); solo re-ejecutar suites |
| Monolito MQTT | M-1d: con device local + broker de staging: connect/testConnection/publish con ACK real y cuarentena real; es FIELD, no CI |
| Permisos/notificaciones | M-1e: **DeviceReadinessGate** instrumented: conceder/denegar location+background+notifications y verificar estados `PermissionHealth` (readiness policy ya existe; solo wiring Android falta) |

Regla transversal (TESTING_STRATEGY §6): lo anterior nunca certifica Doze/cfreezer/24 h
— eso es Fase REAL_DEVICE/ENDURANCE con runbook, no CI.

---

## 5. Herramientas existentes

- **Fakes**: únicamente `testutil/FakePositionDao.kt` (memoria, countOverride) usado por 3
  archivos. **No existe** fake de `ControlQueueStore`, ni fake de publisher MQTT, ni reloj
  inyectable global (el dispatcher usa param `nowMs`; el coordinator usa lambda `now`).
- **Seed data**: no hay seeds de DB en tests; los datos nacen en cada test (builder `fix()`
  del dashboard). Server: `setup/traccar.xml` es config, no seed de datos.
- **Golden files geo/segmentación**: **no existen** como archivos. Los casos forenses
  (Joseph/Macias/Kevin, regla 52) viven inline en `dashboard/src/map/util/replayAudit.test.js`
  ("Santiago / Joseph / Test", disperso 30 s). Riesgo: fixtures efímeras; si se borran se
  pierde la evidencia. Recomendación: extraer los casos a `server/test/resources/geo/` JSON
  compartidos (mismo set consumido por `MotionStateV2EngineTest` y `MobileQualityFilterTest`).

---

## 6. Cobertura real de reglas críticas

| Regla | Cubierta | Evidencia / hueco |
|---|---|---|
| DispatchPolicy backoff (5s·2^n, techo 5 min) + jitter ±25% | Sí (puro) | `DispatchPolicyTest` (16) + `DispatchBackoffTest` (11) |
| Recovery policies | Sí (1:1 archivos, 59 tests) | SessionKeeper/StuckStop/FcmRecovery/Schedule/Boot/Journal/Status; glue workers sin test |
| MobileQualityFilter (server) | Sí | 11 @Test puros (verdicts) + Ingestion/Envelope/ApiKey |
| **Presencia ACK/retry/cuarentena (móvil)** | **NO** | dispatchLoop sin test; puerto sin fake (C2) |
| **Pipeline captura→outbox (móvil)** | **NO** | policies sí, orquestación no (C1) |
| **applyRemote (contrato remoto)** | **NO** | 0 tests (C3) |
| Migraciones Room | Parcial | 7→8 instrumented; 8→9 y 1→7 nada |
| Reglas OR Traccar en onNewLocation (deferred) | Parcial | `FixFilter` puro testado; la integración con `dao.count()`/buffer-full no |

---

## 7. Severidades

| ID | Hallazgo | Sev | Acción |
|---|---|---|---|
| H-1 | dispatchLoop sin caracterización + sin fake de ControlQueueStore | **ALTA** (bloquea deuda A-4) | C2 + puerto publisher antes de tocar MQTT |
| H-2 | onNewLocation (1382 LOC) sin caracterización | **ALTA** (bloquea A-1) | C1 (extraer CapturePipeline) |
| H-3 | applyRemote sin test de contrato | **MEDIA-ALTA** (A-3) | C3, barato, primero |
| H-4 | Migración 8→9 sin instrumented | **MEDIA** | M-1a, se ejecuta en dispositivo con patrón existente |
| H-5 | `npm test` dashboard excluye 3 de 7 archivos | **MEDIA** (CI silencioso) | corregir script (fuera del refactor móvil, pero documentarlo) |
| H-6 | `isReturnDefaultValues=true` con 1 test tocando Log | **MEDIA-BAJA** | vigilar; documento TESTING_STRATEGY ya lo prohíbe declarar |
| H-7 | Reloj real en 4 tests + sleep real en MicroBatch | **BAJA-MEDIA** (flakes) | inyectar reloj al extraer el pipeline (viene gratis con C1) |
| H-8 | Fixtures forenses inline, no golden files | **BAJA** | extraer JSON compartidos geo |
| H-9 | Dashboard controllers/store sin tests | **BAJA** (P2) | post-refactor |

---

## Resumen final (≤15 líneas)

- Suites verificadas HOY: móvil 588/0, server 821/0/29 skips (opt-in de red), dashboard 80/0.
- Patrón del repo: políticas puras muy testadas (location 99, transport 75, recovery 59);
  orquestación Android (TrackingService, MqttManager, controllers, Room glue) 0 tests.
- La confianza verde es real en capa POLÍTICA (sin mocks, sin no-throw, aserciones exactas)
  pero no certifica pipeline captura→outbox ni loop MQTT ACK/retry/cuarentena.
- Prioridades: C3 (applyRemote, barato) → C2 (dispatchLoop con fakes de puerto) →
  C1 (CapturePipeline) antes de las fases del refactor; M-1a migración 8→9 en dispositivo.
- Correcciones al brief: MIGRATION_8_9 ya existe (falta su instrumented); `npm test`
  del dashboard corre solo map/util (24 tests fuera de CI); no hay mocks ni Robolectric.
- Fakes casi inexistentes (1 FakePositionDao): el plan de caracterización exige primero
  crear fakes de ControlQueueStore y publisher, sin tocar código productivo.
