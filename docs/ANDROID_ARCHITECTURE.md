# ANDROID_ARCHITECTURE.md — Arquitectura móvil vigente (post R1–R8)

> Documento de arquitectura del APK `com.dmujeres.traccar` (Kotlin, Compose,
> Room, FLP, MQTT, FCM). Sustituye a `ARCHITECTURE_FINAL.md` §1–§2 como
> descripción del plano móvil. Evidencia: mobile 586/0, compile y lint verdes
> (2026-09-17). Contratos de protocolo: `core/MobileProtocol.kt` +
> `docs/mqtt/protocol-v1.md`.

## 1. Capas y paquetes

```
ui/            Activities Compose, componentes, theme, widget (sin lógica crítica)
tracking/      ORQUESTADOR: TrackingService + controladores/políticas del ciclo
               de tracking (sesión, presencia, watchdog, notificación, cierre)
location/      Captura y calidad: LocationEngine, FixFilter, GNSS, timeouts
outbox/        Entrega durable: dispatcher, cuarentena, retención, flush cierre
transport/     MQTT (Paho) + Envelope (formato de cable)
health/        Snapshots locales + subida + estado multidimensional
diagnostics/   Caja negra: recolector/reporter, NetCause, silencio, telefonía, RTT
recovery/      Escalera: worker, SessionKeeper, BootReceiver, FCM recovery, journal
readiness/     Gates de onboarding/permisos del dispositivo (pre-tracking)
oem/           Perfil de capacidades + guías por fabricante
sensors/       MotionSensor/GyroSensor (evidencia; jamás coordenadas)
platform/      Servicios Android neutros: notificaciones, OTA, Sentry, estado
data/          Room (outbox, salud, secuencias, dead letters) + config remota
config/        AppConfig: preferencias tipadas + contadores/buckets de salud
core/          Valores/protocolo compartidos (MobileProtocol, MqttStatus,
               TrackingState, RecoveryOutcome, BootId, formatters, estimadores)
```

Dependencias permitidas y enforcement: `DEPENDENCY_RULES.md`.
Decisión de módulos: `MODULE_BOUNDARIES.md` (1 módulo `:app`, justificado).

## 2. Plano de captura (flujo real)

```
LocationEngine (FLP request adaptativo, intervalo 5–10 s)
  ├─ GNSS status listener (LocationManager, fallback GPS_PROVIDER + main looper)
  ├─ poll one-shot activo si no hay fix fresco (ActiveGpsPolicy)
  └─ LocationTimeoutController (re-solicitud 2 min; re-init 10/15 min)
        │ onFix ordenado por FixTime (elapsedRealtimeNanos > wall)
        ▼
TrackingService.onNewLocation
  ├─ invalidLocationReason (lat/lon/accuracy)
  ├─ FixFilter.evaluate (anti-salto, accuracy, tiempo, ventana honesta)
  ├─ SpeedEstimator.choose (Doppler creíble / implícita acotada / unknown)
  ├─ LocationQuality.classify + confidence
  ├─ regla OR Traccar (24 m / 15° / frecuencia) → descarta o difiere
  └─ enqueueMutex.withLock → Room insertWithinLimit (transaccional)
        └─ PositionOutboxDispatcher.requestFlush / MqttManager.wakeDispatch
```

Reglas invariantes (con tests, no tocar sin regresión equivalente):

- Un fix jamás se fabrica: sin proveedor no hay coordenada (sensores = evidencia).
- `lastFixAt` avanza SOLO tras insert OK; si no, el heartbeat de presencia sigue.
- El orden de cierre es drenar → `ended` → drenar → 3 s → fin
  (`JourneyStopCoordinatorTest`).
- El claim FGS es el primer acto de `onStartCommand` (`ForegroundClaimPolicy`).
- Retención dura: 100 000 posiciones / 7 días (`OutboxRetentionPolicy`).

## 3. Plano de entrega

| Camino | Transporte | Confirmación | Uso |
|---|---|---|---|
| Posiciones | HTTP `POST /api/mobile/v1/positions` (lotes FIFO) | ACK de negocio accepted/duplicate | Primario |
| Presencia | MQTT `dmj/v1/devices/{id}/telemetry` QoS1 | ACK `.../ack` | En vivo; HTTP si MQTT no entrega |
| Salud | HTTP `/api/mobile/v1/health` | HTTP 2xx por lote | Snapshots Room |
| Diagnóstico | HTTP `/api/mobile/v1/diagnostics` | 204 | Caja negra |
| FCM/recovery | HTTP `/fcm-token`, `/recovery-ack` | 2xx | Registro y ACK por etapas |

