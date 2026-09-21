# Arquitectura del servidor

Fork de Traccar 6.14.5 (`server/`), con el módulo propio `org.traccar.mobile`.
Puerto web único **999** (API + panel estático) y broker MQTT.

## Módulos

```mermaid
flowchart TD
    subgraph Entrada
        MQTT[MobileMqttConsumer<br/>dmj/v1/devices/&lt;id&gt;/telemetry]
        HTTP[Recursos REST<br/>/api/mobile/v1/*]
        WEB[Panel / dashboard<br/>estático + /api/*]
    end
    subgraph Validación
        V1[MobileApiKeyValidator]
        V2[MobileEnvelopeValidator]
        V3[MobileQualityFilter]
    end
    subgraph Dominio móvil
        ING[MobileIngestionService]
        PER[MobilePresenceService<br/>+ MobilePresenceTracker]
        TEL[MobileTelemetryApplier<br/>atributos mobile.*]
        HEA[MobileHealthService<br/>tc_device_health + funnel]
        REG[MobileJourneyRegistry<br/>jornadas activas en memoria]
        TIM[MobileTimelineService]
        MSG[MobileMessageStore<br/>tc_mobile_messages]
        REJ[MobileRejectionCounter]
    end
    subgraph Persistencia
        AP[MobileAtomicPersistence]
        DB[(PostgreSQL<br/>tc_positions, tc_devices,<br/>tc_device_health, tc_recovery_event,<br/>tc_fcm_tokens)]
    end
    subgraph Salida y control
        OTA[OtaResource + OtaRolloutPolicy<br/>rollout.json con allowlist]
        FCM[FcmRecoveryService + FcmSender]
        ALR[AdminAlertsResource<br/>+ AdminAlertsService]
        REP[JourneysReportResource<br/>+ JourneysReportProvider]
        WDG[WatchdogPolicy<br/>+ MobileSilenceMonitor]
    end

    MQTT --> V1 --> V2 --> V3 --> ING
    HTTP --> V1
    ING --> PER & TEL & HEA & REG & TIM & MSG & REJ
    PER & TEL & HEA & TIM & MSG --> AP --> DB
    WEB --> REP & ALR
    OTA --> WEB
    FCM --> DB
    WDG --> ALR
```

## Ingesta y rutas de datos

```mermaid
flowchart LR
    APP[App Android] -- MQTT telemetría --> C[MobileMqttConsumer]
    APP -- HTTP posiciones/salud/eventos --> R[Recursos REST]
    C & R --> VAL[Validación + calidad]
    VAL --> POS[tc_positions]
    VAL --> ATT[tc_devices.attributes<br/>mobile.*]
    VAL --> HLTH[tc_device_health]
    VAL --> REC[tc_recovery_event]
    POS --> DASH[Panel: mapa, replay, jornadas]
    ATT --> DASH
    HLTH --> DASH
    REC --> DASH
    DASH --> SUP[Supervisión y auditoría]
```

## Modelo de datos propio

```mermaid
erDiagram
    tc_devices ||--o{ tc_positions : "deviceid"
    tc_devices ||--o{ tc_device_health : "deviceid"
    tc_devices ||--o{ tc_recovery_event : "deviceid"
    tc_devices ||--o{ tc_fcm_tokens : "deviceid"
    tc_devices }o--|| tc_groups : "grupo/departamento"

    tc_devices {
        int id PK
        string name
        string uniqueid
        varchar attributes "mobile.* (version, jornada, bateria, gnss…)"
    }
    tc_positions {
        int id PK
        int deviceid FK
        timestamp fixtime
        double latitude
        double longitude
        double accuracy
        varchar attributes "motion, battery, origen…"
    }
    tc_device_health {
        int deviceid FK
        timestamp ts
        varchar eventtype "HEARTBEAT, STATE_CHANGE, CRITICAL…"
        varchar reason "NO_FRESH_FIX:motivo, …"
        jsonb attributes "funnel: embudo por bucket"
    }
    tc_recovery_event {
        int deviceid FK
        timestamp ts
        varchar eventtype "RECOVERY_SENT, TIMEOUT, BLOCKED…"
        varchar reason
    }
    tc_fcm_tokens {
        int deviceid FK
        varchar token
    }
    tc_groups {
        int id PK
        string name "DPTO. MARKETING, VENTAS, …"
    }
```

## Alertas y control (panel)

| Fuente | Regla | Severidad |
|---|---|---|
| `journey-silence` | jornada activa sin reportar > 10 min / > 30 min | warning / critical |
| `device-health` | `PROCESS_FREEZE_DETECTED` o `CRITICAL` en 24 h | critical |
| `recovery` | `RECOVERY_TIMEOUT` / `RECOVERY_BLOCKED` en 24 h | warning / critical |
| `health-funnel` | jornada activa, equipo vivo y sin buckets de embudo > 24 h / > 48 h (solo ≥ 1.1.22) | warning / critical |
| `backup` / `disk` / `watchdog` | respaldo > 24/36 h, disco > 75/90 %, watchdog | warning / critical |

## OTA (piloto → flota)

```mermaid
flowchart LR
    U[UpdateChecker] -- GET /api/mobile/v1/ota<br/>X-Api-Key + deviceId + versionCode --> O[OtaResource]
    O --> P[OtaRolloutPolicy]
    P -- lee --> J[dashboard/public/rollout.json<br/>percent, paused, allow]
    P -->|allow contiene el device| Y[APK + sha256 + notas]
    P -->|no| N[update:false]
    Y --> D[Descarga + verificación de hash]
    D --> I[Instalación PackageInstaller<br/>requiere permiso del usuario]
```

**Nota de política**: las notas de release visibles al usuario son solo
`Actualizar a la versión 1.x.x` (sin descripciones técnicas).
