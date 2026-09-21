# ARCHITECTURE_AUDIT_AGENT.md — Auditoría de arquitectura (Agente R3, 2026-09-17)

> Método: inspección estática de `mobile/app/src/main/java/com/dmujeres/traccar`
> (108 archivos `.kt`), grep de imports reales, y lectura de
> `ArchitectureDependencyTest` / `DependencyCycleTest`. SOLO LECTURA del código.
> No duplica `ARCHITECTURE_AUDIT_R2.md` ni `TECHNICAL_DEBT.md` A-1..A-8:
> los verifica con evidencia nueva y marca discrepancias al final (§8).

---

## 1. Inventario real por paquete (LOC por clase)

Total `main`: **108 archivos**. Tests: **82 archivos de test JVM** (587 `@Test`)
+ 1 `androidTest` (`HealthSnapshotMigrationTest`, 3 `@Test`) → **590** anotaciones
`@Test` estáticas. La cifra declarada "588/0" es consistente en orden de magnitud;
ver discrepancia D-3 en §8.

| Paquete | LOC | Clases >500 | Nota |
|---|---:|---|---|
| `ui` | 4841 | MainActivity 1472, OnboardingActivity 980, DiagnosticsActivity 890, MetricsRow 522 (UI pura, OK) | Capa de presentación, sin tests de Activity |
| `tracking` | 3062 | TrackingService 1382 | Resto son políticas/controladores ya extraídos |
| `location` | 1603 | LocationEngine 531 (OK, R9) | Resto políticas puras |
| `recovery` | 1264 | — | Workers/receivers/policies |
| `diagnostics` | 1077 | — | NetCause 305, DiagnosticsCollector 273 |
| `readiness` | 978 | — | DeviceReadinessPolicy 330 |
| `transport` | 1238 | MqttManager 771 | Envelope/DispatchPolicy/ReconnectGate puros |
| `config` | 911 | AppConfig 911 | Única clase |
| `outbox` | 731 | PositionOutboxDispatcher 412 | — |
| `platform` | 635 | — | Notifications 282, UpdateManager 245 |
| `oem` | 553 | — | Hoja sin dependencias |
| `data` | 722 | — | Room + políticas |
| `sensors` | 388 | — | Hoja sin dependencias |
| `health` | 347 | — | — |
| `core` | 439 | — | Primitivas puras |
| raíz (`DmujeresApp`) | 153 | — | Composition root (2 clases: App + `ReconnectPolicy` L148) |

**Conteo total de `object`:** 77 declaraciones (incluye `object :` anónimos);
los `object` de nivel de paquete son mayoritariamente políticas puras sin estado.

---

## 2. Los 7 archivos bloqueados: responsabilidades concretas y extracciones

### 2.1 `tracking/TrackingService.kt` — 1382 líneas (deuda A-3, límite ~1500)

| # | Responsabilidad | Evidencia | Extraer a |
|---|---|---|---|
| 1 | Ciclo de vida Android (FGS) | `onCreate` L151, `onStartCommand` L420, `onTaskRemoved` L1333, `onDestroy` L1346 | (permanece) |
| 2 | API estática + estado global `isRunning` | companion L74–95 (`@Volatile isRunning`) | (permanece, aceptado) |
| 3 | Wiring de DI explícito (~175 líneas) | `onCreate` L151–325 | Si crece: fachada `ServiceGraph` (H-1) |
| 4 | Receiver de pantalla ON/OFF | `registerScreenReceiver` L326–364 | `platform/` (observador de pantalla) |
| 5 | Detección de reinicios anómalos | `detectAbnormalRestarts` L407 | `recovery/` |
| 6 | Orquestación start/stop con reintento `pendingStart` en `finishStopping` | `startTracking` L504, `stopTracking` L1274, `finishStopping` L1294 | Coordinador de sesión (J-3) |
| 7 | **Pipeline de captura `onNewLocation` (~410 líneas)**: validación, plausibilidad, anti-livelock ventana vacía, velocidad doppler/implícita, re-afirmación de foreground, continuidad, encolado, presencia MQTT | L864–1273; helpers `isPlausibleFix` L784, `confidenceFor` L842 | `tracking/PositionPipeline` (bloqueado por J-1: sin caracterización) |
| 8 | Estado mutable propio del pipeline (ArrayDeque `recentFixes`, `emptyWindowRejects`, `lastSpeedRef*`) | campos de instancia + L767–783 | Debe migrar CON la extracción #7 |
| 9 | Contexto de dispatch outbox + callbacks de cuarentena | L654–696 | (permanece: composición) |
| 10 | Reacción a estado MQTT/notificación y métricas de viaje | `onMqttStateChanged` L697, `distanceMeters` L734 | `TrackingNotificationController` ya existe; mudar la parte de métricas |
| 11 | Pausa/reanudación por buffer lleno | L747–766 | `OutboxCoordinator` (ya tiene debounce) |

