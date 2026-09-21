# Arquitectura de la app Android

Paquete raíz: `com.dmujeres.traccar` · minSdk 26 · Kotlin · FGS de ubicación.

## Capas y dirección de dependencias

```mermaid
flowchart TD
    subgraph Presentación
        UI[ui<br/>actividades, onboarding, widget]
    end
    subgraph Orquestación
        TRK[tracking<br/>TrackingService, watchdog, presencia, telemetría]
    end
    subgraph Dominios
        LOC[location<br/>LocationEngine, filtros, geocerca, fallback]
        HLT[health<br/>embudo, snapshots, subida]
        RCV[recovery<br/>FCM, geocerca-wake, ventana CPU, journal]
        OBT[outbox<br/>cola Room, dispatcher, cuarentena]
        TRP[transport<br/>MQTT, HTTP trickle, ack]
        SEN[sensors<br/>accel/giro/significant motion]
        OEM[oem<br/>autostart, batería]
        RDY[readiness<br/>permisos, hechos del equipo]
        DIA[diagnostics<br/>reporte y logs]
        CAP[capture<br/>receptor PendingIntent L1,<br/>bridge y dedupe]
    end
    subgraph Soporte
        DATA[data<br/>Room, RemoteConfig]
        CFG[config<br/>AppConfig]
        CORE[core<br/>MobileProtocol, normalizadores]
        PLAT[platform<br/>OTA, Sentry, arranque]
    end

    UI --> TRK
    TRK --> LOC & HLT & RCV & OBT & TRP & SEN & OEM & RDY & DATA & CFG & PLAT & CAP
    LOC --> CAP
    LOC --> SEN & CFG & CORE
    HLT --> DATA & CFG
    RCV --> DATA & CFG
    OBT --> DATA & CORE
    TRP --> CORE
    DATA --> CFG & CORE
    CFG --> CORE
    OEM --> CFG
    RDY --> CFG
    DIA --> CFG & CORE

    classDef forbidden fill:#fdd,stroke:#c00,stroke-dasharray: 4 4
    classDef note fill:#ffd,stroke:#aa0
```

Reglas prohibidas que el test de arquitectura verifica (resumen; detalle en
`docs/DEPENDENCY_RULES.md`):

```mermaid
flowchart LR
    CORE[core] -.->|prohibido| X1[config, data, health,<br/>location, tracking, ui, …]
    LOC[location] -.->|prohibido| X2[tracking, ui]
    HLT[health] -.->|prohibido| X3[tracking, ui]
    TRP[transport] -.->|prohibido| X4[outbox, data, tracking, ui,<br/>recovery, health, location]
    OBT[outbox] -.->|prohibido| X5[tracking, ui, health,<br/>location, recovery, transport]
    TRK[tracking] -.->|prohibido| X6[ui — única excepción:<br/>ui.widget]
    classDef forbidden fill:#fdd,stroke:#c00
    class CORE,LOC,HLT,TRP,OBT,TRK forbidden
```

## Flujo de captura (una jornada)

```mermaid
flowchart TD
    A[Usuario inicia jornada] --> B[TrackingService<br/>FGS + wake lock]
    B --> C[LocationEngine.requestLocationUpdates]
    C --> D{¿fused fiable?}
    D -- sí --> E[FLP PRIORITY_HIGH_ACCURACY<br/>ráfaga 10 s / quietud 60 s]
    D -- 3 fallos --> F[GPS_PROVIDER AOSP directo<br/>cadencia efectiva]
    E & F --> G[FixFilter.evaluate<br/>accuracy, ventana honesta, staleness]
    G -->|aceptado| H{acceptByRule<br/>dt ≥ frecuencia<br/>o ≥ 24 m<br/>o giro ≥ 15°}
    G -->|rechazado| I[contador de embudo<br/>por motivo]
    H -->|sí| J[Outbox Room<br/>cola offline]
    J --> K[PositionOutboxDispatcher]
    K --> L[MQTT telemetría<br/>dmj/v1/devices/&lt;id&gt;/telemetry]
    K --> M[HTTP trickle<br/>/api/mobile/v1/positions]
    L --> N{ack}
    M --> N
    N -->|ok| O[borra de cola]
    N -->|timeout| P[reintento / cuarentena]
```

## Decisión de cadencia (F1-A)

```mermaid
flowchart TD
    S[Movimiento del teléfono<br/>MotionSensor] --> E[MovementStartPolicy]
    F[Fix GNSS: speed,<br/>desplazamiento, frescura] --> E
    E -->|sensor MOVING sin fix| C[MOVEMENT_CANDIDATE<br/>ráfaga 10 s]
    E -->|speed ≥ 5 m/s| V[MOVING<br/>ráfaga 10 s]
    E -->|sin fix y sin sensor| Q[STATIONARY<br/>heartbeat 60 s]
    E -->|quieto confirmado| Q
    C & V -->|heartbeat de quietud| HB{¿sensor dice MOVING?}
    HB -->|sí| C
    HB -->|no| Q
```

## Salud, rescate y OEM

```mermaid
flowchart LR
    subgraph Evidencia
        H1[TrackingHealthMonitor<br/>bucket 5 min]
        H2[TrackingWatchdog<br/>cada 30 s]
        H3[FreezeEvidence<br/>reinicios/freezes]
    end
    subgraph Rescate
        R1[Geocerca encadenada<br/>cada 150 m, sin internet]
        R2[FCM recovery<br/>push + ack]
        R3[RescueWindow<br/>ventana CPU 150-180 s]
        R4[Reinicio de motor FLP<br/>sin callbacks 10 min]
    end
    H1 --> U[HealthUploader<br/>/api/mobile/v1/health]
    H3 --> U
    R1 & R2 & R3 & R4 --> TRK[TrackingService]
    U --> SRV[(tc_device_health<br/>+ embudo en attributes)]
```

## Configuración remota

`data/RemoteConfig.kt` aplica al iniciar sesión lo que el panel define por
dispositivo (`/api/mobile/v1/config`): intervalo base, tamaño de cola, política de
buffer, timeouts y umbrales de filtros. Si el servidor no responde, la app conserva
los valores locales (sigue funcionando igual).
