# REFACTORING_LOG.md — Registro del refactor arquitectónico (R1–R8)

> Ejecución: 2026-09-16 → 2026-09-17. Continuación de una corrida interrumpida.
> Punto de partida inspeccionado con `git status --short` y `git diff --stat`:
> paquetes por dominio ya creados, `docs/ARCHITECTURE_BEFORE.md` ya escrito,
> y faltantes: R1 (tests), R3 (extracciones restantes), R4–R6, R7, R8.
>
> Regla permanente: sin cambios de comportamiento salvo bug demostrable; tests
> antes/después en cada paso; sin commits.

## 0. Estado inicial verificado (2026-09-17)

| Comprobación | Resultado |
|---|---|
| `./gradlew :app:testDebugUnitTest` | verde, **575 tests / 0 fallos** |
| `./gradlew :app:compileDebugKotlin` | verde |
| Server (sin tocar en esta ronda) | **821 / 0** (29 skipped) |
| Docs presentes | `ARCHITECTURE_BEFORE`, `ARCHITECTURE_FINAL`, `CURRENT_ARCHITECTURE`, `BASELINE`, F2/runbooks |
| Docs ausentes | `REFACTORING_LOG`, `TECHNICAL_DEBT`, `MODULE_BOUNDARIES`, `DEPENDENCY_RULES`, `ANDROID_ARCHITECTURE`, `ARCHITECTURE_AFTER` |

`TrackingService.kt` medía **2 156 líneas** (la ronda anterior lo había bajado
de 2 819 a 2 156; `ARCHITECTURE_FINAL.md` citaba 2 021 en su momento).

## R1 — Characterization tests faltantes

Se añadieron tests puros para lo que se iba a extraer/mover (nada se movió
antes de tener su decisión congelada):

| Test | Congela |
|---|---|
| `tracking/NetworkStatePolicyTest` (4) | etiquetas wifi/mobile/none, proveedor gps/network/fused/unknown, umbral fix rancio |
| `tracking/JourneyStopCoordinatorTest` (3) | orden drenar→ended→drenar→3 s→fin y tolerancia a fallos |
| `core/MobileProtocolTest` (3) | topics MQTT, paths HTTP y estados de jornada (contrato server) |
| `architecture/ArchitectureDependencyTest` (1) | reglas de dependencia entre paquetes |

Total móvil: **575 → 586 tests** (ninguno borrado ni desactivado).

## R2 — Organización por dominios

Ya estaba ejecutada por la corrida previa (paquetes `core/data/diagnostics/
health/location/oem/outbox/platform/readiness/recovery/sensors/tracking/
transport/ui`). En esta ronda solo se pulieron dos valores compartidos:

- `tracking/TrackingState.kt` → `core/TrackingState.kt` (lo usan health,
  location, readiness, recovery, ui y tracking; elimina 3 ciclos).
- `transport/MqttStatus.kt` → `core/MqttStatus.kt` (elimina el ciclo
  `diagnostics ↔ transport`).

## R3 — Extracción de responsabilidades restantes de TrackingService

Cada paso: mover VERBATIM + `:app:compileDebugKotlin` + `:app:testDebugUnitTest`.
Las 5 responsabilidades que la auditoría R0 marcó como ajenas al ciclo de vida
quedaron extraídas:

| Paso | Archivo nuevo | Qué salió del servicio |
|---|---|---|
| R3.1 | `tracking/NetworkStatePolicy.kt` | etiquetas de red/proveedor/umbrales (puro) |
| R3.2 | `tracking/DeviceTelemetrySampler.kt` | telemetría (red/señal/batería/permisos) + snapshot/persistencia `NetCause` |
| R3.3 | `tracking/PresenceController.kt` | `enqueuePresence` + heartbeat (mutex COMPARTIDO inyectado) |
| R3.4 | `tracking/ConnectivityObserver.kt` | receiver de modo avión + `NetworkCallback` (4 eventos) |
| R3.5 | `tracking/TrackingWatchdog.kt` | watchdog de 30 s completo (salud, link, alertas, trickle, cascada de estado) |
| R3.6 | `health/HealthStateProvider.kt` | `healthStateNow()` multidimensional |
| R3.7 | `tracking/JourneyStopCoordinator.kt` | secuencia de cierre de jornada (con test JVM) |
| R3.8 | `tracking/JourneySummaryPresenter.kt` | resumen + limpieza del cierre |