### 2.2 `transport/MqttManager.kt` — 771 líneas (deuda A-5)

| # | Responsabilidad | Evidencia | Extraer a |
|---|---|---|---|
| 1 | Conexión/reconexión con generación y gate | `connect` L147, `connectLocked` L173, `scheduleConnectRetry` L107, `clearStaleConnecting` L161, `connectGeneration` L82 | `MqttConnectionManager` |
| 2 | Suscripción + reintento de suscripción | `subscribeAndDispatch` L349, `scheduleSubscriptionRetry` L393 | ídem |
| 3 | **Bucle de dispatch** (cola de control, presencia, in-flight) | `startDispatch` L404–576 | `MqttControlDispatcher` |
| 4 | Correlación ACK con futuros y secuencia | `ackFutures`/`inFlightSequences`/`retryAt` L63–65, `handleAck` L588 | `MqttAckCorrelator` (JVM-testeable) |
| 5 | Estado observable (`connected/ready/lastError`, 17 campos mutables) | L55–83 | Agrupar en data class de estado |
| 6 | Harness de prueba de conexión + normalización + errores amigables | companion L652–760 | `core/` ya tiene `MqttServerNormalizer`; mover `testConnection` a `diagnostics/` |

Estado mutable real: `@Volatile` L55–83 + `ConcurrentHashMap` L63–65 + watchdog
`Thread` L696 (hilo dedicado para timeouts de ACK). Riesgo principal del split:
la generación (`connectGeneration`) invalida callbacks de cliente viejo; extraer
sin congelar ese invariante reintroduce zombies de sesión (el bug que motivó el
sufijo persistente en `AppConfig`).

### 2.3 `config/AppConfig.kt` — 911 líneas (deuda A-4)

Un solo `SharedPreferences` ("dmj_tracking", L18) con **~65 claves** (companion
L773–864) y **163 referencias `prefs.`**. Dominios de responsabilidad:

| Dominio | Evidencia | Extraer a |
|---|---|---|
| Identidad/credenciales (`server`, `username`, `password` en claro — TODO(security) L36, `deviceId`, `deviceHash`) | L21–70, `deviceHash` L714 | `config/IdentityStore` |
| Sesión/boot (`sessionId`, `bootId`, crashes) | `newSessionId` L339, `bootIdRefresh` L363 | `config/SessionStore` |
| Tunables (interval, buffer, ackTimeout, retries, policies + `applyRemote`) | L676+, claves L791–796 | `config/TuningStore` |
| Timestamps `last_*` (8 claves) | L796–803 | ídem |
| Contadores/métricas (fixes, rechazos, ACK, retry, cuarentena) | L157–281 | `config/MetricsStore` |
| Buckets diarios codificados "epochDay:value" (crashes/clock/reconnect/stuck/anr) | L479–494, codec L753–790 | `config/HealthBuckets` (codec YA testeado: `HealthBucketTest`) |
| Estado de jornada (start, elapsed, distancia, puntos, última loc, stop requested) | claves L847–861 | `config/JourneyStateStore` |
| Estado de update + topics de protocolo | L862–864, `telemetryTopic/ackTopic` L705–710 | `platform/UpdateManager` / (permanece, delega en `core.MobileProtocol`) |

