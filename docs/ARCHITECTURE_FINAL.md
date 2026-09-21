# ARCHITECTURE_FINAL.md — Arquitectura final (FASE 3/4)

> **SUPERSEDIDO (2026-09-17)**: este documento registra la ronda F3–F10. La
> descripción vigente del plano móvil es `ANDROID_ARCHITECTURE.md` +
> `ARCHITECTURE_AFTER.md`; el registro del refactor R1–R8 está en
> `REFACTORING_LOG.md`. Se conserva por trazabilidad (las decisiones de
> producto y servidor siguen vigentes).

Estado: implementado y probado (549 tests móviles / 0 fallos, 821 server / 0).
Este documento describe la arquitectura REAL tras el refinamiento; sustituye a
la descripción conceptual del prompt donde el proyecto decidió otra cosa
(documentado el porqué).

## 1. Plano de tracking (mobile)

```
TrackingService (orquestador de ciclo de vida, 2021 líneas — era 2819)
├── TrackingSessionController   → sesión/jornada: sessionId, bootId, anclas
│                                 monotónicas, elapsed, detección paso de reloj
├── LocationEngine              → FLP + GNSS fallback + polling + timeouts
│   ├── LocationTimeoutController (re-solicitud 2 min, re-init 10/15 min,
│   │                               GNSS forzado, alerta sin satélites)
│   └── LocationEnginePolicy      (tabla pura de tiempos, caracterizada)
├── SensorCoordinator           → MotionSensor/GyroSensor (opcionales)
├── LocationSensorFusion        → GPS_HEALTH / MOVEMENT_LIKELY / REACQUISITION
├── TrackingHealthMonitor       → snapshots Room (HEARTBEAT/STATE_CHANGE/CRITICAL)
├── OutboxCoordinator           → drenaje single-flight, debounce, cierre
├── TrackingNotificationController → FGS claim/reassert, estado, alertas
│   └── NotificationStatePolicy (qué transiciones alertan)
└── onNewLocation (pipeline)    → FixFilter → LocationQuality → Room
```

Direcciones de datos:

```
FLP / GNSS listener / poll one-shot
    └→ onNewLocation → invalidLocationReason → isPlausibleFix (FixFilter)
         └→ regla OR Traccar → dao.insertWithinLimit (Room, transaccional)
              └→ PositionOutboxDispatcher (HTTP primario / MQTT presencia)
                   └→ servidor (validación, idempotencia, tc_positions)
```

Decisiones preservadas de F0 (no se cambian sin regresión equivalente):
- **HTTP es el transporte primario de posiciones** (lotes FIFO con ACK de
  negocio); MQTT transporta presencia/heartbeat. El prompt sugiere lo inverso;
  cambiarlo habría reescrito un subsistema validado en campo.
- Sensores solo evidencia: nunca generan lat/lon (verificado por tests).
- El perfil adaptativo ajusta distancia (0 m quieto / 15 m en marcha) e
  intervalo (5 s en marcha) sin sacrificar confiabilidad en silencio.

## 2. Plano de health/recovery (mobile)

```
TrackingHealthMonitor → Room (health_snapshots, retención 24 h)
        │                     │
        │                     └→ HealthUploader → POST /api/mobile/v1/health
        │                                          (idempotente (device, ts))
        └→ healthState (TrackingHealthPolicy) → telemetría presencia

Recovery escalera (implementada):
LVL0 FGS normal
LVL1 self-recovery in-process (re-registro, re-init FLP, polling)
LVL2 TrackingRecoveryWorker (WorkManager, auxiliar)
LVL3 SessionKeeper (alarma 2/15 min; corre en proceso nuevo al disparar)
LVL4 BootReceiver (BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT)
LVL5 Acción de usuario → notificación "Toca para reanudar" (FASE 6)
```

## 3. Servidor (org.traccar.mobile)

```
POST /api/mobile/v1/positions     (lotes; valida envelope+seq+idempotencia)
POST /api/mobile/v1/health        (NUEVO: snapshots de salud)
POST /api/mobile/v1/diagnostics   (caja negra en atributos)
POST /api/mobile/v1/fcm-token     (F2)
POST /api/mobile/v1/recovery-ack  (F2, ACK por etapas)
GET  /api/devices/{id}/continuity (NUEVO: continuidad por jornada/sesión)
MQTT dmj/v1/devices/{id}/telemetry → MobileMqttConsumer
```

Módulos nuevos (FASE 4/5/7):
- `DeviceHealthStore`/`JdbcDeviceHealthStore` → `tc_device_health`
  (idempotencia (deviceid, ts), retención 90 días).
- `MobileHealthService` → validación whitelist, perfil de capacidad,
  atributos `mobile.healthState/healthAt/healthOutbox/androidVersion/...`.
- `MobileContinuityPolicy` (pura) + `MobileContinuityService` → journeyTime,
  trackingTime, gapTime, continuity%, gaps y desglose por sesión.
- `MobileApiKeyValidator` → S1: clave actual + anterior (rotación) en los
  cuatro endpoints móviles.

Persistencia: TimescaleDB en `dmj-db`. Migración nueva `changelog-6.14.6.xml`
(no se tocaron changelogs históricos). Aplicada y verificada en la BD real:
columnas sessionid/elapsedms/eventtype/reason/wallbucket + índice único.

## 4. Dashboard (capa DMujeres)

- `src/common/util/dmujeresFleet.js` (puro, tests node): filas de flota,
  continuidad, formatos, orden. Sin inventar UNKNOWN→PASS.
- `src/reports/DmujeresHealthPage.jsx` (`/reports/dmujeres`): tabla de flota
  (salud, jornada, GPS, red, pendientes, recovery, OEM, readiness, app/FCM) y
  continuidad por jornada del dispositivo seleccionado.
- `src/common/util/shift.js::deviceHealthState` (existente) sigue siendo el
  espejo del estado para el mapa.

## 5. Piezas del prompt NO adoptadas (con motivo)

| Propuesta | Decisión | Motivo |
|---|---|---|
| MQTT primario / HTTP fallback | Se mantiene HTTP primario | F0 validado en campo; el cambio no aporta confiabilidad y arriesga regresión |
| `LocationEngine` con proveedor GNSS forzado en el request (API 31) | Fallback GPS_PROVIDER + re-solicitud | El listener directo ya resolvió el caso real (NPE de looper corregido con main looper) |
| Servicios/microservicios extra | No | §43 no feature creep |
| WakeLock permanente / root / Accessibility | No | §24 |
