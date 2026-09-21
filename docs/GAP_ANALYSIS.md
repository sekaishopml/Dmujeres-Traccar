# GAP_ANALYSIS.md — Brecha real vs. arquitectura objetivo (FASE 0)

Referencia: `/tmp/maestro.txt` (81 secciones). Este documento traduce cada
exigencia a estado real y plan ejecutable. No hay PASS sin evidencia.

## 1. Diferencias respecto a la arquitectura objetivo

| # | Objetivo (prompt §) | Real | Gap |
|---|---|---|---|
| 1 | `TrackingService` como orquestador (§3) | 2819 líneas, 8+ responsabilidades | Refactor incremental con extracción |
| 2 | LocationEngine con frontera explícita (§8) | Todo dentro de `TrackingService` | Crear `LocationEngine`/`FusedLocationEngine`/`GnssLocationEngine`/`LocationEnginePolicy`/`LocationTimeoutController` |
| 3 | `LocationSensorFusion` (§9) | Sensores existen, sin fusionador | Crear fusionador puro (no fabrica lat/lon) |
| 4 | Perfiles LOW_POWER/NORMAL/HIGH_PRECISION/RECOVERY (§11) | `ActiveGpsPolicy` + `AdaptiveInterval` (parcial, batería no altera captura por decisión Fase A) | Documentar/medir; sin cambio silencioso |
| 5 | HealthSnapshots servidor (§21) | Room local 5 min; `tc_device_health` **vacía, sin escritor** | Subir snapshot + persistir + consultar |
| 6 | Continuity journey/tracking/gap/% (§6, §40) | `ContinuityTracker` local | Motor servidor + fórmulas + endpoint |
| 7 | Incident timeline automático (§41) | No existe | Construir desde posiciones/presencia/recovery/health |
| 8 | DeviceCapabilityProfile + OemGuidanceProvider (§22, §23) | `DeviceCaps`, `OemProtection`, `VendorSettings` (guias por OEM con Intent) | Fusionar en perfil declarativo + proveedor de guía |
| 9 | Escalera recovery LVL0-5 + user action (§28) | LVL0-3 + boot; **sin acción de usuario ni aviso "tap to resume"** | Añadir notificación de reanudación + política |
| 10 | Dashboard DMujeres (Live/Health/Journey/Alerts/Recovery/OEM/Readiness/QA) (§39) | Utils `deviceHealthState` + componentes parciales | Página de salud + endpoints server |
| 11 | Seguridad S1/S2/S3 (§32) | Los 3 abiertos (ver CURRENT_ARCHITECTURE) | Rotación de clave + NSC + keystore release |
| 12 | FCM E2E real (§65) | SEND y RECEIVE confirmados; recovery E2E pendiente | Depende de hardware/ventana real → PENDING |
| 13 | OEM matrix real (§45, §47) | Guías Xiaomi/Samsung/Honor/Infinix/Tecno/ZTE | Documentar matriz con evidencia por equipo |
| 14 | Endurance 6/12/24 h (§50) | No ejecutado | PENDING real-world |
| 15 | Docs finales (§67) | Parcial (`F2_TEST_MATRIX`, `fcm-recovery`, runbooks) | 9 docs objetivo + auditoría final |

## 2. Código duplicado / acoplado detectado

- `pauseCaptureForBuffer`/`resumeCaptureAfterBuffer` repiten manejo de estado
  de captura que `PositionBufferPolicy` ya decide (acoplamiento servicio-policy).
- Dos rutas de re-inicialización FLP dentro del watchdog (re-registro 2 min y
  re-init 15 min) comparten patrón remove/request → candidato a `LocationEngine`.
- Lógica de snapshot de salud repetida entre `persistHealthSnapshot` (servicio)
  y esquema `DeviceHealthSchema` (mobile) sin transporte.
- `MobilePresenceTracker` (775 líneas) concentra presencia + journey + umbrales;
  no se toca sin tests (F0), solo se documenta.

## 3. Riesgos críticos

| Riesgo | Evidencia | Mitigación |
|---|---|---|
| Puntos ciegos OEM (cfreezer ZTE) | frozen-list/unfreeze `reason=screen_on`; gaps ~10h29 en qa-f0 | Readiness + detección + recovery válido + guía; **sin hacks** |
| `tc_device_health` vacía | `SELECT count(*) = 0` | Implementar ingesta y persistencia (FASE 7) |
| S1 clave dev conocida en APK y server | `AppConfig.HTTP_API_KEY` y `.env` | Rotación con doble clave + eliminación del default |
| S2 cleartext | manifest + despliegue HTTP | NSC restrictiva + decisión TLS documentada |
| S3 debug signing | release sin `keystore.properties` | Keystore de release + checklist |
| NPE "invalid null looper" GNSS tras screen-off | stack real en campo | Guardas de looper + re-registro controlado |
| `tc_devices.attributes` VARCHAR(4000) | presupuesto en `MobileDiagnosticsService` | Mantener presupuesto en todo lo nuevo |
| Pérdida de evidencia por retención | políticas Room 24h/outbox | Preservar evidencia de incidente reciente |

## 4. Funcionalidades TERMINADAS (con tests)