Split mecánico pero de ALTO riesgo de superficie: cualquier cambio de clave
rompe datos en campo. Estrategia R10 (fachada temporal que delega) sigue siendo
la correcta.

### 2.4 `outbox/PositionOutboxDispatcher.kt` — 412 líneas (deuda A-6)

`object` singleton con estado mutable global: `wakeStarted` (AtomicBoolean)
L329, `@Volatile wakeDao/wakeTransport/wakeCtxProvider/wakeDebounceMs` L332–335,
callbacks `mqttReady` L344 y `onFlushOutcome` L351. Responsabilidades: semántica
de flush HTTP (batching 50, quarantine, backoff por estado de negocio),
microbatch WAKE post-insert con debounce, `HttpTransport` interno L89, contexto
`DispatchContext` con valores (no AppConfig) L64. Propietario único del dispatch
HTTP; single-flight garantizado por `core/DispatchLock`. **Es el archivo mejor
caracterizado por tests de los 7** (§4). Extracción recomendada solo si aparece
un segundo consumidor (hoy: no).

### 2.5 `ui/MainActivity.kt` — 1472 líneas (deuda A-2)

**20+ campos `mutableStateOf` sueltos** L163–192 (UiState sin ViewModel) y
polling con `Handler(Looper.getMainLooper())` L200.

| Responsabilidad | Evidencia | Extraer a |
|---|---|---|
| Login + prueba de credenciales | `login` L529 | ViewModel (`AuthViewModel`) |
| Permisos/batería (exención) | `ensureBatteryExemption` L399, `startIfReady` L585 | ViewModel + `readiness/` |
| Toggle de tracking + cierre de jornada con confirmación | `onTogglePressed` L568, `confirmFinishJourney` L818 | ViewModel (`TrackingToggle`) |
| Bucle de refresco 1 s | `run` L203, `refreshState` L652 | ViewModel (corutina) |
| **Actualización in-app completa** (check, banner, diálogo, descarga+instalación) | `checkForUpdate` L862, `showUpdateDialog` L919, `downloadAndInstall` L930 | `platform/UpdateManager` (ya existe: mudar la orquestación allí) |
| Dashboard debug (extras, toast, estado oculto) | `readDebugExtras` L425, `applyDebugDashState` L465 | `ui/DebugDesignActivity` o build-flag |
| Navegación (diagnóstico, WiFi) | L957–965 | (permanece) |
| Compose UI completa (MainScreen, TopBanner, MainCard, Skeleton, LogPanel, UpdateBanner) | L966–1472 | `ui/components/` (como ya se hizo con `MetricsRow`) |

### 2.6 `ui/OnboardingActivity.kt` — 980 líneas (deuda A-2)

- Política de pasos en companion **ya pura y testeada**: `needsForegroundLocationPermission`
  L901, `isLocationComplete` L907, `isStepComplete` L924, `gateSteps` L945,
  `dynamicSteps` L967 → `OnboardingPolicyTest` (15 tests).
- Flujo de permisos en 4 modalidades (foreground L212, notificaciones L240,
  background L248, batería L286) + navegación a settings de vendor
  (`openVendorSettings` L165, `openVendorSecondary` L195) — duplica conocimiento
  que ya vive en `oem/VendorSettings`.
- Compose (~470 líneas): `OnboardingContent` L311, `ReadyScreen` L697,
  `StepButton` L781, `rememberStepStates` L854.
- Extraer: navegación vendor → delegar en `oem/OemGuidanceProvider`; pantallas
  Compose → `ui/components/onboarding/`.

### 2.7 `ui/DiagnosticsActivity.kt` — 890 líneas (deuda A-2)

- Recolección de datos vía `rememberDiagRows` L402 (LaunchedEffect + refresh key).
- 3 acciones con efectos de sistema: `testConnection` L121, `recoverService`
  L132, `sendReportNow` L151.
