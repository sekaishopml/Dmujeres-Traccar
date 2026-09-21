# ARCHITECTURE_BEFORE.md — Auditoría estructural (fase R0)

> Auditoría sobre el estado REAL en disco, **sin modificar código**. Fuente de
> verdad: archivos `.kt`, `AndroidManifest.xml`, resultados de tests unitarios.
> Fecha: 2026-09-16. Commit base del working tree: `4797a4a` + cambios F1/F2/P1-P4
> sin commitear (pre-existentes, no de esta fase).
>
> Este documento es el "antes". El "después" vive en `ARCHITECTURE_FINAL.md` y
> `ARCHITECTURE_AFTER.md`; el registro de cambios en `REFACTORING_LOG.md`.

## 1. Alcance y método

- `mobile/` (objeto de la refactorización): 92 archivos Kotlin main (18 209
  líneas), 72 archivos de test unitario (8 285 líneas, 549 tests), 1 archivo
  androidTest (218 líneas).
- `server/` y `dashboard/`: fuera de alcance; no se tocan (sus suites 821/0 y
  80/0 no pueden verse afectadas).
- Método: inventario por paquete, grafo de imports clase→paquete, lectura de las
  clases >300 líneas, baseline de tests (549/0/0/0) y conteo de `@Test`.

## 2. Baseline verificado

| Suite | Comando | Resultado |
|---|---|---|
| Mobile JVM | `./gradlew :app:testDebugUnitTest` | **549 tests, 0 fallos, 0 errores, 0 skipped** |
| Server | (fuera de alcance) | 821/0 |
| Dashboard | (fuera de alcance) | 80/0 |

App publicada: 1.1.3 (versionCode 113) — cualquier cambio de contrato de
payload/OTA rompe compatibilidad y queda prohibido en esta fase.

## 3. Árbol de paquetes real (main)

```
com.dmujeres.traccar/           6 archivos   3780 líneas  (Activities + DmujeresApp)
├── config/                     1 archivo     913 líneas  (AppConfig: prefs + telemetría)
├── db/                        11 archivos    672 líneas  (Room + políticas de outbox/retention)
├── location/                  25 archivos   5437 líneas  (¡mezcla 4 dominios!)
├── messaging/                  3 archivos    443 líneas  (FCM recovery)
├── mqtt/                      10 archivos   2012 líneas  (MQTT + HTTP outbox + dispatch)
├── receiver/                   3 archivos    375 líneas  (boot/keeper/stuck-stop)
├── ui/components + ui/theme    6 archivos    937 líneas  (Compose)
├── util/                      25 archivos   3123 líneas  (cajón de sastre: 9 dominios)
├── widget/                     1 archivo     267 líneas  (AppWidget)
└── worker/                     1 archivo     250 líneas  (WorkManager recovery)
```

Hallazgo principal: la organización por **capas técnicas** (`util`, `location`,
`mqtt`) no corresponde a los dominios reales. Ejemplos:

| Clase | Paquete actual | Dominio real |
|---|---|---|
| `OutboxCoordinator` | `location` | outbox |
| `OutboxRetentionPolicy`, `StopDrainPolicy`, `BufferDrainPolicy`, `PositionBufferPolicy` | `db` | outbox |
| `PositionOutboxDispatcher` (+ `HttpTransport`, `DispatchContext`, wake loop) | `mqtt` | outbox/transport HTTP |
| `TrackingHealthMonitor`, `TrackingHealthPolicy` | `location` | health |
| `RecoveryJournal`, `RecoveryStatus` | `util` | recovery |
| `DeviceReadinessPolicy/Checker`, `ContinuityTestPolicy`, `ContinuityTracker`, `RecoveryTestPolicy` | `location` | readiness |
| `OemProtection`, `OemGuidanceProvider`, `VendorSettings`, `DeviceCaps`, `DeviceCapabilityProfile`, `PermissionHealth` | `util` | oem |
| `SilenceDiagnosis`, `NetCause`, `DiagnosticsCollector/Reporter`, `RttMeter`, `LinkState` | `util` | diagnostics |
| `MotionSensor`, `GyroSensor` | `util` | sensors |
| `TrackingService` + 4 controladores | `location` | tracking |

Consecuencia: "todo lo de recovery" hoy vive en `util/` + `receiver/` +
`messaging/` + `worker/`; "todo lo del outbox" vive en `db/` + `mqtt/` +
`location/`. Encontrar el código de un dominio obliga a conocer antes la
historia de extracciones.

## 4. Dependencias entre paquetes (imports, clase→paquete)

