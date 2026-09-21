# CURRENT_ARCHITECTURE.md — Arquitectura real encontrada (FASE 0)

> Auditoría del repositorio hecha el 2026-09-16 sobre el estado en disco
> (sin modificar código). Complementa `docs/ARCHITECTURE_FINAL.md` (destino).
> Fuente de verdad: código + DB + pruebas. Nada aquí es aspiracional.

## 1. Repositorio

Monorepo git con submódulos:

| Ruta | Contenido | Stack |
|---|---|---|
| `server/` (submódulo) | Fork de Traccar 6.14.5 + paquete `org.traccar.mobile` | Java 17, Gradle, JAX-RS, Hikari, Liquibase |
| `dashboard/` (submódulo) | Traccar Web (React 19 + MUI 9 + MapLibre) + utilidades DMujeres | Node, Vite, `node --test` |
| `mobile/` | App Android | Kotlin, Compose, Room, FLP, MQTT/EMQX, FCM |
| `infrastructure/` | scripts dev (server, mqtt-users, timescale, backup/restore) | bash + docker |
| `docs/` | documentación operativa (F2, runbooks, Sentry) | Markdown |

- App: `applicationId com.dmujeres.traccar`, `versionCode 112`, `versionName 1.1.2`,
  `minSdk 26`, `targetSdk 35`, `compileSdk 35`.
- No hay commits pendientes en este trabajo: el working tree ya venía con
  modificaciones (F1/F2/P1/P2) sin commitear; FASE 0 no tocó código.

## 2. Plano de tracking (mobile)

Cadena real de datos (verificada en `TrackingService`):

```
DeviceReadinessChecker/Policy  →  Onboarding/Readiness  (gates, no bloquea tracking si ya corre)
TrackingService (Service, FGS location)
  ├─ FusedLocationProviderClient (requestLocationUpdates, intervalo adaptativo)
  │    └─ callback ordenado por FixTime (elapsedRealtimeNanos primero)
  ├─ GNSS fallback (LocationManager GPS_PROVIDER listener directo) cuando no hay fix fresco
  ├─ FixFilter / isPlausibleFix (anti salto, accuracy, coherencia de tiempo)
  ├─ LocationQuality (EXCELLENT..INVALID, confidence)
  ├─ SpeedEstimator / MotionSensor / GyroSensor (opcionales, no fabrican coordenadas)
  ├─ Room outbox: PendingPosition + SequenceState + DeadLetter + DispatchLock
  │    ├─ HTTP POST /api/mobile/v1/positions (LOTE FIFO con ACK de negocio) ← transporte PRIMARIO
  │    └─ MQTT publish dmj/v1/devices/{uniqueId}/telemetry ← presencia/heartbeat
  └─ MqttManager (reconnect gate, LWT, status)
```

Notas de arquitectura real (difieren del texto aspiracional del prompt):

1. **HTTP es el transporte primario de posiciones**, MQTT transporta presencia
   (comentario y código en `watchdogLoop`/`PositionOutboxDispatcher`).
   El prompt describe MQTT primario + HTTP fallback; se preserva F0.
2. `TrackingService.kt` = 2819 líneas. Responsabilidades mezcladas:
   orquestación + ubicación + GNSS + sensores + notificaciones + outbox +
   watchdog + salud + sesión + arranque/parada.
3. Persistencia local de salud (`HealthSnapshot`) **no se sube al servidor**
   (solo Room; retención 24 h). `tc_device_health` existe en DB pero vacía y
   sin código Java que la escriba.

## 3. Plano de health/recovery (mobile)

| Componente | Estado real |
|---|---|
| `MotionSensor`, `GyroSensor` | Implementados; opcionales; no generan lat/lon |
| `ActiveGpsPolicy` (poll activo + min-distance adaptativa) | Implementado y probado |
| `AdaptiveInterval` | Lógica adaptativa de intervalo (tests) — batería no altera captura (decisión Fase A) |
| `ContinuityTracker`/`ContinuityTestPolicy` | Implementados (medición local) |
| `DeviceReadinessChecker/Policy` | Implementado (gates de onboarding) |
| `TrackingHealthPolicy`, `RecoveryTestPolicy`, `ForegroundGuardPolicy`, `GnssFallbackPolicy` | Implementados |
| `SessionKeeper` (alarma 15 min in-process + `setAndAllowWhileIdle`) | Implementado; la cadena depende de proceso vivo |
| `TrackingRecoveryWorker` (WorkManager auxiliar) | Implementado |
| `BootReceiver` (BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT) | Implementado |
| `RecoveryJournal` (estados honestos) | Implementado |
| FCM recovery (`FcmRecoveryMessagingService`, `FcmRecoveryPolicy`, `FcmTokenRegistrar`) | Implementado; SEND real confirmado; E2E recovery REAL pendiente |
| `DiagnosticsCollector/Reporter`, `SilenceDiagnosis`, `NetCause`, `DeviceCaps`, `OemProtection`, `VendorSettings`, `RemoteConfig`, `RttMeter` | Implementados |
| Notificación de reanudación por acción del usuario ("tap to resume") | **No existe** |
| Watchdog out-of-process | Parcial: `SessionKeeperReceiver` corre en proceso nuevo al broadcast, pero no hay fallback de acción de usuario si el start en background es bloqueado |

