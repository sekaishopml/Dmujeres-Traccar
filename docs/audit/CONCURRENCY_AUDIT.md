# CONCURRENCY_AUDIT.md — Agente G (R3), 2026-09-17

Repo: `/DMujeres-Tracking` · App Android Kotlin 1.1.5 (versionCode 115, targetSdk 35).
Alcance: solo lectura de `mobile/app/src/main` + tests. Baseline previa: 0 GlobalScope, 3 CoroutineScope propietarios, 14 usos Handler/Looper, 7 registerReceiver, 68 objects.

---

## 1. INVENTARIO

### 1.1 CoroutineScope (dueño → creación → cancelación)

| # | Scope | Creación | Dueño / hilos | Cancelación | Evidencia |
|---|---|---|---|---|---|
| 1 | `serviceScope` | `newServiceScope()` = `SupervisorJob + Dispatchers.Default + CoroutineExceptionHandler` | TrackingService (corrutinas de fix/presencia/watchdog/outbox/MQTT) | `finishStopping()` → `closingScope.cancel()` (captura el scope de la jornada cerrada); y en `onDestroy()` → `serviceScope.cancel()` | TrackingService.kt:102, 622-625, 1289-1298, 1357 |
| 2 | `stopControllerScope` | `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | TrackingService (JourneyStopCoordinator: secuencia de cierre) | `onDestroy()` → `stopControllerScope.cancel()`; `stopJob?.cancel()` | TrackingService.kt:103, 1355-1356 |
| 3 | `appScope` | `CoroutineScope(SupervisorJob + Dispatchers.Default)` | DmujeresApp (broadcast de conectividad → update badge + worker reconnect) | NUNCA se cancela (vida = proceso Application) — correcto por diseño | DmujeresApp.kt:31 |
| 4 | `lifecycleScope` (x4) | Compose Activity | MainActivity (login/refresh/update/download) | Automática por ciclo de vida | MainActivity.kt:548, 736, 866, 931 |
| 5 | scope inyectado a `MqttManager` | parámetro `scope` | dispatchLoop + retries + subscriptionRetry (siempre `serviceScope`) | hereda cancelación de #1; además `disconnect()` cancela dispatchJob/subscriptionRetryJob/connectRetryJob | MqttManager.kt:49, 614-619 |

**GlobalScope: 0.** Confirmado por grep (`GlobalScope` sin resultados en `src/main`).

Scopes anidados: `PresenceController`, `TrackingWatchdog`, `ConnectivityObserver`, `TrackingNotificationController`, `LocationEngine`, `OutboxCoordinator` no crean scopes: reciben `scopeProvider: () -> CoroutineScope` y siempre es `{ serviceScope }` (TrackingService.kt:167, 192, 239, 249, 259, 304). Un solo owner real (#1).

### 1.2 Handlers / Looper

| Uso | Archivo:line | Riesgo |
|---|---|---|
| `uiHandler` ticker 3 s + timeout 15 s | MainActivity.kt:200-210, 807-808 | `removeCallbacks(uiTicker)` en onPause (387) y `removeCallbacks(detailsTransitionTimeout)` en onDestroy (391). Cubierto. |
| Verify FCM 6 s / recheck 30 s | FcmRecoveryMessagingService.kt:119, 154 | `Handler(mainLooper).postDelayed` sin removeCallbacks: retiene el Service 6-30 s tras destruirlo (ver R-06). |
| Ticker continuidad 1 s | ContinuityTracker.kt:129, 150-153 | `stopFreezeWatcher()` → `removeCallbacksAndMessages(null)` (155-158). Cubierto. |
| FLP/GPS con Looper explícito | LocationEngine.kt:399-421, GnssFallbackPolicy.kt:9-17 | Fix documentado: overload con `context.mainLooper` evita Handler-sin-Looper en corrutina. OK. |

Total: **5 sitios activos** de Handler/Looper (los 14 reportados incluyen comentarios/imports). Ningún `HandlerThread` creado manualmente.

### 1.3 registerReceiver (7 = 3 pares reales + 1 app-lifetime)

| Registro | Unregister | Par OK |
|---|---|---|
| Screen ON/OFF dinámico, TrackingService | TrackingService.kt:353/356 → 367 (`unregisterScreenReceiver` en onDestroy:1350) | ✅ |
| Modo avión, ConnectivityObserver | ConnectivityObserver.kt:81/84 → 95 (`stop()` desde `stopTracking`:1278 y `onDestroy`:1349) | ✅ |
| CONNECTIVITY_ACTION, DmujeresApp | DmujeresApp.kt:126-132 — **sin unregister** (vida = Application, intencional) | ⚠️ aceptable |
| (resto: BootReceiver, SessionKeeperReceiver, widget, FCM — declarados en manifest, Android los gestiona) | — | ✅ |

### 1.4 Executors / threads sueltos

| Componente | Evidencia | Shutdown |
|---|---|---|
| `FcmAck` executor daemon `fcm-ack` | FcmTokenRegistrar.kt:23-26 | Nunca (object = vida de proceso). Aceptable: mono-hilo daemon. |
| `FcmTokenRegistrar` executor daemon `fcm-token` | FcmTokenRegistrar.kt:71-74 | Nunca. Ídem. |
| `DiagnosticsReporter` executor daemon `diagnostics-report` | DiagnosticsReporter.kt:47-49 | Nunca. Ídem. |
| Watchdog thread `mqtt-test-timeout` (12 s) de `testConnection` | MqttManager.kt:696-705 | **No se interrumpe al terminar OK**: duerme los 12 s aunque el connect haya respondido en 1 s. Daemon ⇒ sin leak real, pero acumula hasta N hilos si el usuario repite el login (ver R-08). |
| Paho (`MqttAsyncClient`) threads internos | MqttManager.kt:229-232, 631-637 | `disconnect()` + `close(true)` en reconexión (229-232) y en `disconnect()` (631-637) — el close(true) evita el revive por automaticReconnect (documentado en 635-636). ✅ |
| `RttMeter` synchronized | RttMeter.kt:18-32 | `synchronized(lock)` en update/current/reset — thread-safe, lock de objeto privado. ✅ |

### 1.5 Timers de UI

- **Tick 40 verificado**: MainActivity.kt:206-207 — `if (tickCount % 40 == 0) checkForUpdate(auto = true)` con tick de 3 s = chequeo OTA cada 2 min. Correcto.
- Journey timer: NO existe timer dedicado; el elapsed se recalcula dentro del `uiTicker` de 3 s leyendo `config.journeyElapsedMs` (MainActivity.kt:677-686). En rotación el ticker muere con onPause/onDestroy y renace en onResume: sin leak.
- Watchdog del servicio: corrutina `while(isActive) { delay(30_000) }` en `serviceScope` (TrackingWatchdog.kt:87-94). Cancelada con el scope. ✅

---

## 2. RIESGOS REALES (tabla con severidad y reproducibilidad)

| ID | Riesgo | Severidad | Reproducibilidad | Evidencia |
|---|---|---|---|---|
| R-01 | **MainActivity: callback de `testConnection` tras destroy** — `MqttManager.testConnection` invoca `onResult` desde un thread Paho/executor; el callback llama `runOnUiThread { showDialog(...) }` (MainActivity.kt:541-556, 576-581). `runOnUiThread` no verifica `isDestroyed`: si la Activity se destruyó durante el connect (hasta 12 s), `AlertDialog.Builder(this).show()` lanza `WindowManager$BadTokenException`. Repro: login → rotación/cierre inmediato → broker lento (timeout 10 s). | **Alta** | Media (ventana ≤12 s, fácil en broker caído) | MqttManager.kt:660-712; MainActivity.kt:560-566 |
| R-02 | **Singleton `PositionOutboxDispatcher` compite entre sesiones de servicio** — wakeDao/wakeTransport/wakeCtxProvider/mqttReady/onFlushOutcome son `@Volatile` en un object; con `pendingStart` (restart inmediato post-stop, TrackingService.kt:1304-1314) o con `TrackingRecoveryWorker.flushOnce` (TrackingRecoveryWorker.kt:149-150) hay una ventana donde el wakeLoop vivo de la sesión vieja usa el `DispatchContext` nuevo (journeyStartAt ya reseteado). Mitigado porque `ctx` se pide fresco por flush y el Mutex serializa, pero `onFlushOutcome`/`mqttReady` apuntando a la sesión vieja puede contaminar métricas transitoriamente. | Media | Baja (race de ventana estrecha; requiere stop+restart rápido) | PositionOutboxDispatcher.kt:326-351, 372-383; TrackingService.kt:604-618 |
| R-03 | **`dispatchWake` CONFLATED nunca cerrado** (MqttManager.kt:66) — tras `disconnect()` el canal recibe `trySend` (639) y `dispatchJob` ya está cancelado: la señal queda bufferizada. Sin leak (el manager muere con el GC) ni pérdida (CONFLATED coalescea por diseño), pero el último receive() del dispatchLoop muerto no la consume. Cosmético. | Baja | N/A (diseño) | MqttManager.kt:66, 615, 639 |
| R-04 | **Pérdida teórica de señales wake en restart del wakeLoop** — `wakeStarted.compareAndSet` + `invokeOnCompletion { wakeStarted.set(false) }` (PositionOutboxDispatcher.kt:378-383): si `startWakeLoop` se llama justo entre la cancelación del scope viejo y el `invokeOnCompletion`, el flag aún está true y el relanzamiento se pierde; las señales siguen bufferizadas en el CONFLATED hasta el próximo `requestFlush()`. Con debounce 1.2 s y watchdog 30 s el mensaje se recupera solo. | Baja | Muy baja (requiere microventana) | PositionOutboxDispatcher.kt:328-383; TrackingService.kt:616-618 |
| R-05 | **FCM onMessageReceived hace I/O ligera en el hilo FCM** — `AppConfig(this)` (SharedPreferences) ×2 + `TrackingService.start` (FcmRecoveryMessagingService.kt:37, 120, 155). La red está correctamente fuera (FcmAck con executor propio, FcmTokenRegistrar.kt:23-58). SharedPreferences es I/O de disco breve; el proyecto prohíbe "I/O pesada" y esto no lo es, pero son ~4-6 lecturas/escrituras síncronas en un callback con presupuesto de 10 s. | Baja | Alta (cada probe) pero impacto mínimo | FcmRecoveryMessagingService.kt:36-108; FcmTokenRegistrar.kt:30-58 |
| R-06 | **Handler postDelayed FCM retiene el Service destruido hasta 30 s** — `scheduleVerify`/`scheduleGpsRecheck` no guardan referencia ni hacen removeCallbacks (FcmRecoveryMessagingService.kt:119, 154). Si el sistema destruye el Service antes, `AppConfig(this)` sobre contexto destruido funciona, pero se sostiene la referencia 6-30 s (leak menor, auto-libera). | Baja | Media | FcmRecoveryMessagingService.kt:118-168 |
| R-07 | **Callbacks Paho tras disconnect — bien blindado** (verificación, no hallazgo): `connectComplete/connectionLost/onFailure` guardan con `if (client !== newClient) return` (MqttManager.kt:245, 266, 326, 375); `disconnect()` pone `client = null` ANTES de `oldClient.disconnect()` (625-631), así el guard neutraliza el connectionLost tardío. `completeInFlightWithoutAck` es idempotente. | ✅ OK | — | MqttManager.kt:242-284, 614-640 |
| R-08 | **Watchdog thread de `testConnection` no interrumpido** — daemon que duerme 12 s aunque el connect termine en 1 s (MqttManager.kt:696-705: `finish()` no hace `watchdog.interrupt()`). Con N intentos de login acumula hasta N hilos dormidos ≤12 s. Coste despreciable, ruido en perfiles. | Baja | Alta (cada login con éxito rápido) | MqttManager.kt:684-705 |
| R-09 | **Doble registro BootReceiver/SessionKeeper — no existe** (verificación): ambos caminos terminan en `SessionKeeper.schedule` con el MISMO PendingIntent (requestCode 2001, FLAG_UPDATE_CURRENT ⇒ idempotente, SessionKeeper.kt:44-50) y `TrackingService.start` es idempotente vía `started.getAndSet` (TrackingService.kt:505) más `ForegroundClaimPolicy`. `SessionKeeperReceiver` solo re-encadena si `trackingEnabled`. Sin alarmas duplicadas. | ✅ OK | — | BootReceiver.kt:63-67; SessionKeeper.kt:38-56; SessionKeeperReceiver.kt:13-25 |
| R-10 | **Race dispatch MQTT vs HTTP flush — cubierta por un único Mutex** (verificación): TODAS las rutas pasan por `DispatchLock.mutex`: MQTT dispatchLoop (MqttManager.kt:477), HTTP `flushOnce` (PositionOutboxDispatcher.kt:163 — usada por wake, drain, `flushPendingOnStop` y TrackingRecoveryWorker:149). Un solo Mutex global ⇒ sin orden de locks ⇒ sin deadlock (DispatchLock.kt:10-18). El microbatch (MICROBATCH_MAX=5) y el replay comparten el mismo `flushOnce`. Sin rutas fuera del lock. | ✅ OK | — | DispatchLock.kt; MqttManager.kt:475-522; PositionOutboxDispatcher.kt:155-167 |
| R-11 | **Jobs huérfanos tras stop — cubierto** (verificación): `stopTracking` captura `closingScope = serviceScope` y `closingMqtt = mqtt` ANTES de tocar nada (TrackingService.kt:1289-1291); `enqueueEnded` usa ese scope capturado (279); `finishStopping` hace `closingMqtt.disconnect()` (con `close(true)`) y `closingScope.cancel()` (1294-1298). Los callbacks del engine viejo (`scopeProvider` = lambda que lee el campo) quedan no-op tras cancel. | ✅ OK | — | TrackingService.kt:1274-1331 |
| R-12 | **ConnectivityObserver NetworkCallback tras stop** — `onNetworkValidated` lanza `serviceScope.launch { manager.connect(immediate = true) }` (TrackingService.kt:262-272). Si el scope ya fue cancelado, launch es no-op; pero `connectivity.stop()` se llama en `stopTracking` (1278) ANTES del cierre, así el callback ya no está registrado. Doble defensa correcta. | ✅ OK | — | TrackingService.kt:1278, 1349; ConnectivityObserver.kt:54-66 |

### Riesgos restantes de la lista (verificados sin hallazgo)

- **(c) registerReceiver sin unregister**: solo DmujeresApp (app-lifetime, intencional). Los otros 2 pares dinámicos cierran (§1.3).
- **(g) pérdida de señales en CONFLATED**: por diseño el coalesceo es correcto; único caso teórico = R-04.

---

## 3. TESTS DE CONCURRENCIA EXISTENTES Y HUECOS

**Existentes (JVM, runBlocking/runTest):**

| Test | Qué cubre | Evidencia |
|---|---|---|
| `DispatchRobustnessTest` | Serialización de `DispatchLock.mutex` (20 corrutinas × 50 inc con `delay(1)` dentro del lock = 1000 exactos); unicidad del mutex; contratos del DAO paginado; FIFO de `selectNext`; semántica de espera del dispatch | DispatchRobustnessTest.kt:22-79 |
| `PositionOutboxMicroBatchTest` | Wake CONFLATED: coalescing, debounce inyectable, idempotencia de `startWakeLoop`, microbatch=5 | PositionOutboxMicroBatchTest.kt:196-318 |
| `ReconnectGateTest` | Cadencia de reconexión (backoff+jitter, ventana por intento) | transport/ReconnectGateTest.kt |
| `MqttRobustnessTest` | Políticas puras del manager (StaleConnectingPolicy, DispatchPolicy) — **sin Paho simulado** | mqtt/MqttRobustnessTest.kt |
| `OutboxCoordinatorTest` | Debounce 10 s, single-flight `drainInProgress`, auto-encadenado, deadline de StopDrainPolicy (flush inyectable) | OutboxCoordinatorTest.kt |
| `JourneyStopCoordinatorTest` | Orden congelado del cierre (drenar→ended→drenar→delay→finish) con lambdas inyectables | JourneyStopCoordinatorTest.kt |
| `StuckStopPolicyTest`, `SessionKeeperPolicyTest`, `BootIntentsTest` | Decisiones puras de recovery/stop (no concurrencia real) | recovery/* |
| `RttMeterTest` | EWMA thread-safe del RTT | RttMeterTest.kt |

**Huecos:**

1. **Sin test de cancelación**: nadie verifica que tras `closingScope.cancel()` no queden jobs vivos (equivalente JVM de R-11). Un test con un scope fake y `Job.children()` lo cubriría.
2. **Sin test de R-01**: la ruta `testConnection → onResult → UI` no está caracterizada (requeriría abstraer el executor/callback; hoy la lambda es hardcoded).
3. **Sin test de ciclo de vida del wakeLoop tras cancel/restart** (R-02/R-04): el caso "cancelar scope y relanzar startWakeLoop" no está en PositionOutboxMicroBatchTest.
4. **MqttManager no testeable en JVM**: `connect/disconnect/dispatchLoop` dependen de `MqttAsyncClient` real; los guards `client !== newClient` (R-07) solo están cubiertos por lectura, no por test. Extraer una interfaz `MqttClientPort` lo habilitaría.
5. **Sin test de que `flushOnce` del RecoveryWorker respeta el mismo lock** — está garantizado por código, pero un architecture test que prohíba escribir en `pending_positions` fuera de `DispatchLock` (ver §5) lo haría permanente.

---

## 4. RECOMENDACIONES (sin ejecutar)

### Fase 1 — Hotfix de riesgo alto (1 PR, bajo riesgo)
1. **R-01**: en los callbacks de `MqttManager.testConnection` (MainActivity.kt:541, 576), sustituir `runOnUiThread { ... }` por `lifecycleScope.launch { ... }` (se auto-cancela en destroy) o guardar `if (isDestroyed || isFinishing) return@runOnUiThread` antes de `showDialog`. Alternativa estructural: mover el `showDialog` a un `lifecycleScope.launch` que consuma un `MutableStateFlow<LoginResult>`.
2. **R-08**: en `MqttManager.testConnection`, guardar el `Thread` del watchdog y hacer `interrupt()` dentro de `finish()` (MqttManager.kt:684-694).

### Fase 2 — Endurecimiento estructural (2-3 PR)
3. **R-02/R-04**: encapsular el estado del wakeLoop en una clase instanciable (`WakeLoop(owner)`) en lugar del object singleton, con `close()` explícito llamado desde `finishStopping`; `PositionOutboxDispatcher` pasa a ser fachada estática o se inyecta. Elimina la ventana del `compareAndSet`/`invokeOnCompletion`.
4. **R-05/R-06**: en `FcmRecoveryMessagingService`, mover lecturas/escrituras de `AppConfig` fuera del hilo FCM (executor del propio FcmAck) y guardar el `Handler` con `removeCallbacksAndMessages(null)` en `onDestroy()` del Service.
5. Extraer `MqttClientPort` (interfaz con connect/subscribe/publish/close) para poder caracterizar R-07 en JVM con un fake de Paho.

### Fase 3 — Reglas de architecture test (permanentes)
6. Nueva regla en `ArchitectureDependencyTest`: prohibir `dao.insertWithinLimit` / `dao.delete` / `store.delete` fuera de archivos que referencien `DispatchLock` (garantiza R-10 ante futuros dispatchers).
7. Regla anti-regresión: prohibir `GlobalScope`, `runBlocking` en `src/main`, y `Thread(` fuera de `MqttManager`/`FcmTokenRegistrar`/`DiagnosticsReporter` (lista blanca cerrada).
8. Test de ciclo de vida: test JVM que lanza `startWakeLoop` + `requestFlush`, cancela el scope, relanza y verifica que la señal bufferizada se consume (cubre R-04) y que `Job.children` queda vacío tras `cancel` (cubre R-11).

### Fase 4 — Higiene (opcional, no urgente)
9. `DmujeresApp.connectivityReceiver`: evaluar migrar a `registerDefaultNetworkCallback` como ConnectivityObserver y eliminar el broadcast legacy `CONNECTIVITY_ACTION` (deprecado desde API 28; DmujeresApp.kt:126).
10. Documentar en `docs/DEPENDENCY_RULES.md` el convenio "todo scope pasa por `scopeProvider: () -> CoroutineScope` inyectado" para que nuevos componentes no creen scopes propios.

---

## RESUMEN FINAL

1. **0 GlobalScope confirmado**; 3 scopes propietarios reales (serviceScope, stopControllerScope, appScope), todos con cancelación correcta excepto appScope (vida de proceso, intencional).
2. Todos los controladores extraídos (Presence, Watchdog, Connectivity, Outbox, NotificationController, LocationEngine) usan `scopeProvider` inyectado → un solo owner de corrutinas: buen diseño de ownership.
3. **Único riesgo ALTA: R-01** — callback de `testConnection` puede mostrar un AlertDialog sobre Activity destruida (BadTokenException). Fix de 3 líneas con `lifecycleScope`.
4. La serialización MQTT/HTTP vía `DispatchLock.mutex` está verificada en TODAS las rutas (dispatch, microbatch wake, drain, stop-drain, recovery worker): sin doble-delete posible.
5. Los callbacks Paho están correctamente blindados con `client !== newClient` y `close(true)`; no hay estado tocado post-disconnect con efecto.
6. No hay doble registro BootReceiver/SessionKeeper: PendingIntent único + started idempotente.
7. FCM no hace red en su hilo (executor propio); solo SharedPreferences ligero (R-05, baja).
8. Handlers: MainActivity y ContinuityTracker se limpian bien; FCM deja 2 postDelayed sin cancelar (leak de 6-30 s, auto-libera).
9. Executors sueltos: 3 daemon mono-hilo de proceso (aceptable) + watchdog de testConnection sin interrupt (R-08, cosmético).
10. CONFLATED: correcto por diseño; única ventana teórica entre cancel del wakeLoop y relanzamiento (R-04, se autorecupera con el watchdog).
11. Tick 40 verificado (MainActivity.kt:206-207) y sin journey timer separado: la UI no filtra en rotación.
12. Tests de concurrencia sólidos en políticas puras; huecos: cancelación de scope, ciclo de vida del wakeLoop y la ruta UI de testConnection.
13. Ruta recomendada: Fase 1 (2 hotfixes) → Fase 2 (wakeLoop instanciable) → Fase 3 (reglas permanentes).
14. Sin necesidad de tocar `DispatchLock`, `MqttManager.disconnect` ni la secuencia de cierre: ya cumplen.
15. 589 tests JVM existentes son la red de seguridad adecuada para aplicar las fases sin riesgo de regresión de comportamiento.