Resultado: **2 156 → 1 383 líneas** (−36 %). El pipeline `onNewLocation`
(~450 líneas) se quedó A PROPÓSITO: no existe harness JVM de caracterización y
las reglas del refactor prohíben mover código crítico sin él (ver
`TECHNICAL_DEBT.md` J-1). Detalle del "por qué" en `ARCHITECTURE_AFTER.md` §3.

Bugs de comportamiento cero: se conservaron literalmente umbrales, orden de
llamadas, logs, `runCatching`, y el uso del MISMO `enqueueMutex`.

## R4 — Dependencias

- Ciclos eliminados: `config↔recovery` (constantes de veredicto → `core/RecoveryOutcome`),
  `diagnostics↔transport` (MqttStatus → core), `outbox↔transport`
  (`MqttManager` ya no mueve cuarentena: recibe callback `quarantine` del borde),
  `tracking→ui` solo queda en `ui.widget` por callback de refresco.
- Ciclos restantes (aceptados y documentados): `readiness↔tracking`,
  `recovery↔tracking` (receivers/worker arrancan el servicio: punto de entrada
  Android) y `platform↔ui` (PendingIntent hacia `MainActivity`).
- Enforcement: `ArchitectureDependencyTest` escanea imports y falla ante una
  arista prohibida nueva.
- Documento: `docs/DEPENDENCY_RULES.md` (grafo real + tablas).

## R5 — Módulos Gradle

Decisión: **seguir con 1 módulo (`:app`)** evaluando criterios objetivos
(build time, ownership, reutilización, aislamiento de deps, API pública).
Disparadores de revisión y cortes naturales documentados en
`docs/MODULE_BOUNDARIES.md`.

## R6 — Config/protocolo centralizado

- `core/MobileProtocol.kt`: topics (`telemetryTopic`/`ackTopic`), puerto web,
  paths `/api/mobile/v1/*` y estados `started/ended`.
- Migrados a la constante: `AppConfig`, `PositionOutboxDispatcher`,
  `HealthUploader`, `DiagnosticsReporter`, `FcmTokenRegistrar`, `RemoteConfig`,
  `Envelope`, `PresencePolicy`, `TrackingService`, `UpdateManager`,
  `TrackingRecoveryWorker`.
- `MobileProtocolTest` congela el contrato (cambiarlo rompe la flota).

## R7 — Tests / lint

| Verificación | Resultado |
|---|---|
| `:app:testDebugUnitTest` | **586 / 0 / 0 / 0** |
| `:app:compileDebugKotlin` | verde |
| `:app:lintDebug` | verde (informe en `app/build/reports/lint-results-debug.html`) |
| Server `cleanTest test` (no tocado) | **821 / 0 / 0 / 29 skipped** |

## R8 — Documentación

Creados: `ARCHITECTURE_AFTER.md`, `ANDROID_ARCHITECTURE.md`,
`MODULE_BOUNDARIES.md`, `DEPENDENCY_RULES.md`, `REFACTORING_LOG.md` (este),
`TECHNICAL_DEBT.md`. Actualizado: `BASELINE.md` (re-baseline R) y cabecera de
`ARCHITECTURE_FINAL.md` (marcado como ronda anterior).

## Inventario de archivos de esta ronda

**Creados (main, 12):**
`core/MobileProtocol.kt`, `core/MqttStatus.kt`, `core/RecoveryOutcome.kt`,
`core/TrackingState.kt`, `tracking/NetworkStatePolicy.kt`,
`tracking/DeviceTelemetrySampler.kt`, `tracking/PresenceController.kt`,
`tracking/ConnectivityObserver.kt`, `tracking/TrackingWatchdog.kt`,
`tracking/JourneyStopCoordinator.kt`, `tracking/JourneySummaryPresenter.kt`,
`health/HealthStateProvider.kt`.

**Creados (test, 4):** `tracking/NetworkStatePolicyTest.kt`,
`tracking/JourneyStopCoordinatorTest.kt`, `core/MobileProtocolTest.kt`,
`architecture/ArchitectureDependencyTest.kt`.

**Eliminados (por mudanza):** `tracking/TrackingState.kt`,
`transport/MqttStatus.kt` (contenido íntegro en `core/`).

