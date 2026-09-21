# TECHNICAL_DEBT.md — Deuda técnica y validaciones pendientes

> Registro honesto tras el refactor R1–R8 (2026-09-17). No hay PASS inventado:
> cada ítem tiene estado (`ABIERTO`, `ACEPTADO`, `PENDIENTE REAL`) y siguiente
> paso concreto. Las listas G–K son deuda de arquitectura/refactor; las M–Q son
> deuda de diagnóstico/validación real.

## Lista G — Gradle / módulos

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| G-1 | ¿Módulos Gradle adicionales? | **ACEPTADO: 1 módulo** | `MODULE_BOUNDARIES.md` (criterios objetivos: build, ownership, reutilización) | Revisar solo si se cumple un disparador documentado |
| G-2 | `assembleRelease` depende de secretos locales (`keystore.properties`, `secrets.properties`) | ABIERTO (operativo) | `app/build.gradle.kts` falla a propósito si falta la clave de flota | Documentar en runbook de release; CI futuro con secretos inyectados |
| G-3 | `debug.keystore` versionado (firma debug compartida del equipo) | ACEPTADO | Decisión F1: instalar encima entre PCs | No publicar artefactos firmados con debug |

## Lista H — DI (Hilt/Koin)

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| H-1 | Sin contenedor de DI | **ACEPTADO por diseño** | Grafo explícito en `TrackingService.onCreate`; `MODULE_BOUNDARIES.md` §4 | Reevaluar solo si `onCreate` supera ~100 líneas de wiring o aparece multi-proceso |
| H-2 | `TrackingWatchdog` con 18 dependencias inyectadas | ABIERTO (vigilado) | Constructor del watchdog | Si crece: agrupar en fachadas (`WatchdogInputs`) o inyectar 3–4 providers |

## Lista I — Interfaces / fronteras

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| I-1 | Única interface real: `LocationEngine.Callbacks`; el resto son lambdas | ACEPTADO | Patrón consistente en `OutboxCoordinator`, `PresenceController`, `ConnectivityObserver`, `TrackingWatchdog` | Mantener: interface solo cuando haya ≥2 implementaciones o test dobles |
| I-2 | `platform → ui` por PendingIntent (`Notifications` → `MainActivity`) | ACEPTADO/DOCUMENTADO | `DEPENDENCY_RULES.md` §3 | Si se añade otra Activity: extraer `launcherIntent: (Context) -> Intent` en el borde |

## Lista J — Jornada / pipeline de captura

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| J-1 | `onNewLocation` (~450 líneas) sin caracterización JVM | **ABIERTO (el mayor)** | No hay emulador/Robolectric en el proyecto; `ARCHITECTURE_AFTER.md` §3 | Añadir Robolectric o harness de pipeline con fakes ANTES de extraer `PositionPipeline` |
| J-2 | `TrackingService` aún toca presentación (callback de widget) | ACEPTADO | Excepción `ui.widget` en `ArchitectureDependencyTest` | Mover refresco a un observer del widget o a `Notifications` |
| J-3 | Reintento de arranque dentro de `finishStopping` (`pendingStart`) | ABIERTO (complejo) | Sin test (flujo Android) | Extraer a coordinador con test de secuencia cuando J-1 esté resuelto |

## Lista K — Keys / seguridad

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| K-1 | Clave de flota compartida embebida en el APK | **ABIERTO — riesgo residual aceptado** | `docs/SECURITY.md` §38–39; rotación con ventana ya aplicada (S1) | **Token por dispositivo**: emitir token en provisioning, almacenarlo en Keystore, validarlo en server y retirar la clave de flota |
| K-2 | TLS pendiente (HTTP `:999` + MQTT `:1883` sin certificados en infra) | **ABIERTO** | NSC con excepciones cleartext SOLO a hosts conocidos (`network_security_config.xml`); `docs/SECURITY.md` S2 | Certificados web/API + MQTT (`EMQX_CERTS_DIR` preparado); retirar excepciones cleartext |
| K-3 | `username/password` en SharedPreferences en claro | ABIERTO | `TODO(security)` en `AppConfig` | `EncryptedSharedPreferences` + migración que copie y borre valores |

## Lista M — Muestreo / telemetría

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| M-1 | Sin tests instrumentados del servicio (ni AndroidTest de tracking) | ABIERTO | `androidTest` solo cubre migración Room | Añadir emulador/CI (o Robolectric) |
| M-2 | `currentSignalLevel` con `signalStrength` requiere API 29 y varía por OEM | ABIERTO (dato) | `DeviceTelemetryPolicyTest` cubre mapeo; el valor real depende del radio | Validar en matriz OEM (Q-2) |
| M-3 | `RttMeter` mide RTT solo del camino MQTT | ACEPTADO | `diagnostics/RttMeter` | Medir también HTTP si se necesita SLA fino |