`PositionOutboxDispatcher` es el ÚNICO dueño de borrar/actualizar la cola;
`transport` no conoce `outbox` (la cuarentena entra por callback desde el
servicio, ver `DEPENDENCY_RULES.md` §3).

## 4. Estado, salud y recuperación

Estados de tracking (`core/TrackingState`) publicados por
`TrackingNotificationController` con `TrackingStatePolicy` (pura) y
`NotificationStatePolicy` (alertas por transición, no por refresco).

Salud multidimensional (`HealthStateProvider` + `TrackingHealthPolicy`):
`TRACKING_ACTIVE / GPS_DISABLED / NETWORK_OFFLINE / MQTT_DISCONNECTED /
SERVER_UNAVAILABLE / PENDING_ACK_TIMEOUT / BATTERY_LOW / BUFFER_FULL /
PERMISSION_MISSING / SERVICE_RECOVERY / TRACKING_DISABLED_BY_USER`.

Escalera de recuperación (ver `RECOVERY.md` para operación):
LVL0 FGS normal → LVL1 self-recovery in-process → LVL2 WorkManager →
LVL3 SessionKeeper (alarma 2/15 min) → LVL4 BootReceiver → LVL5 aviso al usuario.
El veredicto honesto se persiste vía `core/RecoveryOutcome`.

## 5. Diagnósticos M–Q (dónde mirar cuando "no se ve el carro")

| ID | Pregunta | Fuente | Evidencia |
|---|---|---|---|
| **M** | ¿Qué ve el teléfono ahora mismo? | `DeviceTelemetrySampler`: red validada, transporte, señal 0–4, batería, permisos, GPS on/off | `AppConfig.netCause/netLabel`, presence payload |
| **N** | ¿En qué capa se rompió el enlace? | `diagnostics/LinkState` (transporte / Internet validada / sesión MQTT / reachability por ACK) | estado `NETWORK_OFFLINE` vs `MQTT_DISCONNECTED` vs `PENDING_ACK_TIMEOUT` |
| **O** | ¿Por qué no hay capturas? | Logs `LOCATION_RECEIVED`, `POSITION_EVALUATED`, `POSITION_REJECTED reason=…`, `POSITION_ACCEPTED+STORED`; `config.rejectBreakdown()` diario | `docs/QA_MATRIX.md`, `DiagnosticsActivity` |
| **P** | ¿El servicio estuvo vivo? | `health_snapshots` Room (HEARTBEAT 5 min / STATE_CHANGE / CRITICAL) + `/api/mobile/v1/health` | `tc_device_health` (server) |
| **Q** | ¿Quién responde por cada síntoma? | `docs/PRODUCTION_RUNBOOK.md` + `docs/INCIDENT_DIAGNOSIS.md` | tabla síntoma→acción |

Diagnóstico de silencio (`SilenceDiagnosis`) distingue por capas: GPS apagado,
sin permisos, buffer lleno, red caída, MQTT caído, doctrina OEM. Nunca
"desconocido" si hay evidencia.

## 6. Hilos y concurrencia

- Servicio: `serviceScope` (SupervisorJob + Dispatchers.Default; IO explícito
  con `withContext(Dispatchers.IO)` para Room/HTTP), reemplazado en cada
  arranque para no reutilizar un scope cancelado.
- Cierre: `stopControllerScope` + `JourneyStopCoordinator`; termina en Main
  (`finishStopping`) para tocar notificación/foreground.
- `enqueueMutex`: UNA instancia compartida por el pipeline de posiciones y
  `PresenceController` (serializa secuencia y evita intercalado de mensajes).
- Watchdog: loop de 30 s en el scope del servicio; sin hilos propios.
- Estado de proceso (aceptado por diseño): `GnssState`, `MqttStatus`,
  `MotionSensor`, `GyroSensor`, `PositionOutboxDispatcher` (señalización).

## 7. Sin atajos (por diseño)

- Sin Hilt/Koin: grafo explícito en `TrackingService.onCreate`.
- Sin EventBus global: callbacks + estado persistido.
- Sin WakeLock permanente, root ni Accessibility.
- Sin fabricar GPS ni declarar PASS sin evidencia: ver `TECHNICAL_DEBT.md` M–Q.
