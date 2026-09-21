# MODULE_BOUNDARIES.md — Fronteras y decisión de módulos Gradle (R4/R5)

> Decisión: **se mantiene UN solo módulo Gradle (`:app`)**. Justificado con
> evidencia del grafo real, no por costumbre. Fecha: 2026-09-17.

## 1. Fronteras internas (paquetes por dominio)

`mobile/app/src/main/java/com/dmujeres/traccar` (106 archivos, 18 847 líneas):

| Paquete | Rol | Entradas | Salidas |
|---|---|---|---|
| `core` | Tipos/valores compartidos y protocolo: `MobileProtocol`, `MqttStatus`, `TrackingState`, `RecoveryOutcome`, `BootId`, `SpeedEstimator`, `JourneyFormatter`, `MqttServerNormalizer` | — | — |
| `config` | `AppConfig`: preferencias tipadas + contadores/buckets de salud (persistencia) | `core` | — |
| `data` | Room: entidades, DAOs, políticas de buffer/retención + `RemoteConfig` (aplicador de config remota) | `config`, `core` | — |
| `location` | Captura: `LocationEngine`, `FixFilter`, GNSS fallback, timeouts, calidad | `config`, `core` | — |
| `sensors` | Acelerómetro/giroscopio (evidencia, jamás coordenadas) | — | — |
| `oem` | Perfil de capacidades, guías y settings por fabricante | — | — |
| `tracking` | Orquestación: servicio FGS, watchdog, sesión, presencia, notificación, stop | casi todos | — |
| `outbox` | Cola de entrega: dispatcher HTTP/MQTT, cuarentena, retención | `core`, `data`, `transport` | — |
| `transport` | MQTT Paho + `Envelope` (formato de cable) | `config`, `core`, `data`, `diagnostics` | — |
| `health` | Snapshots de salud + uploader + estado multidimensional | `config`, `core`, `data`, `oem` | — |
| `diagnostics` | Recolección/reporte de caja negra, causa de silencio, telefonía, RTT | `config`, `core`, `data`, `oem`, `outbox`, `platform`, `sensors` | — |
| `recovery` | Escalera de recuperación: BootReceiver, SessionKeeper, worker, FCM, journal | `config`, `core`, `data`, `outbox`, `platform` | `tracking` (entrada) |
| `readiness` | Gates de onboarding/permisos/continuidad | `config`, `core`, `oem`, `platform`, `recovery` | `tracking` (entrada) |
| `platform` | Servicios Android neutros: notificaciones, sensores de estado, OTA, Sentry | `config`, `core` | `ui` (PendingIntent) |
| `ui` | Activities Compose + componentes + widget | todo | — |

Reglas y enforcement: `DEPENDENCY_RULES.md` (+ `ArchitectureDependencyTest`).

## 2. Por qué NO hay módulos Gradle adicionales

Criterios objetivos evaluados (los mismos que justificarían separar):

| Criterio | Evidencia | Veredicto |
|---|---|---|
| Tiempo de compilación | `:app:compileDebugKotlin` en esta máquina: segundos; 18 847 líneas Kotlin | No justifica |
| Equipos/ownership independientes | Un único desarrollador/agente; mismo repo, mismo ciclo de release | No justifica |
| Reutilización fuera de la app | Ningún consumidor externo de estos paquetes | No justifica |
| Aislamiento de dependencias pesadas | Las pesadas (Paho, Firebase, Room, Sentry) las usa `tracking`/`transport`/`data`, que ya son hojas hacia arriba; separar no reduce nada del APK | No justifica |
| API pública estable | No hay API de librería; todo es `internal` al APK | No justifica |
| Beneficio de build incremental | El cuello es KSP/Room + Compose, no el grafo de paquetes | Marginal |

**Coste de modularizar sin esos criterios**: doble build script, `api`/`impl`
ceremonial, refactor de recursos (`R`) y del `AndroidManifest`, pérdida de
velocidad por más tareas Gradle, y un `assembleRelease` más frágil (keystore,
google-services, Sentry) — todo sin beneficio medible.

## 3. Cuándo revisar la decisión (disparadores explícitos)

1. Un segundo consumidor real (otra app/SDK) de `core`+`data`+`outbox`.
2. Equipo separado que toque `ui` a diario mientras otro toca `tracking`.
3. `assembleDebug` > 3 min por recompilar todo al cambiar una línea de UI.
4. Necesidad de publicar `tracking-core` como artefacto versionado.

Si aparece (1) el primer corte natural es `:core` + `:data` + `:app`
(los paquetes ya están desacoplados por las reglas actuales). Si aparece (2/3)
el corte es `:ui` vs `:tracking`. Ninguno se hace "por si acaso".

## 4. Fronteras de proceso (no Gradle)

| Frontera | Mecanismo | Contrato |
|---|---|---|
| App ↔ servidor posiciones | HTTP `POST /api/mobile/v1/positions` (primario) | `Envelope` v1 + ACK de negocio |
| App ↔ servidor presencia | MQTT `dmj/v1/devices/{id}/telemetry` QoS1 | ACK `.../ack` |
| App ↔ servidor salud/diagnóstico/FCM | HTTP `/health`, `/diagnostics`, `/fcm-token`, `/recovery-ack` | `core/MobileProtocol` |
| Servicio ↔ UI | `TrackingService.isRunning` + `AppConfig` (estado persistido) | sin bind; sin EventBus |
| Componentes internos | Constructores + lambdas + políticas puras | interfaces SOLO en fronteras reales (`LocationEngine.Callbacks`) |

Sin Hilt/Koin: el grafo de objetos se construye explícitamente en
`TrackingService.onCreate` (un solo scope de servicio) y en Activities; no hay
grafo global que justifique un contenedor de DI (y añadirlo sería "moda").