```
raíz (Activities/App)  -> config(5) db(1) location(12) mqtt(7) ui(4) util(21) worker(1)
config                 -> mqtt(1) util(2)
location               -> raíz(2) config(8) db(7) mqtt(9) util(20) widget(2) worker(1)
messaging              -> config(2) location(1) util(2)
mqtt                   -> config(2) db(7) util(1)
receiver               -> config(2) location(2) util(3) worker(1)
util                   -> raíz(1) config(5) db(3) mqtt(6)
widget                 -> raíz(1) config(1) location(1) util(1)
worker                 -> raíz(1) config(1) location(1) mqtt(2) receiver(1) util(2)
```

### Ciclos detectados (9)

| Ciclo | Evidencia | Riesgo |
|---|---|---|
| `raíz ↔ location` | `TrackingService` ← `MainActivity`; Activities ← `TrackingService`/`TrackingState` | acoplamiento UI↔Service |
| `raíz ↔ util` | `util` (DiagnosticsCollector) referencia `DmujeresApp`; raíz usa util | bajo (lectura de DB) |
| `raíz ↔ worker` | Activities lanzan worker; worker usa `TrackingService` | bajo |
| `config ↔ mqtt` | `AppConfig.telemetryTopic()/ackTopic()` ↔ `MqttManager` lee config | medio (config conoce protocolo) |
| `config ↔ util` | `AppConfig` usa `BootId`, `JourneyFormatter`; util lee config | bajo |
| `location ↔ widget` | `TrackingNotificationController` refresca `JourneyWidget`; widget lee estado | bajo |
| `location ↔ worker` | `TrackingService.onTaskRemoved` lanza worker; worker arranca servicio | bajo |
| `mqtt ↔ util` | `MqttManager` usa `SentryLog`/`RttMeter`; `util.LinkState` usa enums MqttLink | bajo |
| `receiver ↔ worker` | worker usa `StuckStopPolicy` (receiver) y `SessionKeeper` agenda worker | bajo |

Ninguno es un ciclo de inicialización peligroso, pero `raíz↔location` y
`mqtt↔util` demuestran que los límites no están donde se leen.

## 5. Clases grandes y responsabilidades mezcladas

| Archivo | Líneas | Responsabilidades mezcladas |
|---|---|---|
| `location/TrackingService.kt` | **2131** | ciclo de vida Service · claim FGS · receivers de red/avión/pantalla · construcción de telemetría (batería/red/señal) · encolado de presencia · pipeline de captura completo (filtro→velocidad→payload→Room→jornada) · watchdog 30 s (health, recovery, link, trickle HTTP, alertas, cascade de estado) · cierre de jornada (drenaje→presence ended→3 s→pendingStart) · texto de resumen |
| `MainActivity.kt` | 1469 | UI Compose + login HTTP + permisos + readiness + arranque/parada servicio + diagnóstico inline + update/OTA UI + debug preview |
| `OnboardingActivity.kt` | 978 | UI wizard + permisos + pasos OEM + persistencia |
| `config/AppConfig.kt` | 913 | prefs tipadas (80+) + telemetría agregada + formato de buckets diarios + temas MQTT + `applyRemote` + hash de dispositivo + constantes de protocolo |
| `DiagnosticsActivity.kt` | 887 | UI + recolección DB + parsing de logs + política de GPS diag |
| `mqtt/MqttManager.kt` | 771 | conexión/reconexión/subscribe/ACK + **loop de dispatch de presencia** + test de conexión + normalización de servidor |
| `location/LocationEngine.kt` | 530 | FLP + GNSS status + fallback GPS_PROVIDER + polling one-shot + watchdog tick + modo adaptativo |
| `ui/components/MetricsRow.kt` | 522 | componente + formatters |
| `mqtt/PositionOutboxDispatcher.kt` | 410 | `object` global mutable + transporte HTTP + dispatch FIFO + cuarentena + wake loop |
| `location/DeviceReadinessPolicy.kt` | 330 | 8 checks + outcomes + result (cohesivo, pero en paquete equivocado) |

`TrackingService` sigue siendo un **God Orchestrator**: aún concentra 5
responsabilidades que tienen frontera lógica clara y ninguna es "ciclo de vida":

1. Observadores de conectividad (receiver avión + `NetworkCallback`) — ~200 líneas.
2. Telemetría de dispositivo (red/señal/batería/telefonía/permisos) — ~180 líneas.
3. Encolado de presencia (`enqueuePresence` + heartbeat) — ~110 líneas.
4. Cierre de jornada (`stopTracking`/`finishStopping`/`finishedJourneyText`) — ~120 líneas.
5. Watchdog de 30 s — ~260 líneas con 8 tareas secuenciales.

## 6. Duplicaciones y estado global