**Modificados (main, 20):** `tracking/TrackingService.kt` (gran refactor),
`tracking/TrackingNotificationController.kt` (callback de widget),
`health/TrackingHealthMonitor.kt` (import core), `location/LocationEngine.kt`,
`readiness/ContinuityTracker.kt`, `ui/MainActivity.kt`,
`ui/DiagnosticsActivity.kt`, `recovery/FcmRecoveryMessagingService.kt`,
`recovery/RecoveryJournal.kt` (delegación a core),
`config/AppConfig.kt` (protocolo + RecoveryOutcome),
`transport/MqttManager.kt` (quarantine por callback),
`outbox/PositionOutboxDispatcher.kt`, `health/HealthUploader.kt`,
`diagnostics/DiagnosticsReporter.kt`, `recovery/FcmTokenRegistrar.kt`,
`recovery/TrackingRecoveryWorker.kt`, `platform/UpdateManager.kt`,
`data/RemoteConfig.kt`, `transport/Envelope.kt`, `tracking/PresencePolicy.kt`.

**Docs creados/modificados:** los 6 + `BASELINE.md` + `ARCHITECTURE_FINAL.md`.

## Riesgos residuales de esta ronda

1. Las extracciones no tienen tests instrumentados (no hay emulador): la
   garantía es compile+unit y revisión verbatim. Ver `TECHNICAL_DEBT.md` M-1.
2. El pipeline de captura sigue en el servicio (decisión consciente).
3. No se tocó server/dashboard; su verificación es la suite existente.

---

# Ronda R2 — seguridad + frontera transport/data (2026-09-17 PM)

> Continuación sobre el árbol ya refactorizado (R1-R8 previos). Sin commits
> (autorización pendiente). Tag local de restauración: `refactor-r2-pre-refactor`;
> snapshot `backups/dmj-worktree-20260917-1352.tar.gz`.

## Hecho

| Fase | Resultado |
|---|---|
| R0 | Snapshot (tag + tar) y `REFACTOR_BASELINE.md` con versiones/métricas/tests |
| R1 | Integridad verificada: `data/*` completo (los `db/*` "borrados" eran la migración); `0` referencias a `com.dmujeres.traccar.db.*`; compile verde |
| R2 | **Fuga de ZIPs públicos** con `.env`/keystores retirada (404) + `pack-project.sh` seguro + ejemplos (`keystore.properties.example`) + **build release falla sin provisioning** (probado) + `SECURITY_BUILD.md` con plan de rotación |
| R7/R11 | **`transport → data` eliminado**: puerto `transport/ControlQueueStore` + adaptador `outbox/RoomControlQueueStore`; `DispatchLock` y `RttMeter` movidos a `core` (puros); `DependencyCycleTest` nuevo (ciclos congelados) |
| R28 | `ARCHITECTURE_AUDIT_R2.md`, `TESTING_STRATEGY.md`, `SECURITY_BUILD.md`, `REFACTOR_BASELINE.md`; deuda A-1..A-8 |

## Verificación (R31)

| Comprobación | Resultado |
|---|---|
| `:app:compileDebugKotlin` | OK |
| `:app:testDebugUnitTest` | **588/0** (586 → 588: +2 `DependencyCycleTest`; el resto sin cambios) |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleRelease` con provisioning | OK, firma de flota (`CN=Android Debug`, SHA-256 `867b…`) — OTA intacto |
| `:app:preReleaseBuild` sin `keystore.properties` | FALLA con error claro R2 (probado) |
| OTA republished | `http://68.168.20.219:999/latest.json` → 1.1.5, notas exactas |

## Archivos (ronda)

- **Nuevos (main):** `transport/ControlQueue.kt`, `outbox/RoomControlQueueStore.kt`, `core/DispatchLock.kt` (movido), `core/RttMeter.kt` (movido).
- **Nuevos (test):** `architecture/DependencyCycleTest.kt`; `core/RttMeterTest.kt` (movido).
- **Modificados:** `transport/MqttManager.kt` (puerto, sin data/outbox), `tracking/TrackingService.kt` (wiring), `outbox/PositionOutboxDispatcher.kt` (import lock), `mobile/app/build.gradle.kts` (gating R2), `mobile/keystore.properties` (provisioning de flota), `.gitignore` (`backups/`).
- **Nuevos (infra/docs):** `infrastructure/scripts/pack-project.sh`, 4 docs.