Tracking FLP + GNSS fallback + plausibilidad; LocationQuality/SpeedEstimator;
Room outbox (buffer/replay/seq/dead letter/retention/dispatch lock);
MQTT presencia + HTTP posiciones con ACK/idempotencia; presencia servidor;
ingesta validada; F2 FCM (SEND + RECEIVE reales, ACK por etapas, política);
boot/update recovery; onboarding/readiness; diagnóstico de silencio;
session keeper por alarma; widget; OTA.

## 5. Parcialmente terminadas

| Área | Falta |
|---|---|
| Health snapshots | Transporte al servidor + persistencia + consulta |
| Continuidad | Cálculo y exposición servidor |
| Recovery escalera | Nivel "user action" y fallback cuando Android bloquea start en background |
| OEM | Perfil declarativo unificado y guía data-driven |
| Sensors | Fusionador con estados GPS_HEALTH/MOVEMENT_LIKELY/STATIONARY_LIKELY/REACQUISITION_REQUIRED |
| Dashboard DMujeres | Página de salud/incidentes y QA |
| Docs | 9 documentos objetivo |

## 6. Faltantes totales

`tc_device_health` writer + endpoint; continuity engine; incident timeline;
"tap to resume"; per-device key rotation; TLS; release keystore; página
dashboard; informes de endurance/OEM reales.

## 7. Plan por fases (orden del prompt §71)

| Fase | Trabajo | Verificación |
|---|---|---|
| F0 | Este documento + `CURRENT_ARCHITECTURE.md` | Revisión |
| F1 | Baseline ejecutado | 805/0 y 507/0 |
| F2 | Characterization tests: extraer constantes/modos puros ya testeados y añadir los que fijen comportamiento del servicio (notificaciones, outbox coord) | suite verde |
| F3 | Extracción `LocationEngine`/`TrackingNotificationController`/`OutboxCoordinator`/`SensorCoordinator` con delegación sin cambio | compile + 507/0 |
| F4 | `LocationSensorFusion` + health engine mobile (estados multidimensionales) | tests nuevos |
| F5 | Continuidad servidor (journey/tracking/gap/%) | tests + SQL real |
| F6 | Recovery user-action + tap-to-resume | tests + ADB si hay device |
| F7 | Health E2E (mobile→HTTP→`tc_device_health`) + timeline + endpoints | curl + SQL real |
| F8 | Adaptive/sensors refino | tests |
| F9 | DeviceCapabilityProfile + OemGuidanceProvider + matriz | tests + doc |
| F10 | S1 rotación doble clave, S2 NSC, S3 keystore release | build + curl 401/200 |
| F11 | QA dispositivo real disponible (ZTE) | ADB + SQL |
| F12 | Endurance 6/12/24 h | PENDING real-world |
| F13 | Release 1.1.3, SHA256, OTA, checklist, rollback | HTTP + apksigner |
| Final | `FINAL_PRODUCTION_AUDIT.md` + 9 docs | Revisión |

## 8. Archivos que se tocarán por fase

- **F3**: `mobile/app/src/main/java/com/dmujeres/traccar/location/TrackingService.kt`
  (y nuevos `location/LocationEngine.kt`, `location/FusedLocationEngine.kt`,
  `location/GnssLocationEngine.kt`, `location/TrackingNotificationController.kt`,
  `location/OutboxCoordinator.kt`, `location/SensorCoordinator.kt`);
  tests nuevos en `mobile/app/src/test/.../location/`.
- **F4**: `mobile/.../util/MotionSensor.kt`, `GyroSensor.kt`,
  `location/LocationSensorFusion.kt` (nuevo), tests.
- **F5/F7**: `server/src/main/java/org/traccar/mobile/` (nuevos
  `DeviceHealthStore`, `JdbcDeviceHealthStore`, `DeviceHealthResource`,
  `MobileHealthResource`, `MobileContinuityService`, `MobileIncidentTimeline`),
  `server/schema/changelog-6.14.6.xml`, `MainModule.java`.
- **F6**: `mobile/.../receiver/SessionKeeper.kt`, `util/Notifications.kt`,
  `location/TrackingService.kt`, tests.
- **F9**: `mobile/.../util/DeviceCaps.kt`, `util/OemProtection.kt`,
  `util/VendorSettings.kt`, nuevos `util/DeviceCapabilityProfile.kt`,
  `util/OemGuidanceProvider.kt`.
- **F10**: `mobile/app/build.gradle.kts`, `mobile/app/src/main/AndroidManifest.xml`,
  `res/xml/network_security_config.xml`, `config/AppConfig.kt`, `server Keys.java`,
  4 resources `Mobile*Resource.java`, `.env` (rotación), `keystore/`.
- **F13**: `mobile/app/build.gradle.kts` (versionCode), `dashboard/build/latest.json`,
  `dashboard/public/latest.json`.

## 9. Reglas de ejecución adoptadas

- No commit sin pedido explícito (el prompt no lo pide; no se hará).
- No reescribir F0; cada extracción mantiene comportamiento y suite verde.
- Secretos: nunca imprimir; rotación por procedimiento con doble clave.
- Lo no verificable se marca `PENDING_VALIDATION` / `BLOCKED — REQUIRES REAL-WORLD VALIDATION`.
