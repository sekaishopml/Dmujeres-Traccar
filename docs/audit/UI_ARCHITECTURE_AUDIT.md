# UI_ARCHITECTURE_AUDIT.md — Auditoría UI/State R3 (Agente F)

> Solo lectura. Commit base `f2975d0` (ver `docs/audit/R3_BASELINE.md`).
> Alcance: `ui/*`, `ui/components/*`, `ui/widget/*`. No se tocó código.
> No se propone migrar a Compose Navigation ni a Glance: la UI ya es Compose;
> el problema es de **estado y lógica en la Activity**, no de toolkit.

## 0. Hallazgo en una frase

**No existe un solo ViewModel en la app**: las 3 Activities son a la vez capa de
presentación, dueñas del estado (≈25 `mutableStateOf` en MainActivity), y capa de
dominio (login MQTT, gate de readiness, reactivación de recovery, OTA con
descarga+instalación, refresh de diagnóstico con `runBlocking` en hilo crudo).
`OnboardingPolicy` y `GpsDiagPolicy` demuestran que el equipo ya sabe extraer
lógica pura: falta repetir el patrón con orquestación.

---

## 1. INVENTARIO por archivo (con evidencia file:line)

### 1.1 `ui/MainActivity.kt` (1472 líneas) — archivo BLOQUEADO (regla 50)

| Categoría | Ubicación | Evidencia |
|---|---|---|
| Estado mutable no-Compose | L152-159 | `config`, `testing`, `latestUpdate`, `updateCheckInFlight`, `detailsTransition`, `recoveryDialogShown`, `repairReviewShown` |
| Estado Compose plano | L163-192 | ≈24 `mutableStateOf/mutableIntStateOf` sueltos (login, dashboard, banner, netCause, pendingAbnormal, toggle*) |
| Overrides debug en memoria | L195-198 | `debugLoggedOverride`, `debugDashMode`, contadores de tap |
| Timer/Handler | L200-210 | `uiTicker` Runnable cada **3 s** → `refreshState()`; cada 40 ticks (≈2 min) → `checkForUpdate(auto=true)` |
| Timer/Handler | L212-214, L804-816 | `detailsTransitionTimeout` 15 s (`beginDetailsLoading/endDetailsLoading`) |
| Callback permisos | L216-220 | `permissionLauncher` → `startIfReady()` directo |
| Mutación AppConfig en onCreate | L224, L238-264 | auditoría post-OTA: `PermissionHealth.check`, navegación a Onboarding con `EXTRA_REPAIR`, **mutación** `config.appVersionCode` (L259) |
| Duplicación onCreate/onNewIntent | L313-321 vs L329-337 | misma lógica `EXTRA_OPEN_UPDATE`/`EXTRA_CONFIRM_STOP` copiada |
| Lógica de negocio: recovery reactivation | L365-382 | diálogo "killed": **mutación** `trackingEnabled=true`, `trackingState=SERVICE_RECOVERY`, `TrackingService.start()` |
| Lógica de negocio: login test MQTT | L529-558 | `MqttManager.testConnection` + callback → `runOnUiThread` → **mutación** `config.username/password` + `lifecycleScope.launch` + `RemoteConfig.fetch` en IO |
| Lógica de negocio: toggle→login→start | L568-583, L585-614 | `onTogglePressed` re-testea MQTT; `startIfReady` = permisos + **gate `DeviceReadinessChecker.evaluate`** + mutación config + start + rollback (L606-612) |
| Lógica de negocio: OTA check | L862-906 | `UpdateManager.check` en IO, **mutación** `lastUpdate*`, `Notifications.updateAvailable/clearUpdateAvailable`, banner/diálogo |
| Lógica de negocio: OTA download/install | L930-955 | notificación foreground id 9, `UpdateManager.download/install`, Toast error |
| Lógica de negocio: resumen jornada | L827-859 | `finishJourney` (**mutación** `journeyStopRequested/trackingEnabled/trackingState` + `TrackingService.stop`) + `showJourneySummary` (cálculo con `JourneyFormatter` leyendo config) |
| E/S en UI | L696-709 | `BatteryManager.getIntProperty` en main thread |
| E/S + DB en UI | L736-775 | `lifecycleScope.launch` + IO: `positionDao().count()/oldestEnqueuedAt()` → `PendingAlertPolicy.isAbnormal` |
| E/S red en UI | L722-728 | `NetCause.detect(snapshot(...))` en main thread |
| Diálogos sistema | L560-566, L404-419, L370-380, L819-825, L854-858, L920-928 | 6 `AlertDialog.Builder` imperativos |
| Permisos | L616-641 | `allPermissionsGranted` inline (4 permisos, lógica por SDK) |
| Permisos/batería | L399-420 | `ensureBatteryExemption`: diálogo + `dismissPrefs` (`"dmj_tracking"/battery_dialog_dismissed`) escritos **fuera de AppConfig** |
| Navegación | L246, L355, L587, L599, L957-964, L461 | Onboarding (repair), Diagnostics, DebugDesign, WiFi settings |