- Compose (~200 líneas): `DiagnosticsContent` L165, `DiagSection` L354, `DiagCell` L368.
- Helpers puros en companion YA testeados: `agoOrNever` L494, `hasRecentFix`
  L858, `withoutFixMs` L866, `isSearching` L872, `describe` L875 →
  `GpsDiagPolicyTest` (7 tests).
- Extraer: `DiagRows` a un provider/ViewModel (`ui/DiagnosticsViewModel`); las
  3 acciones son finas y pueden quedar.

---

## 3. Dependencias y ciclos reales (evidencia de imports)

### 3.1 Grafo confirmado por grep de imports (coincide con `DEPENDENCY_RULES.md`)

Aristas con mayor peso (nº de imports, por clase):

- `TrackingService →` config 1, core 5, data 3, diagnostics 1, health 2,
  location 6, outbox 3, platform 2, readiness 1, recovery 1, sensors 1,
  transport 2, ui(widget) 1 (`TrackingService.kt:39,44`).
- `TrackingWatchdog →` 10 dominios (data 2, diagnostics 2, health 1, location 3,
  outbox 3, platform 2, recovery 1, sensors 3, transport 1) — vigilado como H-2.
- `MainActivity →` config, core 3, data, diagnostics 3, oem, outbox, platform 3,
  readiness 3, tracking 2, transport 1, ui 7 — **cliente gordo de 10 dominios**.
- `DiagnosticsActivity →` 11 dominios.
- `DeviceTelemetrySampler →` diagnostics 8 (acoplamiento de telemetría notable).

Reglas ejecutadas: `ArchitectureDependencyTest` (103 líneas, 14 reglas por
paquete, escaneo de imports) y `DependencyCycleTest` (2 tests: ningún paquete
fuera de la deuda puede ciclar; `transport` nunca). Confirmado que la única
excepción usada es `tracking → ui.widget` (`TrackingService.kt:44`) y
`platform → ui` (`Notifications` → `MainActivity`).

### 3.2 Ciclo aceptado (congelado por `DependencyCycleTest` L20–27)

`tracking ↔ readiness ↔ recovery ↔ ui ↔ platform ↔ diagnostics`:

- `tracking → readiness`: `TrackingService.kt:39`; retorno
  `readiness/ContinuityTracker.kt` → tracking, `DeviceReadinessChecker` → tracking.
- `tracking → recovery`: `TrackingService.kt:42` (worker); retorno
  `BootReceiver`, `SessionKeeper`, `FcmRecoveryMessagingService`,
  `TrackingRecoveryWorker` → tracking (4 aristas de entrada).
- `ui → diagnostics` (`DiagnosticsActivity`) y `diagnostics → platform`
  (`DiagnosticsReporter`) → `platform → ui` (`Notifications`): cierra el anillo.

`outbox → transport` solo `Envelope` (cable compartido) + `data` (Room) —
correcto; `transport` NO importa `data`/`outbox` (puerto `ControlQueueStore` +
`outbox/RoomControlQueueStore`). **Verificado: 0 violaciones de las 14 reglas.**

---

## 4. Riesgos de refactor: qué tests caracterizan qué

Suite: 82 archivos JVM, 0 Robolectric / 0 androidx.test en `test/` (grep = 0).