| Hallazgo | Ubicaciones | Nota |
|---|---|---|
| Haversine | `FixFilter.distanceMeters`, `SpeedEstimator.haversineMeters`, `TrackingService.distanceMeters` | 3 implementaciones; fórmulas equivalentes |
| Lectura de red (`ConnectivityManager`) | `TrackingService.currentNetworkLabel/currentTransport/currentSignalLevel/isNetworkAvailable` + `util/NetCause.snapshot` | misma consulta con 4 criterios distintos |
| Nivel de señal por bandwidth | `TrackingService.currentSignalLevel` | lógica pura sin test que la congele |
| `runBlockingSafe` | `TrackingService.telemetry()` | bloquea un hilo de Default para leer `dao.count()`; el resto del servicio ya usa `withContext(Dispatchers.IO)` |
| Estado global mutable | `PositionOutboxDispatcher` (`object` con `mqttReady`, `onFlushOutcome`, `wakeDao/wakeTransport/wakeCtxProvider`, canal + `wakeStarted`) | singleton con estado de sesión: dificulta tests paralelos y oculta dependencias |
| Estado global mutable | `MotionSensor`, `GyroSensor`, `GnssState`, `MqttStatus`, `MqttManager.retryAt` | aceptado por diseño (sensores de proceso), documentado |
| Cadenas mágicas | `"wifi"/"mobile"/"none"`, `"gps"/"network"/"fused"/"unknown"`, `"accepted"/"duplicate"/...` esparcidas | contrato con servidor: debería vivir en un solo lugar |

## 7. Lógica en capa equivocada

- `config/AppConfig.kt` contiene **formato de protocolo** (`telemetryTopic()`,
  `ackTopic()`, `deviceHash()`, `encodeDailyBucket`) que no es "preferencias".
- `mqtt/MqttManager.kt` ejecuta **dispatch de outbox** (borra filas, cuarentena)
  cuando esa responsabilidad es del outbox, no del transporte.
- `ui/components` no tiene lógica crítica (bien). `MainActivity` sí:
  `startIfReady()` decide readiness y arranca el servicio; `login()` habla HTTP.
- `DiagnosticsActivity` usa `runBlocking` para leer la DB (línea 151).

## 8. Testabilidad (dónde duele)

- Suites puras excelentes: `FixFilter`, `LocationQuality`, `LocationSensorFusion`,
  `SpeedEstimator`, `DispatchPolicy`, `RecoveryJournal`, `DeviceReadinessPolicy`,
  `OemGuidanceProvider`, `TrackingHealthPolicy`, `LinkState`, `NetCause`,
  `Envelope`, `PositionOutboxDispatcher` (con `FakeDao`), `BufferDrainPolicy`...
- Zonas sin caracterización directa: `OutboxCoordinator` (debounce/single-flight/
  auto-encadenado), `TrackingNotificationController` (estados), cascade de estado
  del watchdog, resumen de cierre de jornada, niveles de señal, persistencia de
  `netCause/netLabel`.
- Razón estructural: esas piezas dependen de `Context`/`AppConfig(Room/SharedPreferences)`
  o están enterradas en `TrackingService`, así que solo se pueden testear vía
  Android o no se testean.

## 9. Riesgos de regresión identificados (para el refactor)

| Riesgo | Por qué | Mitigación exigida |
|---|---|---|
| Semántica temporal del watchdog (30 s, 2 min, 10/15 min, 60 s) | Definida por constantes + estado `@Volatile` repartido | No tocar umbrales; extraer con los mismos valores y tests de política |
| Mutex único de encolado (`enqueueMutex`) compartido por presencia y posiciones | Serializa secuencias y evita intercalado | Si se extrae, inyectar **la misma instancia**, nunca una nueva |
| Orden del cierre: drenar → `ended` → drenar → 3 s → `pendingStart` | Visible en `stopTracking` | Mover verbatim; test de decisiones puras |
| Claim FGS como primer acto de `onStartCommand` | Anti `ForegroundServiceDidNotStartInTimeException` (crash real) | No cambiar orden; `ForegroundClaimPolicy` intacta |
| Room: 2 versiones exportadas (8, 9) sin migración destructiva | OTA en campo | No tocar entidades/DAO/versión |
| `object` con estado global del dispatcher | Singleton de proceso | Cambios de API interna conservando fachada |
| Contrato payload/notificación/test IDs | Server + dashboard + FCM | Prohibido renombrar campos o topics |

## 10. Veredicto R0

1. La extracción FASE 3 fue correcta (controladores con frontera real), pero
   `TrackingService` **sigue siendo excesivo**: 2131 líneas y 5 responsabilidades
   ajenas al ciclo de vida. Objetivo R3: dejarlo como orquestador de
   `onStartCommand`/ciclo de vida + delegación, sin perder una sola regla.
2. Los paquetes actuales son capas técnicas, no dominios. R2 debe reorganizar
   por dominio **sin** tocar contratos Room/payload/Manifest externo.
3. Hay deuda real de testabilidad en las piezas que se van a extraer: R1 debe
   congelar su comportamiento antes de moverlas.
4. El número de módulos Gradle correcto sigue siendo **1** (`:app`): no hay
   límites de compilación, equipos ni dependencias que justifiquen más; R5 lo
   justifica formalmente.