### 1.2 `ui/OnboardingActivity.kt` (980 líneas)

| Categoría | Ubicación | Evidencia |
|---|---|---|
| Launchers + estado | L94-106 | 3 `registerForActivityResult` (location, background, battery) → solo `refreshKey++`; `refreshKey` como mecanismo de re-evaluación |
| Cálculo de repairSteps (dominio) | L115-132 | parseo de intent + regla vendor pendiente (lee `AppConfig.vendorGuideDone`) |
| Mutación AppConfig | L172, L200 | `vendorGuideDone = true` al abrir guía (persistido como confirmación) |
| Mutación AppConfig | L262, L270 | `backgroundLocationAsked = true` |
| Flujo de permisos (dominio) | L212-293 | secuencia FINE/COARSE/FSL→BACKGROUND→notifications→battery; vendor-special-casing Xiaomi/Infinix (L261-265); fallback a Ajustes |
| Mutación AppConfig + nav | L295-308 | `finishOnboarding`: `onboardingDone=true`, `appVersionCode=BuildConfig.VERSION_CODE`, → MainActivity |
| **Evaluación del gate dentro de Compose** | L335 | `DeviceReadinessChecker.evaluate(context)` dentro de `OnboardingContent` (síncrono, main thread, `remember(evalKey)`) |
| Derivación de pasos + índice | L339-410 | `oemGatePending/batteryGatePending`, `OnboardingPolicy.gateSteps/dynamicSteps`, saltos de índice en `LaunchedEffect` (L382-407) |
| Permisos inline en Compose | L853-888 | `rememberStepStates` con `ContextCompat.checkSelfPermission` dentro de la composición |
| Persistido huérfano | L684-690 | `ContinuityUi` data class sin uso (muerto) |
| Lógica pura ya extraída | L897-980 | `OnboardingPolicy` (puro, testeado) |

### 1.3 `ui/DiagnosticsActivity.kt` (890 líneas)

| Categoría | Ubicación | Evidencia |
|---|---|---|
| Estado | L86-87 | `config`, `refreshKey` |
| Callback red | L121-130 | `MqttManager.testConnection` → `runOnUiThread` → Toast |
| Mutación AppConfig + start | L132-143 | `recoverService`: `trackingState=SERVICE_RECOVERY` + `TrackingService.start` |
| **Hilo crudo + runBlocking** | L151-161 | `sendReportNow`: `Thread { runBlocking(Dispatchers.IO) { dao.count() } }` + `DiagnosticsReporter.report` + `runOnUiThread` |
| Continuidad | L106-110 | `ContinuityTracker.startTest(this)` directo desde callback de UI |
| Refresh de diagnóstico | L402-821 | `computeDiagRows`: ~420 líneas ensamblando 14 secciones (AppConfig ×20 campos, GnssState, ConnectivityManager, DB, SilenceDiagnosis, RecoveryJournal, DeviceCaps, `DeviceReadinessChecker.evaluate` en IO L745-747) |
| Navegación | L101-103 | → OnboardingActivity (permisos) |
| Lógica pura ya extraída | L845-890 | `GpsDiagPolicy` (puro, testeado) |

### 1.4 `ui/widget/JourneyWidget.kt` (267 líneas)

