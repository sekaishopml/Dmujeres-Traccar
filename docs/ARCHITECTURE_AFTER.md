# ARCHITECTURE_AFTER.md — Estado tras el refactor R1–R8 (2026-09-17)

> "Después" verificable del refactor pedido. Complementa
> `ARCHITECTURE_BEFORE.md` (auditoría R0, sin cambios de código) y sustituye a
> `ARCHITECTURE_FINAL.md` como descripción vigente del plano móvil (ese
> documento queda como registro de la ronda F3–F10). Detalle ampliado:
> `ANDROID_ARCHITECTURE.md`, `MODULE_BOUNDARIES.md`, `DEPENDENCY_RULES.md`.
> Registro cronológico: `REFACTORING_LOG.md`. Deuda: `TECHNICAL_DEBT.md`.
>
> Evidencia: mobile **586 tests / 0 fallos** (`./gradlew :app:testDebugUnitTest`),
> `:app:compileDebugKotlin` y `:app:lintDebug` verdes; server **821/0** (29
> skipped) sin tocar en esta ronda; dashboard fuera de alcance.

## 1. Números del refactor

| Métrica | Antes (R0) | Después | Δ |
|---|---|---|---|
| `TrackingService.kt` | 2 156 líneas | **1 383** | −36 % |
| Paquetes por dominio | `util` (25), `db`, `mqtt`, `location` mezclados | 15 dominios + `ui` | — |
| Ciclos de paquete | 9 | 3 (todos de entrada/UI, documentados) | −6 |
| Tests móviles | 575† (549 en F1) | **586** | +11 |
| Archivos main / test | 100 / 78 | 106 / 82 | +6 / +4 |
| `:app:lintDebug` | no ejecutado en R0 | verde (0 errores) | — |

† El working tree de R0 ya tenía 575 tests de la ronda anterior; el baseline
publicado en F1 era 549. En esta ronda no se borró ni desactivó ningún test.

## 2. Plano de tracking (mobile) — orquestador delgado

```
TrackingService (ciclo de vida FGS + pipeline de captura, 1 383 líneas)
├── TrackingSessionController      sesión/jornada, elapsed monotónico, pasos de reloj
├── LocationEngine                 FLP + GNSS fallback + polling + timeouts
│   ├── LocationTimeoutController  re-solicitud/re-init/GNSS forzado (políticas puras)
│   └── LocationEnginePolicy       tabla pura de tiempos (caracterizada)
├── SensorCoordinator              Motion/Gyro (evidencia, jamás lat/lon)
├── LocationSensorFusion           GPS_HEALTH / MOVEMENT / REACQUISITION
├── TrackingHealthMonitor          snapshots Room (HEARTBEAT/STATE_CHANGE/CRITICAL)
│   └── HealthStateProvider        estado multidimensional consolidado (R3)
├── OutboxCoordinator              drenaje single-flight + cierre (deadline)
├── PresenceController             encolado de presencia + heartbeat (R3)
├── ConnectivityObserver           modo avión + NetworkCallback (R3)
├── DeviceTelemetrySampler         red/señal/batería/permisos + snapshot NetCause (R3)
├── TrackingWatchdog               tick de 30 s: salud, link, alertas, trickle (R3)
├── JourneyStopCoordinator         drenar→ended→drenar→3 s→fin (R3, con test JVM)
├── JourneySummaryPresenter        resumen/limpieza del cierre (R3)
├── TrackingNotificationController FGS claim/reassert + notificación + widget
│   └── NotificationStatePolicy    qué transiciones alertan (pura)
└── onNewLocation (pipeline)       FixFilter → calidad → payload → Room (mutex único)
```

Direcciones de datos (sin cambios de contrato):

```
FLP / GNSS listener / poll one-shot
  └→ onNewLocation → invalidLocationReason → isPlausibleFix
       └→ regla OR Traccar → insertWithinLimit (Room, transaccional)
            └→ PositionOutboxDispatcher (HTTP primario / MQTT presencia)
                 └→ servidor (validación, idempotencia, tc_positions)
```

## 3. ¿Por qué `TrackingService` sigue teniendo 1 383 líneas?

Contenido restante, por responsabilidad:

| Bloque | Líneas aprox. | Por qué se queda |
|---|---|---|
| Ciclo de vida Android (`onCreate`, `onStartCommand`, screen receiver, `onDestroy`, claim FGS) | ~340 | Es el único lugar donde Android entrega el ciclo de vida y las promesas de FGS; extraer no reduce acoplamiento |
| Pipeline de captura (`onNewLocation` + filtros + payload) | ~450 | Usa el mutex compartido con presencia y ~10 estados de sesión; no tiene caracterización JVM y su extracción sin harness es el mayor riesgo de regresión de la app |
| Callbacks de integración (MQTT, outbox, health) | ~200 | Cableado explícito borde↔dominio (inversión por lambdas) |
| Helpers de tiempo/distancia/permisos | ~120 | Puros y pequeños; viven con el pipeline que los usa |
| `stopTracking`/`finishStopping` (secuencia + reintento de arranque) | ~120 | Coordina flags del servicio (`stopping`, `pendingStart`, scope) |

Decisión R3: lo que tenía frontera clara y valor (conectividad, telemetría,
presencia, watchdog, cierre de jornada) se extrajo; el pipeline NO se movió
porque no existe todavía un harness de caracterización para él (ver
`TECHNICAL_DEBT.md` M-1). Mover código crítico sin tests previos está prohibido
por las reglas del refactor.

## 4. Fronteras y dependencias

- `DEPENDENCY_RULES.md`: grafo permitido + test de arquitectura que lo congela.
- `MODULE_BOUNDARIES.md`: decisión de UN módulo Gradle con criterios objetivos.
- Sin Hilt/Koin: construcción explícita en `onCreate` (un scope de servicio).
- Sin EventBus: callbacks y estado persistido (`AppConfig`) únicamente.
- `core/MobileProtocol.kt`: topics, paths y estados de jornada en un solo lugar
  (antes repartidos entre `AppConfig` y literales de cada uploader).

## 5. Garantías preservadas (no se tocaron)

- HTTP primario de posiciones con lotes FIFO + ACK de negocio; MQTT presencia.
- Room: mismas entidades/DAO/versión (schemas 8 y 9 exportados; sin migración destructiva).
- Contrato de payload/notificación/topics/rutas: intacto (test `MobileProtocolTest` lo congela).
- Umbrales temporales del watchdog (30 s, 2 min, 10/15 min, 5 min, 60 min) sin cambios.
- `enqueueMutex` único compartido por posiciones y presencia (la MISMA instancia
  se inyecta a `PresenceController`).
- Sensores solo evidencia; jamás generan coordenadas (tests intactos).
- Claim FGS como primer acto de `onStartCommand` (`ForegroundClaimPolicy` intacta).

## 6. Pendiente real (no maquillado)

`TECHNICAL_DEBT.md` G–K y M–Q: clave de flota → token por dispositivo, TLS,
validaciones reales (endurance 6/12/24 h, OEM por equipo, FCM recovery E2E),
caracterización del pipeline, y ciclo `platform↔ui`. Nada de eso se declara
resuelto aquí.