| Clase bloqueada | Cobertura caracterizadora | Riesgo |
|---|---|---|
| `TrackingService` | SOLO las políticas ya extraídas (9 tests en `test/tracking/`: DeviceTelemetry, ForegroundGuard, ForegroundClaim, NotificationState, Presence, TrackingState, NetworkState, JourneyStop, JourneySummary). **`onNewLocation` L864–1273: 0 tests** (J-1) | **ALTO**: extraer el pipeline sin harness JVM/Robolectric rompe silenciosamente anti-livelock R1, velocidad implícita, dedup de relay |
| `MqttManager` | `MqttRobustnessTest` (11), `PresenceObservabilityTest` (5), `DispatchBackoffTest`, `ReconnectGateTest` — cubren helpers/robustez, NO la máquina de estados conexión→suscripción→dispatch | MEDIO-ALTO: el invariante `connectGeneration` (L82) y el watchdog `Thread` L696 no están congelados por test |
| `AppConfig` | Solo codecs del companion: `HealthBucketTest` (5), `RejectBreakdownTest` (2), `RemoteBufferPolicyTest` (3) | MEDIO: split mecánico, pero 65 claves × 163 usos sin test de regresión de claves; usar fachada que mantenga claves byte-idénticas |
| `PositionOutboxDispatcher` | `PositionOutboxDispatcherTest` (13), `PositionOutboxMicroBatchTest` (7), `DispatchRobustnessTest` (7), `HttpFlushPolicyTest` | **BAJO** (el mejor de los 7): semántica de flush/cuarentena/microbatch caracterizada; safe de refactorizar ya |
| `MainActivity` | 0 tests de Activity; nada de su lógica de login/update/toggle | ALTO: cualquier extracción requiere primero ViewModel con test de decisión (p. ej. `startIfReady`) |
| `OnboardingActivity` | `OnboardingPolicyTest` (15) cubre el companion puro | BAJO-MEDIO: la política está congelada; el riesgo es solo el rewire de navegación vendor |
| `DiagnosticsActivity` | `GpsDiagPolicyTest` (7) cubre helpers puros | BAJO-MEDIO: ídem |

---

## 5. Purity y fronteras (verificación de R2)

- **`core` puro**: el único import Android de `core` es
  `core/JourneyFormatter.kt:3` (`android.content.Context`, solo para
  `strings.xml` en `journeyDuration`/`agoText`; la aritmética está separada y
  testeada). Mejora real vs R2 (que reportaba `android.util.Log` en core).
- Implicancia pendiente: `core` importa `com.dmujeres.traccar.R` (raíz) — el
  test de dependencias no lo captura porque `R` resuelve al namespace raíz.
  Impacto bajo (solo recursos), pero rompería un futuro módulo Gradle `core`.
- Interfaces de frontera reales: `PositionOutboxDispatcher.Transport` (L77),
  `LocationEngine.Callbacks`, `ControlQueueStore` (puerto R7). El resto son
  lambdas inyectadas (patrón consistente; I-1 sigue siendo válido).
- Room confinado a `data/` + `outbox/RoomControlQueueStore` (0 usos fuera).
- `GlobalScope`: 0. `System.currentTimeMillis()`: **97 usos** (A-7, era 96).
- `DmujeresApp` (153 líneas): composition root limpio (crash reporting,
  connectivity receiver, WorkManager, `ReconnectPolicy` pura L148).

---

## 6. Singletons / estado global mutable (inventario)

| Objeto | Estado mutable | Evidencia | Evaluación |
|---|---|---|---|
| `outbox.PositionOutboxDispatcher` | `AtomicBoolean` + 4 `@Volatile` + 2 callbacks | L329–351 | Aceptado (A-6); propietario único, tests sólidos |
| `core.MqttStatus` | 2 `@Volatile` | L9–14 | Aceptado (lectura observada) |
| `readiness.ContinuityTracker` | `Handler` + `lastTickElapsed` + `appContext` | L31–33 | Bajo riesgo; migrar `appContext` a parámetro cuando sea fácil |
| `tracking.TrackingService.isRunning` (companion) | `@Volatile` | L74–76 | Aceptado (API estática del servicio) |
| `DmujeresApp` | receiver + scope propio | L73, L137 | Composition root, aceptado |

El resto de `object` (≈70) son políticas puras sin estado — correcto.

---

## 7. Prioridades recomendadas (valor/riesgo, de mayor a menor)

1. **P1 — Split de `AppConfig` (A-4, R10) con fachada de claves idénticas.**
   Máximo valor: desbloquea testabilidad de tunables/métricas y encoge el
   acoplamiento de 12 clases que hoy importan el monolito. Riesgo MEDIO y
   mecánico; los codecs ya tienen test.