| Categoría | Ubicación | Evidencia |
|---|---|---|
| Lectura directa de AppConfig | L49-59 | `config.trackingEnabled/journeyStartAt/journeyElapsedMs/WallMs/netCause` |
| **Arranque directo sin gate** | L134-139 | `startPendingIntent` → `TrackingService.ACTION_START` vía `getForegroundService`: **NO pasa por login, permisos ni readiness gate** (contrasta con MainActivity L585-614) |
| Stop con confirmación | L141-156 | stop → MainActivity + `EXTRA_CONFIRM_STOP` (correcto) |
| Push desde el servicio | L170-179 | `updateAll(context)` llamado por TrackingService al refrescar notificación |
| Lógica pura ya extraída | L186-267 | `WidgetUiState`/`JourneyWidgetState` (testeado) |
| Duplicación de tokens | L184 | `JourneyColorToken` duplica `ui/theme/JourneyColors` (hex paralelos) |

### 1.5 `ui/components/*` (presentación, sano)

- `MetricsRow.kt` (522): presenta puro, pero tiene su **propio ticker** `LaunchedEffect + delay(1000)` (L234-245) que recalcula elapsed desde las anclas: 2º reloj de jornada en vivo. Medición de fuente adaptativa pura y testeada (`FitFontScaleTest`).
- `StatusBanner.kt`, `LoginCard.kt`: 100% presentacionales (props + callbacks). Sin issues.

### 1.6 `ui/SplashActivity.kt` (143)

- L53-64: lee `AppConfig.onboardingDone` y decide navegación. Aceptable; candidata menor a ViewModel si se unifica con el gate.

---

## 2. LÓGICA CRÍTICA A EXTRAER (tabla prioridad × riesgo)

