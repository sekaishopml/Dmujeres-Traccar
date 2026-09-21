# Flujos (secuencias auditables)

## 1. Jornada manual (inicio → captura → fin)

```mermaid
sequenceDiagram
    actor T as Trabajador
    participant UI as App (UI)
    participant S as TrackingService (FGS)
    participant L as LocationEngine
    participant O as Outbox (Room)
    participant MQ as MQTT
    participant SV as Servidor

    T->>UI: Iniciar jornada
    UI->>S: startTracking (acción manual)
    S->>S: FGS + wake lock + geocerca
    S->>L: requestLocationUpdates
    loop Mientras la jornada esté activa
        L->>L: fix GNSS (fused o AOSP)
        L->>O: aceptado por FixFilter + acceptByRule
        O->>MQ: telemetría
        MQ-->>S: ack
        S->>O: confirmado (se borra)
    end
    T->>UI: Detener jornada
    UI->>S: stopTracking
    S->>SV: resumen de jornada (journeyId, distancia, puntos)
    S->>L: stop (quita registros)
```

## 2. Posición cuando no hay red (offline)

```mermaid
sequenceDiagram
    participant L as LocationEngine
    participant O as Outbox (Room)
    participant D as PositionOutboxDispatcher
    participant H as HTTP trickle
    participant MQ as MQTT

    L->>O: fix aceptado (sin red)
    Note over O: la cola retiene; nada se pierde<br/>mientras la jornada siga viva
    D->>D: red disponible (ConnectivityObserver)
    D->>MQ: lote por MQTT
    alt MQTT sin ack
        D->>H: trickle HTTP /positions
        H-->>D: 200 OK
    end
    D->>O: confirmados → se borran
```

## 3. Embudo de salud (F0)

```mermaid
sequenceDiagram
    participant W as TrackingWatchdog (30 s)
    participant A as HealthFunnelAccumulator
    participant HM as TrackingHealthMonitor
    participant U as HealthUploader
    participant SV as MobileHealthService
    participant DB as tc_device_health

    W->>A: tick(moving, now)
    W->>HM: persistHeartbeat (cada 5 min)
    HM->>HM: cierre de bucket → funnel JSON
    HM->>U: snapshot + funnel
    U->>SV: POST /api/mobile/v1/health
    SV->>SV: valida funnel (≤512, empieza con {)
    SV->>DB: INSERT attributes.funnel
    Note over DB: vista v_device_funnel_daily<br/>agrega por dispositivo y día
```

## 4. Rescate (GPS caído / proceso congelado)

```mermaid
sequenceDiagram
    participant G as Geocerca encadenada
    participant S as TrackingService
    participant W as TrackingWatchdog
    participant R as RescueWindow
    participant F as FCM
    participant SV as Servidor

    Note over G: se re-centra en cada fix (cada 150 m)
    G->>S: salida de geocerca (despierta sin internet)
    S->>W: reanuda captura + ventana CPU 150-180 s
    SV->>F: RECOVERY push si el equipo está mudo
    F->>S: mensaje de alta prioridad
    S->>SV: ack de recuperación (recovery-ack)
    W->>W: sin callbacks FLP > 10 min → reinicia motor
    W->>W: sin fix > 90 s → poll one-shot con backoff
```

## 5. OTA en modo piloto

```mermaid
sequenceDiagram
    participant A as App (UpdateChecker)
    participant O as OtaResource
    participant P as OtaRolloutPolicy
    actor M as macias (piloto)
    actor R as Resto de la flota

    A->>O: GET /ota?deviceId=…&versionCode=…
    O->>P: evalúa rollout.json
    P-->>M: update:true (allow list decisiva)
    P-->>R: update:false
    M->>A: descarga APK + verifica sha256
    A->>M: diálogo de instalación (permiso del sistema)
```

## 6. Liberación a toda la empresa (procedimiento)

```mermaid
flowchart TD
    A[Tag baseline + rama de release] --> B[Build APK firmado<br/>versionCode siguiente]
    B --> C[Pruebas: suites + test instrumentado<br/>migración Room]
    C -->|verde| D[Publicar con publish-ota.sh]
    D --> E[rollout.json: allow = pilotos]
    E --> F[Monitoreo 24-48 h:<br/>Cobertura30, embudo, alertas]
    F -->|sin regresiones| G[rollout.json: quitar allow<br/>percent 100]
    G --> H[Flota completa]
    F -->|regresión| I[paused:true + corrección]
```