## Lista N — Red

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| N-1 | Captive portal / WiFi sin salida: `validated=false` reporta `network=none` | ACEPTADO (diseño) | `NetworkStatePolicy` + `LinkState` | Validación real en campo (portal cautivo) |
| N-2 | Nodo Tailscale/IP fija en NSC | ACEPTADO (temporal) | `network_security_config.xml` | Se retira al completar K-2 (TLS) |
| N-3 | Flapping WiFi↔datos: debounce de drenaje (10 s) y gate MQTT | ACEPTADO | `OutboxCoordinator.DRAIN_DEBOUNCE_MS`, `ReconnectGate` | Prueba de estrés de handover en laboratorio |

## Lista O — Outbox / almacenamiento

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| O-1 | Retención 100 000/7 d probada con tests, no con volumen real de 7 días | PENDIENTE REAL | `OutboxRetentionPolicyTest`, `OfflineRouteOrderTest` | Endurance offline (Q-1) |
| O-2 | `PositionOutboxDispatcher` mantiene estado global de proceso | ACEPTADO/DOCUMENTADO | `ARCHITECTURE_BEFORE` §6 | Si molesta para tests paralelos: fachada de instancia |
| O-3 | Cuarentena: evidencia local sin UI dedicada más allá de diagnóstico | ACEPTADO | `DeadLetter` + `DiagnosticsActivity` | Panel de cuarentena si operaciones lo pide |

## Lista P — Payload / contrato

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| P-1 | `tc_devices.attributes` VARCHAR(4000) con presupuesto | ABIERTO (server) | `MobileDiagnosticsService` (server) | Mantener presupuesto en campos nuevos; test server |
| P-2 | Protocolo sin número de versión de payload explícito | ACEPTADO | `Envelope` v1 (docs/mqtt/protocol-v1.md) | Si se rompe: `v2` + doble parseo en server |
| P-3 | `journeyStarted/journeyEnded` como flags derivados de `started/ended` | CUBIERTO | `MobileProtocolTest` | — |

## Lista Q — QA / validaciones reales pendientes

| ID | Validación | Estado | Bloqueo | Cómo cerrarla |
|---|---|---|---|---|
| Q-1 | Endurance 6/12/24 h con pantalla apagada | **PENDIENTE REAL** | Requiere dispositivo + ventana | `docs/PRODUCTION_RUNBOOK.md`; métricas en `tc_device_health` + continuidad |
| Q-2 | Matriz OEM por equipo (ZTE/Xiaomi/Samsung/Honor/Infinix/Tecno) | PARCIAL | Solo ZTE qa-f0 disponible (puede estar offline) | `docs/OEM_COMPATIBILITY.md` + prueba por modelo |
| Q-3 | FCM recovery E2E real (push→start→ACK por etapas) | PARCIAL | SEND/RECEIVE confirmados; E2E recovery pendiente | `docs/FCM_RECOVERY.md` + `docs/fcm-recovery.md` |
| Q-4 | TLS en producción | **PENDIENTE** (infra) | K-2 | Retirar cleartext y verificar 401/200 con certificados |
| Q-5 | Token por dispositivo | **PENDIENTE** | K-1 | Provisioning + Keystore + validación server |
| Q-6 | Rotación de clave de flota completada en toda la flota | PARCIAL | Ventana con clave anterior | Retirar `MOBILE_HTTP_API_KEY_PREVIOUS` cuando el 100 % migre |

## Deuda cerrada en esta ronda (para no repetirla)

- Ciclos de paquete `config↔recovery`, `diagnostics↔transport`,
  `outbox↔transport` (ver `REFACTORING_LOG.md` R4).
- Protocolo duplicado en `AppConfig` y literales de paths.
- Responsabilidades ajenas al ciclo de vida dentro de `TrackingService`
  (conectividad, telemetría, presencia, watchdog, cierre).

## Lista A — Arquitectura (ronda refactor R2, 2026-09-17)