| # | Pieza | Hoy en | Destino | Prioridad | Riesgo de mover | Riesgo de dejar |
|---|---|---|---|---|---|---|
| 1 | **`startIfReady`**: permisos + readiness gate + start + rollback | MainActivity L585-614 | `StartJourneyCoordinator` (readiness/, junto a DeviceReadinessChecker) + VM | **Alta** | Medio: gate abre Onboarding (nav depende del Activity) | Alto: imposible de testear; divergirá del widget y del diag |
| 2 | **Login test MQTT + persistencia credenciales** | MainActivity L529-558, Onboarding? no, Diagnostics L121-130 | `LoginCoordinator` (transport/) + VM | **Alta** | Medio: callback-threading (runOnUiThread), RemoteConfig.fetch | Alto: credenciales se escriben antes de saber el resultado de RemoteConfig |
| 3 | **Recovery reactivation** (diálogo killed + start) | MainActivity L365-382 | `RecoveryReactivation` en recovery/ + evento al VM | **Alta** | Bajo | Alto: muta 2 claves + start sin guardas testeables |
| 4 | **Refresh de diagnóstico** (`computeDiagRows` + `sendReportNow`) | DiagnosticsActivity L151-161, L402-821 | `DiagnosticsPresenter` (puro, entry-points inyectados) + VM con `Dispatchers.IO` | **Alta** | Bajo: ya casi es puro; sólo romper dependencia Context→valores | Alto: `runBlocking` en Thread crudo; no testeable |
| 5 | **OTA: check/banner/download/install/notificación** | MainActivity L862-955 | `UpdateCoordinator` (platform/, ya existe UpdateManager) | **Alta** | Medio: notificación id 9 + Toasts manuales | Alto: mutación de `lastUpdate*` repartida en 3 sitios |
| 6 | **Auditoría post-OTA / repair review** | MainActivity L238-265 (onCreate) + L348-364 (onResume) — duplicada | `PostOtaRepairAuditor` (readiness/, sobre PermissionHealth) | Media-Alta | Bajo: pura sobre PermissionHealth (ya testeado) | Medio: dos copias que ya divergieron (breadcrumb distinto) |
| 7 | **Secuencia de permisos onboarding** | OnboardingActivity L212-293 | `PermissionFlowCoordinator` (readiness/) | Media-Alta | Medio: vendor-special-casing por OEM con prefs | Medio |
| 8 | **Evaluación del gate en Compose** | OnboardingActivity L335 + derivación L339-410 | VM (re-evalúa en `onResume`/eventos) | Media-Alta | Medio: `evaluate` lee prefs/permisos (síncrono) | Medio: main-thread I/O en recomposición |
| 9 | **Reactivación desde diagnóstico** | DiagnosticsActivity L132-143 | mismo coordinator que #3 | Media | Bajo | Medio |
| 10 | **Reloj de jornada (3 s UI + 1 s MetricsRow)** | MainActivity L202-210 + MetricsRow L234-245 | UN solo `JourneyClockFlow` en VM; MetricsRow queda presentacional | Media | Medio: NTP-inmunidad depende de anclas persistidas | Medio: dos tickers independientes pueden divergir |
| 11 | `allPermissionsGranted` | MainActivity L616-641 | `PermissionHealth` (ya existe: unificar) | Media | Bajo | Bajo |
| 12 | Diálogos de decisión (confirm stop, summary, battery) | MainActivity L818-859, L399-420 | VM emite eventos sellados; Activity solo renderiza | Media | Bajo (con #3/#1) | Bajo |
| 13 | Widget start sin gate | JourneyWidget L134-139 | decidir: gate en servicio (ACTION_START auto-valida) o widget abre MainActivity | Media | **Alto si se exige gate desde widget** (agrega fricción en arranque desde launcher) | Alto: inconsistencia de invariantes |
| 14 | Estado `WidgetUiState` derivado de AppConfig | JourneyWidget L47-69 | `WidgetSnapshot` compartido con DashboardPresenter | Baja | Bajo | Bajo |

---

## 3. DISEÑO OBJETIVO por pantalla (fichas)

### 3.1 Dashboard (MainActivity)

```
MainActivity (solo): setContent { MainScreen(uiState, onEvent) }
  + render de diálogos a partir de MainEvent (sealed)
MainViewModel (androidx.lifecycle.ViewModel):
  UiState UNO: DashboardUiState(login, dashboard, banner, update, flags de transición)
  Sources: AppConfig (snapshot), TrackingService.isRunning, MqttStatus, DB counts,
           BatteryManager (vía UseCase en IO), NetCause
  Timers: uiTicker 3 s → Flow en VM (deja de correr cuando la app no lo observa)
  Events: Login, ToggleJourney, ConfirmStop, OpenDiag, CheckUpdate, DownloadUpdate,
          ReactivateRecovery, OpenWifi, VersionTap
Coordinators (fuera de ui/): StartJourneyCoordinator, LoginCoordinator,
  UpdateCoordinator, RecoveryReactivation, PostOtaRepairAuditor
Queda en Compose: animaciones (Crossfade/skeleton/toggle color), layout compacto,
  filtrado de LogPanel, easter-egg de versión (contador de taps → evento)
```

- **Qué baja al VM**: los ≈24 `mutableStateOf` (un `StateFlow<DashboardUiState>`),
  `testing`, `latestUpdate`, `updateCheckInFlight`, `detailsTransition`,
  `recoveryDialogShown`, `repairReviewShown` (flags de sesión → VM `SavedStateHandle`).
- **Qué queda en Compose**: passwordVisible (UI puro), debugPreview de diseño (se
  puede mantener como decorador del UiState), animaciones.

### 3.2 Onboarding (OnboardingActivity)

```
MainActivity Activity (solo): launchers (3) + startActivity + diálogos si los hay
OnboardingViewModel:
  State: OnboardingUiState(steps, index, stepOk, allOk, repairMode, vendor)
  Evalúa: PermissionHealth/DeviceReadinessChecker → pasos derivados (OnboardingPolicy
  queda intacto y sigue siendo la unidad testeada)
  Events: RequestLocation, RequestNotifications, RequestBattery, OpenVendor,
  OpenVendorSecondary, Next, Back, Finish
PermissionFlowCoordinator: secuencia FINE/FSL→BG→vendor-fallback (prefs
  backgroundLocationAsked/vendorGuideDone pasan a ser escritas por el coordinator)
Queda en Compose: progreso animado, StepButton, ReadyScreen, índice visual
```

- Riesgo específico: el índice que salta pasos completos (L382-407) es estado
  de navegación → al VM, con test de caracterización de la lista dinámica.
- `repairSteps` del intent: parseo → VM (init), Activity queda sin lógica.

### 3.3 Diagnostics (DiagnosticsActivity)

```
Activity (solo): TopAppBar + scroll + botones → eventos
DiagnosticsViewModel:
  State: DiagUiState(14 secciones como strings YA traducidos + recoverEnabled)
  LaunchedEffect/onResume → vm.refresh() (IO), elimina runBlocking/Thread crudo
  Actions: testConnection (LoginCoordinator reuse), recoverService (#3),
  sendReportNow (executor existente de DiagnosticsReporter), startContinuity
Presenter puro: DiagnosticRowsPresenter(entrada: DiagSnapshot data class, salida:
  strings por sección) → testeable en JVM; GpsDiagPolicy/SilenceDiagnosis intactos
```

- El `DiagSnapshot` se arma en un `DiagnosticsCollector` existente o junto al
  presenter; la Activity deja de leer 20 campos de AppConfig en compose.

### 3.4 Widget (JourneyWidget)

```
JourneyWidget.render: igual (RemoteViews queda: decisión v1 correcta)
JourneyWidgetState: queda (ya puro y testeado)
Cambio mínimo: 
  - unificar JourneyColorToken con JourneyColors (o mapping explícito)
  - decidir semántica de ACTION_START (ver riesgos)
  - `online = config.netCause == "ok"` (L59) → usar NetCause.fromValue por consistencia
```

---

## 4. RIESGOS CLAVE del movimiento (los 5 que matan)

1. **Gate de readiness abre Onboarding**: `StartJourneyCoordinator` debe devolver
   un *veredicto* (READY / faltan permisos / gate NOT_READY) y el VM traduce a
   evento `Navigate.Onboarding(repairSteps?)`. Si el coordinator navegara
   directamente, se acopla a Activity. NO cambiar la semántica: hoy
   `startIfReady` abre Onboarding tanto por permisos (L587) como por gate
   (L599) — comportamiento visible a testear primero.
2. **Reloj de jornada**: la duración mostrada usa elapsed monotónico persistido +
   ancla (NTP-inmune, `JourneyFormatter.displayElapsedMs`). Cualquier VM nuevo
   debe reusar exactamente esa fórmula y el ticker 3 s + el 1 s de MetricsRow
   deben unificarse sin duplicar anclas. Regresión típica: volver a
   `now - journeyStartAt`.
3. **Banner/diálogo OTA**: hoy hay auto-check cada 2 min en foreground + al
   onResume + intent `EXTRA_OPEN_UPDATE` + notificación persistente
   (`Notifications.updateAvailable`) + descarga con notificación id 9. El
   coordinator debe preservar TODOS los disparadores y el flag
   `updateCheckInFlight` (evita doble check). Riesgo: perder la notificación
   clear (`clearUpdateAvailable`) en las 3 ramas de "no hay update".
4. **Recovery/reactivation con flags de sesión**: `recoveryDialogShown` y
   `repairReviewShown` son "una vez por sesión". En VM sobreviven rotación;
   verificación: diálogo de killed no debe reaparecer tras rotación (hoy sí lo
   haría si se moviera a `remember{}` — se requiere `ViewModel` real, no
   estado de composición).
5. **Widgets/invariantes**: `ACTION_START` desde widget arranca el servicio sin
   gate; si el coordinator centraliza el arranque, el servicio debe seguir
   aceptando el intent (idempotencia) o el widget rompe. Decisión documentada
   requerida antes de mover #1.

---

## 5. TESTS DE CARACTERIZACIÓN recomendados (antes de mover cada pieza)

Cobertura actual verificada:

| Test existente | Cubre | NO cubre (hueco a llenar ANTES de mover) |
|---|---|---|
| `ui/OnboardingPolicyTest` (15) | `needsForegroundLocationPermission`, `isLocationComplete`, `isStepComplete`, `gateSteps`, `dynamicSteps` | Secuencia de request de la Activity (L212-293), fallback vendor, escritura de `backgroundLocationAsked/vendorGuideDone`, parseo de repairSteps del intent, salto de índice L382-407 |
| `readiness/DeviceReadinessPolicyTest` (~20) | `DeviceReadinessPolicy.evaluate` (bucket, restricción, batería combinada, OEM, casos A-D de continuidad/recovery) | `DeviceReadinessChecker.evaluate(context)` (el objeto Android que la UI llama) y el mapeo veredicto→navegación en `startIfReady` |
| `ui/GpsDiagPolicyTest` (7) | `hasRecentFix/withoutFixMs/isSearching/describe` | `computeDiagRows` completo (armado de 14 secciones), `recoverEnabled`, textos de readiness/continuity |
| `ui/widget/JourneyWidgetStateTest` (10) | `elapsedDisplayMs/formatDuration*/stateFor` | PendingIntents (start directo vs stop confirmado), `online = netCause=="ok"`, `buttonBackground` |
| `readiness/ContinuityTestPolicyTest`, `RecoveryTestPolicyTest` | políticas puras | invocación desde DiagnosticsActivity (L106-110, L132-143) |
| `platform/UpdateManagerTest` (6) | `isNewer` (numérico, sufijos), parseo GitHub release | `checkForUpdate` completo: escritura `lastUpdate*`, banner vs diálogo vs notificación, `updateCheckInFlight`, clear de notificación |
| `outbox/PendingAlertPolicyTest` | `isAbnormal` | su consumo en `refreshState` (L747-758) y orden de líneas del log |
| `readiness/PermissionHealthTest` | `check/repairStepsFor` | auditoría post-OTA en MainActivity (disparo onCreate L238 y onResume L348, y la escritura de `appVersionCode`) |
| `architecture/ArchitectureDependencyTest` | reglas de paquetes | hay que actualizarlo al crear `ui/*model*` o coordinators |

Tests de caracterización NUEVOS (estrictamente comportamiento actual, antes de refactor):

1. **`StartJourneyCharacterizationTest`** (con fakes de Checker/MqttManager):
   (a) sin permisos → abre Onboarding sin tocar servicio; (b) permisos OK +
   gate NOT_READY → abre Onboarding (sin start); (c) READY → muta
   `trackingEnabled=true` + `trackingState=SERVICE_RECOVERY` + `start()==true`;
   (d) `start()==false` → rollback completo (trackingEnabled=false,
   lastStartError, estado DISABLED_BY_USER) y diálogo.
2. **`LoginFlowCharacterizationTest`**: (a) campos vacíos → diálogo sin tocar
   MQTT; (b) éxito → credenciales persistidas + RemoteConfig.fetch llamado +
   diálogo bienvenida; (c) fracaso → credenciales NO persistidas + mensaje.
3. **`ToggleFlowCharacterizationTest`**: (a) con tracking → confirm-dialog
   evento; (b) sin credenciales → Toast; (c) con credenciales → re-test MQTT
   antes de start (comportamiento hoy: doble test de conexión en flujo login+
   toggle).
4. **`RecoveryReactivationCharacterizationTest`**: killed + `lastStartError`
   → diálogo una vez por sesión (flag no re-dispara tras re-onResume), aceptar
   muta las 3 piezas y llama start.
5. **`UpdateCheckCharacterizationTest`**: simulacro de UpdateManager: null
   (error, Toast solo si !auto, notificación limpiada), sin versión nueva,
   versión nueva auto (banner) vs manual (diálogo), guard `updateCheckInFlight`.
6. **`PostOtaAuditCharacterizationTest`**: `onboardingDone && versionCode
   distinto && missing` → navegación repair + NO escribe appVersionCode;
   missing vacío → escribe appVersionCode; onResume: una vez por sesión.
7. **`DiagRowsCharacterizationTest`**: fijar DiagSnapshot → strings por sección
   (golden test liviano) para congelar `computeDiagRows` antes de extraer.
8. **`OnboardingIndexCharacterizationTest`**: lista dinámica se encoge → índice
   salta al primer pendiente; paso actual completo → salto; índice fuera de
   rango → coerce.
9. **`WidgetPendingIntentCharacterizationTest`** (Robolectric o extraer
   builder de intents puro): start→servicio directo; stop→MainActivity
   EXTRA_CONFIRM_STOP.

---

## 6. PLAN DE FASES (compatible con locking: MainActivity bloqueado)

> Restricción: `ui/MainActivity.kt` está congelado (regla 50). Las fases 1-2 no
> lo tocan; la fase 3 es el único corte coordinado sobre él, idealmente un solo
> commit breve que solo reenvía a VM/coordinators.

- **Fase 0 — Red de seguridad (sin tocar código productivo)**: escribir los
  tests de caracterización §5.1-§5.8 (todos son JVM puros salvo que se opte por
  Robolectric). Actualizar `ArchitectureDependencyTest` con las reglas nuevas
  (p.ej. `ui/` no llama `MqttManager.testConnection` directo).
- **Fase 1 — Sin archivos bloqueados**:
  1. `DiagnosticsActivity`: extraer `DiagnosticRowsPresenter` (puro) +
     `DiagnosticsViewModel` (IO); eliminar `Thread+runBlocking` → `viewModelScope`.
     Riesgo bajo, archivo no bloqueado, tests §5.7 de red.
  2. `DiagnosticsActivity`: `recoverService` y `startContinuity` delegan a
     `RecoveryReactivation`/`ContinuityTracker` (ya existen) — solo se quita la
     mutación inline.
- **Fase 2 — Sin archivos bloqueados**:
  3. `OnboardingActivity`: `OnboardingViewModel` (pasos, gate, índice) +
     `PermissionFlowCoordinator`. Activity queda con 3 launchers + startActivity.
     Los tests §5.1 (policy) ya existen; añadir §5.8.
  4. Widget: unificar color tokens; builder de PendingIntents testeable;
     decisión documentada sobre ACTION_START sin gate (riesgo 5).
- **Fase 3 — Ventana con MainActivity desbloqueado (corte único)**:
  5. Introducir `MainViewModel` + `DashboardUiState`: mover estado (§3.1),
     ticker, eventos; `startIfReady/login/finish/checkForUpdate/repair-audit`
     delegan a coordinators ya creados en fases 1-2 (los coordinators son
     nuevos archivos: se pueden escribir ANTES y testear contra el
     comportamiento congelado; MainActivity solo cambia su interior).
     Los tests §5.1-§5.6 deben pasar idénticos.
- **Fase 4 — Cruces (opcional, bajo demanda)**:
  6. `JourneyClockFlow` único (servicio → VM → MetricsRow), retirar doble ticker.
  7. `SplashActivity` + widget consumen el mismo snapshot/presenter del dashboard.

---

## 7. RESUMEN (≤15 líneas)

- No hay ningún ViewModel: MainActivity acumula ≈24 estados Compose, 2 timers,
  6 diálogos y 5 flujos de dominio (login MQTT, gate readiness, recovery,
  OTA completo, resumen de jornada) mutando `AppConfig` en 8 puntos.
- Onboarding evalúa `DeviceReadinessChecker` dentro de la recomposición y
  escribe `AppConfig` desde callbacks de launcher; su lógica pura ya está
  bien extraída (`OnboardingPolicy`, 15 tests).
- Diagnostics tiene ~420 líneas de ensamblado de texto con un hilo crudo
  + `runBlocking`; `GpsDiagPolicy` ya está extraída y testeada.
- El widget arranca el servicio **sin** login ni gate (inconsistencia con la
  UI) y duplica tokens de color; su estado puro ya está testeado.
- Orden de extracción por riesgo/beneficio: (1) recovery reactivation,
  (2) login MQTT, (3) startIfReady/gate, (4) diag refresh, (5) OTA.
- Con MainActivity bloqueado: fases 1-2 (Diagnostics, Onboarding, widget) no lo
  tocan; los coordinators nuevos se testean antes con caracterización; la fase 3
  es un único corte que solo reenvía a VM/coordinators.
- Los 5 riesgos duros: navegación por veredicto del gate, reloj NTP-inmune,
  disparadores múltiples del OTA, flags de sesión en VM, invariante del
  widget ACTION_START.