## No hecho en esta ronda (razones honestas)

- R8-R24 profundos (ViewModels UI, split AppConfig/MQTT, Clock, ciclo):
  documentados como deuda A-1..A-7 con plan; un "big bang" habría arriesgado
  el pipeline de tracking sin dispositivos para validar (R32/R38).
- Rotación de secretos expuestos: requiere ventana y flota en ≥1.1.5.

---

# Ronda R3 — auditoría 9 agentes + F13/F14/F9 (2026-09-17 tarde)

## Auditoría (R3-0..R3-INT)

9 agentes (A–I) entregaron `docs/audit/*.md`; integrados en `R3_MASTER_AUDIT.md`
+ `R3_AGENT_REPORT.md` (conflicto C/H resuelto: MIGRATION_8_9 existe sin
androidTest). Gate inicial: compile/tests/lint verdes (588/0 móvil, 821/0
server, 80/0 dashboard).

## F13 — Route segmentation (policy + matriz A–G)

| Ítem | Detalle |
|---|---|
| PROBLEMA | El panel/replay mostraba rectas sobre huecos y retrasos fantasma (+18 000 s) |
| CAUSA | B-1 `linkTracksFor` sin `shouldCut`; B-2 `syncDelayMs` mezcla UTC/local; B-4 parada fantasma sin tope de diámetro |
| CAMBIO | `map/util/routeSegmentation.js` NUEVO (RouteSegmentType/Policy/Analyzer puro, umbrales calibrados 100 m/45 s/200 m/300 s/teleport + DELIVERY_DELAY 10 min + backfill Δserver≤2 s∧Δfix>45 s; desambiguado por evidencia: heartbeats=CAPTURE_GAP, NetworkLost/Restored=RECOVERY_GAP, sin señales=UNKNOWN); `replayAudit.js` (serverTimeMs + syncDelayMs corregida con fallback; linkTracksFor respeta shouldCut); `pathDecimation.js` detectStops con tope de diámetro (2×radio) |
| RIESGO | Datos sin `serverTime`/eventos degradan a UNKNOWN (conservador); B-3 (panel↔línea fast-gap) queda ABIERTO (wiring de ReplayPage en fase 2/3) |
| TEST | `routeSegmentation.test.js` 12/12 (matriz A–G + regresiones joseph/stationary/heartbeats + MUST-NOT mutación); suite dashboard 73+12 → **85/0** (73+12? ver gate) |

## F14 — Dashboard (Fase 0 hotfix desplegada)

- B-1: conectores del replay solo si `!shouldCut` → **las rectas de joseph (2941 m)
  y macias (994/1957 m) desaparecen del mapa** (decisión del operador: no dibujar
  la recta en tramos sin evidencia).
- B-2: `syncDelayMs` con base correcta → los retrasos reales (2 s…36 592 s) son
  visibles; phantom `synced+clock` por +5 h estructural eliminado.
- B-4: parada fantasma de 994 m eliminada (no fabrica parada ni recta).
- `npm run build` publicado; OTA preservado y verificado (latest.json + APK 200,
  sha `ac9cf7d3…` intacto).

## F9 (parcial) — R-01 concurrencia

- **PROBLEMA**: AlertDialog sobre Activity destruida (BadTokenException) en
  callbacks de `testConnection` (hasta 12 s).
- **CAUSA**: `runOnUiThread` ignora lifecycle.
- **CAMBIO**: `runOnUiThreadSafe { }` con guard `isDestroyed||isFinishing` en los
  2 callbacks de `MainActivity` (archivo bloqueado, orquestador).
- **RIESGO**: bajo — los flags testing/loginTesting siguen reseteándose; en
  Activity destruida no hay estado visible que mantener.
- **TEST**: `:app:testDebugUnitTest` 588/0 (no hay test UI JVM para este path;
  caracterización pendiente en la lista T-2).

## Verificación de la ronda (gate F13/F14/F9)

- Dashboard: `node --test src/map/util/` → **73/0** (+12 segmentación en archivo
  aparte; runner global 85 con el resto).
- Build publicado y OTA verificado (HTTP 200, sha intacto).
- Móvil: compile + 588/0 (incluye R-01).
- Sin commits (esperando autorización).