## 4. Plano servidor (org.traccar.mobile)

| Área | Clases | Observación |
|---|---|---|
| Ingesta | `MobileMqttConsumer`, `MobileHttpResource` (api), `MobileIngestionService`, `MobileEnvelope(Validator)`, `MobileAtomicPersistence`, `MobileMessageStore` | Validación + idempotencia + secuencia |
| Calidad | `MobileQualityFilter` | HIDE/REJECT con `rejectBreakdown` |
| Estado | `MobilePresenceTracker` (775 líneas), `MobilePresenceService`, `MobileSilenceMonitor`, `WatchdogPolicy` | Presencia SUSPECT/OFFLINE |
| Telemetría | `MobileTelemetryApplier`, `MobileTelemetryMonitor` | Atributos `mobile.*` en `tc_devices.attributes` (VARCHAR 4000 con presupuesto) |
| Journey | `MobileJourneyRegistry` | `mobile.journeyId` |
| Diagnóstico | `MobileDiagnosticsService`, `DiagnosticsResource` | Caja negra en atributos |
| F2 recovery | `FcmSender`, `FcmTokenStore`/`JdbcFcmTokenStore`, `FcmRecoveryPolicy`, `FcmRecoveryService`, `MobileRecoveryResource` | `tc_fcm_tokens`, `tc_recovery_event` |
| Salud | — | **Sin servicio ni endpoint**: `tc_device_health` sin escritor |
| Continuidad | — | Sin cálculo servidor de journey/tracking/gap/continuity |
| Timeline | — | Sin timeline de incidentes |

Persistencia: TimescaleDB en `dmj-db` (tc_positions hypertable). Migraciones
Liquibase DMujeres: `changelog-6.14.0..6.14.5` (mobile messages, leases,
device health, recovery events, fcm tokens + audit).

## 5. Dashboard

Traccar Web con parches DMujeres:

- `src/common/util/shift.js` → `deviceHealthState()` (espejo de
  `TrackingHealthPolicy`; OFFLINE/SILENT/DEGRADED/LIVE) + tests node.
- `src/map/util/motionV2.js`, `canonicalRouteGeometry`, `qualityLabel.js`,
  `DeviceSecondaryText`, `useDeviceStatus`, `DeviceSummary` (estado móvil).
- **No hay** página DMujeres de salud/incidentes/QA ni endpoints server
  dedicados consumidos por el dashboard.

## 6. Seguridad (estado real, sin secretos)

| Riesgo | Evidencia | Estado |
|---|---|---|
| S1 static X-Api-Key fallback | `AppConfig.HTTP_API_KEY = "dmj-dev-fallback-key"` embebida en el APK y **también** en `.env` del server (`MOBILE_HTTP_API_KEY=dmj-dev-fallback-key`, valor dev conocido) | ABIERTO |
| S2 cleartext | `AndroidManifest.xml: android:usesCleartextTraffic="true"`; despliegue actual HTTP `:999` (sin TLS en infra) | ABIERTO |
| S3 debug signing | `app/build.gradle.kts`: release usa `signingConfigs.getByName("debug")` cuando no existe `keystore.properties` (no existe); APK 1.1.2 publicado firmado con debug keystore | ABIERTO |
| FCM credentials | Server-side vía `GOOGLE_APPLICATION_CREDENTIALS` (`~/.config/dmujeres/secrets/firebase-adminsdk.json`, 0600, gitignored) | OK |
| Redacción de logs | tokens FCM solo por prefijo hash (`FcmTokenStore.tokenPrefix`) | OK |

## 7. Pruebas (baseline FASE 1, ejecutado 2026-09-16)

| Suite | Comando | Resultado |
|---|---|---|
| Server | `./gradlew test` (cleanTest) | **805 tests, 0 failures, 0 errors, 29 skipped** |
| Mobile | `./gradlew :app:testDebugUnitTest` | **507 tests, 0 failures, 0 errors** |
| Dashboard | `node --test` (utilidades) | existe infra de tests; ver `GAP_ANALYSIS.md` para detalle |
| Instrumentación | `mobile/app/src/androidTest` (migración Room) | Presente; no ejecutada (requiere dispositivo/emulador) |

## 8. Entornos

- Server dev: `bash infrastructure/scripts/run-server-dev.sh restart` → `:999`.
- DB: `docker exec dmj-db psql -U traccar -d traccar`.
- MQTT: EMQX `dmj-mqtt` (`:1883`).
- OTA: `dashboard/build/latest.json` + APK, servido por el mismo Traccar en `:999`.
- Dispositivo real disponible: ZTE qa-f0 por ADB (`adb connect 100.86.171.63:5555`,
  puede estar congelado/offline). Ningún otro hardware en este entorno.