| ID | Deuda | Estado | Evidencia | Siguiente paso |
|---|---|---|---|---|
| A-1 | Ciclo de orquestación `tracking ↔ readiness ↔ recovery ↔ ui ↔ platform ↔ diagnostics` | ACEPTADO (congelado) | `DependencyCycleTest` (2 tests) | Romper con interfaces/eventos (R13/R16/R17): receivers/worker no deben nombrar el Service; readiness evalúa por contrato |
| A-2 | `MainActivity`/`OnboardingActivity`/`DiagnosticsActivity` sin ViewModel (1472/980/890 líneas) | ABIERTO | `docs/ARCHITECTURE_AUDIT_R2.md` §1 | Migración progresiva `Activity → ViewModel → UiState` (R17), un solo flujo por vez |
| A-3 | `TrackingService` 1383 líneas | ACEPTADO (límite) | extracciones R3 previas | Extraer si supera ~1500 o si aparece una responsabilidad nueva |
| A-4 | `AppConfig` 911 líneas con 9 dominios de prefs | ABIERTO | `config/AppConfig.kt` | Split por preferencias con fachada temporal (R10); NO romper claves existentes |
| A-5 | `MqttManager` 778 líneas (conexión+suscripción+dispatch+ACK) | ABIERTO | frontera de persistencia ya extraída (`ControlQueueStore`) | Separar `MqttConnectionManager` de `MqttPresenceDispatcher` solo con frontera real |
| A-6 | `PositionOutboxDispatcher` es `object` con estado mutable | ACEPTADO (propietario único) | `outbox/PositionOutboxDispatcher.kt` | Evaluar instancia controlada por lifecycle; hoy el coste supera el beneficio |
| A-7 | 96 usos de `System.currentTimeMillis()` | ABIERTO (parcial) | políticas puras ya reciben `now` | Inyectar `Clock` donde habilite tests de expiración sin reloj real (R21) |
| A-8 | Rotación de secretos expuestos por ZIPs públicos | **PENDIENTE CRÍTICO** | `docs/SECURITY_BUILD.md` §3 | Rotar en ventana; primero flota a ≥1.1.5 para la API key |

## Ronda R3 (2026-09-17 tarde) — cambios de estado

| ID | Estado anterior | Estado actual | Nota |
|---|---|---|---|
| B-1 conectores sin shouldCut (I) | P1 ABIERTO | **CERRADO** | `linkTracksFor` respeta `shouldCut`; test actualizado al nuevo contrato |
| B-2 `syncDelayMs` UTC/local | P1 ABIERTO | **CERRADO** | base `serverTime−fixTime` (mismo offset serializado); fallback serverReceivedAt para datos sin serverTime |
| B-4 parada fantasma (outlier sin diámetro) | P2 ABIERTO | **CERRADO** | tope de diámetro 2×radio en `detectStops` |
| B-3 panel vs línea (fast-gap) | P2 ABIERTO | ABIERTO (fase 2/3) | requiere cablear Analyzer en ReplayPage |
| B-5 banda ciega 45 s–300 s / 100–200 m | P3 ABIERTO | ABIERTO | sin caso real hoy; revisar tras fase 2 |
| R-01 AlertDialog post-destroy | ALTA ABIERTO | **CERRADO** | `runOnUiThreadSafe` en MainActivity (locked, orquestador) |
| Segmentación RouteSegment* | — | PARCIAL | módulo puro + matriz A–G listos; wire en ReplayPage/API pendiente (fases 2/3) |

## Cierre de software (2026-09-18) — cambios de estado

| ID | Estado anterior | Estado actual | Nota |
|---|---|---|---|
| `.gitignore data/` ocultaba paquete Room | ABIERTO (crítico) | **CERRADO** | reglas ancladas `/data/`, `/logs/`; paquete visible a Git |
| reconnects24h métrica muerta | ABIERTO (transporte) | **CERRADO** | se cuenta también la reconexión real (ReconnectGate), no solo Paho auto-reconnect |
| OTA: versionCode/HTTPS/firma/puerta segura/estado | ABIERTO | **IMPLEMENTED** | `OtaVersionPolicy`/`OtaUrlPolicy`/`OtaInstallPolicy` + wiring; firma requiere validación en dispositivo |
| Movement start (5 m/s tardío) | ABIERTO | **CERRADO** | `MovementStartPolicy` + integración con ancla/desplazamiento; +11 tests |
| Timeline operativo | INEXISTENTE | **IMPLEMENTED (servicio)** | endpoint auto-registrado; falta restart + UI; 18 tests |
| Dashboard local-first | INEXISTENTE | **IMPLEMENTED (lectura)** | IndexedDB + NetworkState + SWR + badge; write-through WS parcial |
| publish-ota.sh hardcodeado | ABIERTO | **CERRADO** | `OTA_PUBLIC_BASE_URL` obligatorio + fail closed sin HTTPS |