2. **P2 — `MainActivity` → ViewModel por rodajas** (A-2): empezar por
   update-in-app (mover orquestación a `platform/UpdateManager`, ya existe) y
   luego el toggle + refresco en `MainViewModel`. Riesgo ALTO sin tests → crear
   tests de decisión de arranque (`startIfReady`, `isLocationComplete` reutiliza
   política ya testeada) ANTES de mover.
3. **P3 — `PositionOutboxDispatcher` a instancia con lifecycle** (A-6): riesgo
   BAJO por cobertura (21 tests directos); hace explícito el owner y habilita
   tests paralelos. Solo cuando se toque outbox por otra razón (no urgente).
4. **P4 — Pipeline `onNewLocation` → `PositionPipeline`** (J-1+A-3): **no
   empezar sin Robolectric o harness con fakes**. Es la extracción de mayor
   valor funcional (410 líneas + estado) y el mayor riesgo de regresión en
   campo. Orden interno: 1) congelar `isPlausibleFix`/anti-livelock con tests
   JVM, 2) mover estado (`recentFixes`, `lastSpeedRef*`), 3) mover encolado.
5. **P5 — `MqttManager` split** (A-5): solo con frontera real; primero congelar
   `connectGeneration` + secuencia ACK con tests (extraer `MqttAckCorrelator`
   es lo único JVM-testeable hoy).
6. **P6 — Romper ciclo de orquestación** (A-1): interfaces de entrada para
   receivers/readiness (R13/R16/R17). Es el de mayor esfuerzo y menor valor
   inmediato (el ciclo está congelado y no crece).

**No priorizar**: LocationEngine (OK), MetricsRow (UI pura), oem/sensors (hojas),
ni tocar las aristas `outbox→transport (Envelope)` y
`tracking→ui.widget` (documentadas como valiosas).

---

## 8. Verificación de R2/TECHNICAL_DEBT y discrepancias

| ID previo | Veredicto con evidencia nueva |
|---|---|
| A-1 (ciclo congelado) | CONFIRMADO (§3.2); `DependencyCycleTest` L20–27, 2 tests |
| A-2 (3 Activities sin ViewModel) | CONFIRMADO + ampliado (§2.5–2.7: 20+ `mutableStateOf`, polling con Handler L200) |
| A-3 (TrackingService ~límite) | CONFIRMADO (1382; límite ~1500 se mantiene; el foco real es `onNewLocation`, no el total) |
| A-4 (AppConfig 9 dominios) | CONFIRMADO + ampliado (65 claves, 163 usos, 9 dominios enumerados §2.3) |
| A-5 (MqttManager) | CONFIRMADO (771; corrección menor: R2 decía 778 — el archivo encogió 7 líneas) |
| A-6 (dispatcher object) | CONFIRMADO (L329–351) |
| A-7 (clock) | ACTUALIZADO: 97 usos (era 96) |
| A-8 (secretos) | Fuera de alcance de arquitectura — ver `SECURITY_AUDIT.md` |
| J-1 (onNewLocation sin caracterización) | CONFIRMADO: 0 tests JVM lo tocan (§4) |

**Discrepancias detectadas (nuevas):**

- **D-1 (menor):** R2 afirmaba `android.util.Log` en core; hoy core solo importa
  `android.content.Context` (`JourneyFormatter.kt:3`) + `R`. Hubo limpieza no
  documentada en `REFACTORING_LOG.md`.
- **D-2 (menor):** `core → com.dmujeres.traccar.R` es una arista real al
  namespace raíz que `ArchitectureDependencyTest` no puede ver (regex de
  imports resuelve `R` al paquete raíz). Irrelevante hoy, bloqueante si se crea
  un módulo Gradle core (ver G-1).
- **D-3 (menor):** conteo estático de `@Test` = 590 (587 JVM + 3 androidTest)
  vs cifra declarada 588. Diferencia consistente con modo de conteo distinto
  (ejecución vs anotación); sin impacto.
- **D-4 (informativo):** `DeviceTelemetrySampler → diagnostics` tiene 8 imports
  (el mayor acoplamiento fuera del watchdog); candidato a fachada si la
  telemetría sigue creciendo.
