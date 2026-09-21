# QA_MATRIX.md — Matriz de QA (FASE 1/11/12)

Regla: ningún `UNKNOWN` se transforma en `PASS`; sin evidencia no hay PASS
(§53). Resultado permitido: `PASS`, `FAIL`, `PARTIAL`, `UNKNOWN`,
`NOT_TESTED`, `OEM_LIMITATION`.

## 1. Suites automáticas (ejecutadas 2026-09-16)

| Suite | Comando | Resultado | Evidencia |
|---|---|---|---|
| Server (Traccar + mobile) | `cd server && ./gradlew cleanTest test` | **821 tests, 0 fallos** (baseline 805) | `server/build/test-results/` |
| Mobile unit JVM | `cd mobile && ./gradlew :app:testDebugUnitTest` | **549 tests, 0 fallos** (baseline 507) | `mobile/app/build/test-results/` |
| Dashboard (node) | `node --test src/map/util/ src/common/util/ src/other/` | **80 tests, 0 fallos** | salida node |
| Mobile lint release | `./gradlew :app:lintRelease` | `BUILD SUCCESSFUL` | `mobile/app/build/reports/lint` |
| Dashboard lint (archivos nuevos) | `npx eslint src/common/util/dmujeresFleet.* src/reports/DmujeresHealthPage.jsx` | 0 errores | salida eslint |
| Release build + firma | `assembleRelease` + `apksigner verify` | firmado con clave release (no debug) | SHA256 `d7418f…4501` |

Tests nuevos por área:
- FASE 2/3: `LocationEnginePolicyTest`, `NotificationStatePolicyTest`,
  `TrackingHealthMonitorTest`, `SensorCoordinatorTest`.
- FASE 7: `MobileHealthServiceTest` (H2 real), `HealthUploaderTest`.
- FASE 5: `MobileContinuityPolicyTest`.
- FASE 6: (política ya cubierta; notificación validada por compilación/lint).
- FASE 8: `LocationSensorFusionTest`.
- FASE 9: `OemGuidanceProviderTest`.
- S1: `MobileApiKeyValidatorTest`.

## 2. Validación E2E real (servidor dev + BD real)

| Escenario | Método | Resultado | Evidencia |
|---|---|---|---|
| Ingesta salud E2E | `POST /api/mobile/v1/health` con clave real + device qa-f0 | `accepted:2` → filas en `tc_device_health` | SQL: 3 filas, healthstate LIVE/DEGRADED |
| Idempotencia salud | reenvío mismo `(device,ts)` | `duplicates:1` y sin fila extra | SQL count=1 |
| Throttle salud | 2º POST en <10 s | `throttled:true` | respuesta |
| Atributos device | tras ingesta | `mobile.healthState= LIVE`, `mobile.androidVersion=14` | SQL `attributes::jsonb` |
| Continuidad por sesión (qa-f0 real) | `GET /api/devices/47/continuity?sessionId=…` | journey 11h26m32s / tracking 1h01m56s / gap 10h29m35s / 9.02% / 250 fixes | HTTP 200 + JSON |
| Continuidad por journeyId (nuevo) | POST sintético con `journeyId` + GET `?journeyId=` | 100% con 2 fixes, sesión adjunta | HTTP 200 + SQL (datos de prueba eliminados) |
| Migración Liquibase 6.14.6 | restart server | applied + columnas/índice | `databasechangelog` + `\d` |
| OTA 1.1.3 | `GET /latest.json` y APK | HTTP 200, 8 260 528 B | curl |

## 3. Por escenario (§49)

| Escenario | Estado | Nota |
|---|---|---|
| GPS normal/poor/stale/lost/recovered | PASS (unit) | `FixFilterTest`, `FixRobustnessTest`, `LocationQualityTest`, `LocationSensorFusionTest` |
| GNSS fallback | PASS (unit) | `GnssFallbackPolicyTest` (main looper, idempotencia) |
| Red: WiFi/mobile caída, switch, DNS, server/MQTT down, HTTP fallback | PASS (unit) | `NetCauseTest`, `LinkStateTest`, `PositionOutboxDispatcherTest` |
| Outbox buffer/restart/replay/duplicado/seq/dead letter/backlog | PASS (unit) | `BufferDrainPolicyTest`, `DispatchRobustnessTest`, `OfflineRouteOrderTest`, `PositionOutboxMicroBatchTest` |
| Proceso: restart servicio/proceso/kill OEM | PARTIAL | unit + mecanismos implementados; kill OEM real sólo qa-f0 (cfreezer observado) |
| Screen ON/OFF/lock | PARTIAL (real) | qa-f0: huecos con screen-off por cfreezer (evidencia); PASS en ZTE puntual no sostenido |
| Doze / battery saver / bucket | NOT_TESTED (real) | requiere dispositivo accesible |
| FCM tracking/recovery/token/prioridad | PARTIAL | SEND/RECEIVE reales; E2E recovery `PENDING_VALIDATION` |
| Boot cold/reboot/update | NOT_TESTED (real) | código y tests de intents (`BootIntentsTest`) |
| Thermal normal/throttled | NOT_TESTED | sin datos térmicos reales recolectados aún |
| Instrumentación Room (migración 8→9) | COMPILA, NOT_TESTED | `HealthSnapshotMigrationTest` requiere dispositivo |

## 4. Endurance (§50/§12)

| Duración | Estado |
|---|---|
| 6h | NOT_TESTED |
| 12h | NOT_TESTED (existe evidencia parcial de jornada real de 11h26 con OEM limit) |
| 24h | NOT_TESTED |

No se declara soporte de ninguna duración no probada.

## 5. Dispositivos físicos

| Equipo | Estado | Evidencia |
|---|---|---|
| ZTE Z2450 (`qa-f0`) | OEM_LIMITATION | cfreezer; ADB `100.86.171.63:5555` **connection refused** el 2026-09-16 (offline/congelado) |
| Infinix X6531 (`santiago`) | PARTIAL | app 1.0.101, sin token FCM (561 `NO_TOKEN`); requiere actualización |
| Otros OEM | NOT_TESTED | sin hardware en este entorno |
