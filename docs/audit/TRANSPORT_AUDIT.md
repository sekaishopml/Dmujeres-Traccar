# TRANSPORT_AUDIT — Agente D (R3), solo lectura

Fecha: 2026-09-17 · Scope: `mobile/app/src/main/java/com/dmujeres/traccar/transport/` (5 archivos, 1.238 líneas totales; MqttManager 771). Estado previo verificado: puerto `ControlQueueStore` + `outbox/RoomControlQueueStore` ya migrados hoy; `DispatchLock`/`RttMeter` ya en `core/`. No se modificó código ni se hizo commit.

---

## 1. Inventario de responsabilidades de MqttManager (file:line)

Archivo: `mobile/app/src/main/java/com/dmujeres/traccar/transport/MqttManager.kt` (771 líneas).

### 1.1 Conexión (lifecycle del cliente Paho)
| # | Responsabilidad | Evidencia |
|---|---|---|
| C1 | Estado volátil de sesión (`client/connected/ready/lastError/connecting/connectingStartedElapsedMs`) | 55–60, 70–72 |
| C2 | Entrada única de conexión con guard atómico (`synchronized(this)`) | 147–151 |
| C3 | Cuerpo de conexión: validación config, normalización server, clientId estable por instalación (`mqttClientSuffix`), takeover de sesión previa (`stale.disconnect/close(true)`), construcción de `MqttAsyncClient` | 173–240, 217–232 |
| C4 | Cierre de cliente viejo para que no reviva (`close(true)` — Paho `automaticReconnect=false` propio) | 229–232, 306–311 |
| C5 | Config de `MqttConnectOptions`: keepAlive 45 s, connectionTimeout 10 s, cleanSession, credenciales | 306–319 |
| C6 | LWT (will QoS 1 no retained) en `telemetryTopic` con presence de red perdida | 287–304, 314 |
| C7 | `testConnection` estático para el wizard de UI + `friendlyMqttError` + `buildTestClientId` | 642–742 (companion) |
| C8 | `disconnect()` voluntario: cancela jobs, bump de generación, cierre forzoso | 614–640 |

### 1.2 Reconexión (ReconnectGate + watchdog de cuña)
| # | Responsabilidad | Evidencia |
|---|---|---|
| R1 | Estado de cadencia: `connectAttempts/lastConnectAttemptAt/nextConnectAllowedAt/connectGeneration/connectRetryJob` | 79–83 |
| R2 | `scheduleConnectRetry()`: UN solo reintento cancelable, backoff vía `ReconnectGate.connectDelayMs`, guard por generación | 100–123 |
| R3 | Reset de cadencia en éxito (`connectComplete`) | 246–249 |
| R4 | Incremento de attempts en `connectionLost`/`onFailure` (cap `MAX_ATTEMPTS=10`) | 270, 330 |
| R5 | Watchdog de guard colgado: `clearStaleConnecting()` vía `StaleConnectingPolicy` (15 s, puro/testeable) | 161–171, 745–771 |
| R6 | Vía inmediata validada (`immediate=true` desde `NetworkCallback.onAvailable`, TrackingService.kt:266) con debounce 2 s | 147, 178–179; ReconnectGate.kt:69–70 |

### 1.3 Suscripción
| # | Responsabilidad | Evidencia |
|---|---|---|
| S1 | `subscribeAndDispatch()`: subscribe único a `config.ackTopic()` QoS 1; si ya `subscribed`, solo marca `ready` y arranca dispatch | 349–391 |
| S2 | `scheduleSubscriptionRetry(current)`: reintento fijo 5 s, con guard de generación de cliente (`client === current`) | 393–402 |
| S3 | Reset `subscribed=false` en cada ciclo de conexión/éxito/fallo/desconexión | 223, 253, 269, 329, 562, 629 |

### 1.4 ACK tracker (protocolo de aplicación)
| # | Responsabilidad | Evidencia |
|---|---|---|
| A1 | Estado in-flight: `ackFutures: ConcurrentHashMap<String, CompletableFuture<String>>` + `inFlightSequences` | 63–64 |
| A2 | `handleAck`: parse de ack v1 (schema=1, type=ack), validación deviceId/sequence/status ∈ {accepted,duplicate,rejected,invalid,expired}, `lastAckAt` | 588–612 |
| A3 | Timeout de ACK: `future.get(config.ackTimeoutSeconds)` en `publishWithAck` | 539–551 |
| A4 | `completeInFlightWithoutAck()`: despierta futures con `""` (= sin ACK, no contamina RTT) en pérdida/desconexión | 584–586, 272, 332, 623 |
| A5 | RTT de aplicación EWMA vía `core.RttMeter` (solo con status no vacío) | 543–546 |

### 1.5 Dispatch de presencia (cola de control)
| # | Responsabilidad | Evidencia |
|---|---|---|
| D1 | Wake + loop: `dispatchWake` (Channel CONFLATED), `wakeDispatch()`, `startDispatch()`, `dispatchLoop()` | 66–67, 86–89, 404–525 |
| D2 | Consulta acotada de controles vencidos por el puerto `ControlQueueStore.dueControls(now, 100)` (lote 100) | 424–430, 649 |
| D3 | Restauración de backoff en memoria desde el lote (`retryAt`) | 433–438 |
| D4 | Selección FIFO estricta por `sequence` vía `DispatchPolicy.selectNext` | 442–452 |
| D5 | Semántica de espera: MIN(retryAt futuro) entre DB (`minFutureRetryAt`) + memoria + lote; `withTimeoutOrNull` sobre `receive()` | 453–473 |
| D6 | Single-flight con HTTP vía `core.DispatchLock.mutex` | 477 |
| D7 | ACK ok (accepted/duplicate): `store.delete` + `recordConfirmedPosition` | 480–486 |
| D8 | NACK terminal (rejected/invalid/expired): `store.quarantine` (nunca delete) + callback `onQuarantined` | 487–499 |
| D9 | Sin ACK: attempts+1, `DispatchPolicy.dispatchBackoffMs`, persistencia de backoff, retención infinita (`shouldDiscardUnacked` siempre false) | 501–520 |
| D10 | Publish QoS 1 + registro `lastPublishedAt` | 527–538 |

### 1.6 Manejo de errores y estado observable
| # | Responsabilidad | Evidencia |
|---|---|---|
| E1 | `errorText` normalización de mensajes | 95–98 |
| E2 | `notifyDisconnected`: espeja a `core.MqttStatus` (status/lastError) + `notifyState` callback | 125–131 |
| E3 | Errores de publish: distingue conexión rota (reanuda ciclo) vs fallo de suscripción (retry 5 s) | 554–570 |
| E4 | Swallow silencioso de excepciones de consulta DB (loop sigue; espera en `receive()`) | 428–430, 463–465 |
| E5 | ACK ilegible ignorado (reintento natural) | 609–611 |

### 1.7 Watchdog (es de tracking, no de transport)
El "watchdog 30 s" NO vive en transport: `tracking/TrackingWatchdog.kt:94` (tick `delay(30_000)`) llama `mgr?.connect()` (TrackingWatchdog.kt:256) y MqttManager solo aporta el guard de cuña R5 (15 s). Los tres vigiladores son: watchdog de tick 30 s (tracking), watchdog de guard colgado 15 s (transport, `StaleConnectingPolicy`), y timeout de login 12 s del `testConnection` (MqttManager.kt:646, 696–705).

---

## 2. Dependencias (verificado con grep)

### 2.1 Aristas salientes de transport (imports reales)
| Origen | Destino | Evidencia |
|---|---|---|
| MqttManager.kt | `config.AppConfig` (7), `core.MqttStatus` (3), `core.DispatchLock` (8), `core.MqttServerNormalizer` (9), `core.RttMeter` (10) | imports |
| Envelope.kt | `core.MobileProtocol` (3) | import |
| ReconnectGate.kt | `DispatchPolicy.jitteredDelay` (mismo paquete) | ReconnectGate.kt:58 |

**NO existen** imports `transport → {data, outbox, tracking, ui, health, recovery, readiness, diagnostics, location, oem, sensors}` (grep sobre 5 archivos: solo comentarios doc en ControlQueue.kt:7). Regla congelada además por test de arquitectura: `ArchitectureDependencyTest.kt:58` prohíbe a transport `data/outbox/tracking/ui/recovery/readiness/health/location/oem/sensors`. Nota: `diagnostics` NO está en la lista prohibida de transport (línea 58), pero transport tampoco lo importa hoy; la métrica RTT se aisló en `core.RttMeter` precisamente para no traer diagnostics (DEPENDENCY_RULES.md:72,88).

### 2.2 Aristas entrantes (quién usa transport)
| Consumidor | Usa | Evidencia |
|---|---|---|
| `tracking.TrackingService` | `MqttManager` (crea con `RoomControlQueueStore(dao)` inyectado), `Envelope` | TrackingService.kt:36,42–43,119,584–587,1072,1113 |
| `tracking.TrackingWatchdog` | `MqttManager` (provider + connect tick 30 s) | TrackingWatchdog.kt:26,64,256 |
| `tracking.JourneyStopCoordinator`, `PresenceController` | `MqttManager` / `Envelope` | JourneyStopCoordinator.kt:3,28–29; PresenceController.kt:7 |
| `outbox.RoomControlQueueStore` | implementa `ControlQueueStore` (adaptador Room, edge outbox→transport esperada) | RoomControlQueueStore.kt:5–6 |
| `outbox.PositionOutboxDispatcher` | `DispatchPolicy` (clasificación HTTP) | PositionOutboxDispatcher.kt:9 |
| `ui.MainActivity` | `MqttManager.testConnection` | MainActivity.kt:106,541,576 |
| `ui.DiagnosticsActivity` | `MqttManager` (solo lectura de estado) | DiagnosticsActivity.kt:62 |
| `diagnostics` | NO importa transport; lee `core.MqttStatus` (espejo) | DiagnosticsCollector.kt:215 |

### 2.3 Ciclos reales
- **Ninguno en imports**: core ← nadie; config→core; outbox→{data,transport}; transport→{config,core}; tracking→{outbox,transport,health,diagnostics,...}. `DependencyCycleTest` + reglas lo congelan.
- Único acoplamiento temporal/semántico (no de paquete): `DispatchLock` (core) es el single-flight compartido entre `dispatchLoop` (MQTT) y `PositionOutboxDispatcher.flushOnce` (HTTP); ambos tocan `pending_positions` por `messageId`. Es una arista de CONCURRENCIA, no de import: si se rompe el protocolo "ambos usan el mismo Mutex", aparecen dobles publish/delete — ver riesgo RF-2.

---

## 3. Protocolo: Envelope v1 + topics + QoS + ACK

### 3.1 Envelope v1 (`transport/Envelope.kt`, contrato en `docs/mqtt/protocol-v1.md` vía MobileProtocol)
- Cabecera fija y orden determinista: `schema=1, type, messageId, deviceId, sequence, sentAt, observedAt, payload` (Envelope.kt:92–101 posiciones; 182–192 presencia). El server deduplica con hash canónico por CAMPOS (no bytes) — MobileMqttConsumer.java:250 (`Ack` replica envelope).
- `type ∈ {position, presence, ack}`. Posiciones HOY no van por MQTT (van por HTTP, `PositionOutboxDispatcher`); el dispatch MQTT solo drena `isControl=1` (MqttManager.kt:414–430) — `buildPosition` queda como contrato de cable compartido que outbox no serializa por MQTT.
- Presence: payload con pendientes/batería/red/GPS + opcionales observables (rtt/signal/netCause/anti-trampa/GNSS/rejectBreakdown) que solo se serializan si son conocidos (Envelope.kt:150–192); señales de jornada `journeyStarted/journeyEnded/journeyId` (179–181).
- `messageId` = `dmj-<hashDev8>-<seq12>-<uuid32>` (195–200): estable + anti-colisión cross-device con el mismo hash.

### 3.2 Topics / QoS
- Uplink: `dmj/v1/devices/{id}/telemetry` (publish QoS 1, no retained; LWT al mismo topic QoS 1). Downlink: `dmj/v1/devices/{id}/ack` (subscribe QoS 1). Valores en `core/MobileProtocol.kt:22–28` (espejados en server Keys.java:31,34: `mobile.mqtt.topic = dmj/v1/devices/+/telemetry`, `mobile.mqtt.ackTopic = dmj/v1/devices/{deviceId}/ack`).
- Server: QoS AT_LEAST_ONCE + `manualAcknowledgement(true)` — no manda ACK de aplicación si el payload excede `maxPayload`, la cola de workers se llena, capacity admission llena, o el parse falla; el publish queda sin PUBACK → **redelivery broker** (MobileMqttConsumer.java:131–161). Status `PENDING` también deja el publish sin ack (185–190) → el móvil lo ve como timeout, no como NACK.

### 3.3 Semántica ACK (contrato mobile↔server)
| Status | Origen server | Acción móvil |
|---|---|---|
| `accepted` | ingesta OK (dedup settle) | delete de cola, confirma jornada si `type=position`+journeyId (MqttManager.kt:577–582) |
| `duplicate` | dedup por hash canónico | delete (idempotente, es éxito) |
| `rejected` | negocio (dispositivo/recurso) | **cuarentena** (`dead_letters`), jamás delete directo; callback `onQuarantined` |
| `invalid` | parse/schema/hash inválido (p.ej. EXPIRED-vs-INVALID split server: MobileIngestionService.java:352) | cuarentena |
| `expired` | ventana temporal vencida | cuarentena |
| (sin ACK / `PENDING`) | server no publica nada, o redelivery tras fallo interno | backoff `DispatchPolicy.dispatchBackoffMs` 5s·2^n ±25% jitter, techo 5 min; **retención infinita** (`shouldDiscardUnacked=false` siempre) |

Guard móvil en `handleAck` (588–606): schema=1, type=ack, `deviceId == config.deviceId`, `sequence == inFlightSequences[messageId]`, status ∈ los 5 finales. Cualquier otra cosa se descarta → reintento natural. RTT solo con status real ("" de desconexión no contamina, 543–546).

### 3.4 Riesgos de contrato con MobileMqttConsumer (server-side)
| ID | Riesgo | Evidencia | Severidad |
|---|---|---|---|
| RC-1 | **LWT con sequence=0**: presence sintética con `sequence 0` y `messageId "lwt-<ts>"` fuera de la cola. Si el server deduplica por (deviceId, sequence) en alguna ruta, colisiona con el primer mensaje real de la sesión; y `pending=0` + `network=none` en el LWT puede distorsionar métricas server (es lo único que viaja al cortarse TCP). Además comentario dice `network="lost"` pero se serializa `"none"` (doc-drift). | MqttManager.kt:287–304 | **Media** |
| RC-2 | **Sub/ack hardcodeados al contrato**: el cliente subscribe SIEMPRE a `dmj/v1/devices/{id}/ack`; si el operador cambia `mobile.mqtt.ackTopic` server-side (es config en Keys.java:34), los ACK se publican a otro topic y el móvil entra en retención infinita sin NACK ni error visible (solo `lastAckAt` congelado). No hay verificación de que el topic de ack recibido coincida. | MqttManager.kt:359; Keys.java:34 | Media |
| RC-3 | **`reconnect24h` muerto**: `connectComplete(reconnect=true)` solo ocurre con `automaticReconnect=true`, que el cliente desactiva (310). La métrica nunca incrementa → ciego en paneles de flapeo. | 250, 310 | Baja |
| RC-4 | **ACK de sesión limpia**: `cleanSession=true` (313). Si el móvil se cae justo tras aceptar el publish server-side pero antes del ACK, al volver NO recibe reentrega del ack (la sesión del subscriber es nueva). La cola móvil reenvía (QoS1 + retención) y el server responde `duplicate` → camino cubierto, PERO depende de que la dedup server sea por hash canónico persistente, no por memoria de sesión. | 313; MobileMqttConsumer.java:166–181 (deviceTails) | Baja-Media |
| RC-5 | **Ventana de `expired`** (server: MobileIngestionService.java:352): presence en backoff largo (techo 5 min) con jornada ya cerrada server-side cae a `expired` → cuarentena. Correcto, pero si el panel depende de `ended` para cerrar UI, la señal solo llega vía quarentine callback; asegurar que `onQuarantined` dispara reposición server de estado de jornada. | 487–499 | Baja |
| RC-6 | **single-flight asume idempotencia de delete/quarantine**: `store.delete` devuelve filas (0 = ya no estaba) y `quarantine` devuelve false si ya no estaba — contrato del puerto está bien, pero TODO consumidor futuro del puerto debe mantenerlo o el dedup se rompe. | ControlQueue.kt:32–41 | Baja |

---

## 4. Reconexión / timeouts / races entre callbacks

### 4.1 Cadencia (quién dispara qué)
| Emisor | Cadencia | Pasa por `connect()`? | Evidencia |
|---|---|---|---|
| `connectionLost` (Paho) | evento | sí → `scheduleConnectRetry` (backoff 5s·2^n techo 5 min) | 264–275, 107–123 |
| `onFailure` (login) | evento | sí → igual | 324–335 |
| `NetworkCallback.onAvailable` (tracking) | evento por flapeo | sí, vía `immediate=true` (debounce 2 s, sin backoff) | TrackingService.kt:266; ReconnectGate.kt:69–70 |
| Watchdog tick (tracking) | 30 s | sí, vía backoff (sin loop propio) | TrackingWatchdog.kt:94,253–256 |
| Reintento programado | 1 tiro cancelable | sí | 107–123 |
| Paho `automaticReconnect` | — | **desactivado** (dueño único de cadencia = app) | 306–311 |

`ReconnectGate` (puro, testeable): primer retry 5 s, factor 2.0, techo 300 s, jitter ±25%, debounce 2 s (ReconnectGate.kt:26–70). Guard atómico: check `connecting/connected` + creación + connect bajo `synchronized(this)` → un solo cliente (147–151, 133–146). Generación `connectGeneration++` en cada intento + check en callbacks (`client !== newClient`) neutraliza callbacks de clientes viejos (245, 266, 326, 361, 375, 384).

### 4.2 Races con evidencia
| ID | Race | Evidencia | Severidad |
|---|---|---|---|
| RR-1 | **`connecting=false` antes del guard de cliente en `connectionLost`/`onFailure`**: un callback del cliente VIEJO (después de `stale.close(true)` en un connect() nuevo) puede ejecutar `connecting=false` ANTES de detectar `client !== newClient` y salir (264–266, 325–326). Ventana: un tercer `connect()` ve el guard libre y abre un intento adicional mientras el nuevo ya conecta. El `clearStaleConnecting` (161–171) también libera el guard sin distinguir cliente. Consecuencia: intento redundante + doble `scheduleConnectRetry` (mitigado por debounce/backoff, no anulado). | 264–266, 324–326 | **Media** |
| RR-2 | **Doble arranque de `dispatchLoop`**: `startDispatch()` (404–407) hace check-then-act sin mutex (`dispatchJob?.isActive`); `wakeDispatch` corre en threads de callers arbitrarios y `connectComplete`/`subscribe.onSuccess` en hilos de Paho (86–89, 260, 371). Dos arranques → 2 loops → doble publish del mismo control (mitigado por delete idempotente y `duplicate`, pero rompe single-flight y multiplica tráfico). | 404–407 | Media |
| RR-3 | **DB caída durante `dueControls`** → `emptyList()` (428–430): items con backoff en MEMORIA solo (retryAt restaurado del lote previo) quedan invisibles; `minFutureRetryAt` también fallando → `DispatchPolicy.nextRetryAt(emptyList)=null` → `dispatchWake.receive()` sin timeout (463–467). Recovery: cualquier `wakeDispatch` posterior (heartbeat/ciclo) reconsulta. Sin wake nunca más, el item vencido NO se reintenta (stall hasta evento). | 424–473 | Media |
| RR-4 | **Futuro completado con `""` en desconexión**: `completeInFlightWithoutAck` (584–586) despierta el `future.get` → `""` → null → backoff. Correcto, pero si `connectionLost` y luego el publish estaba EN VUELO, el `finally` (571–574) limpia maps mientras `handleAck` de un ACK ya en camino puede llegar con `expectedSequence == null` → ACK válido descartado → reintento + `duplicate` del server (pérdida 0, gasto 1 publish). | 584–586, 602–606 | Baja |
| RR-5 | **`ready` flasea en fallo de publish (554–570) con cliente CONECTADO**: se de-subscribe lógicamente (`subscribed=false, ready=false`) y se agenda retry de suscripción, pero `subscribeAndDispatch` no re-suscribe si Paho aún cree que está suscrito al broker (`subscribed` es estado móvil, no del broker) — re-subscribe real a `ackTopic` (idempotente en broker, sin daño). Ventana: heartbeat no sale hasta retry 5 s. | 554–570, 393–402 | Baja |
| RR-6 | **Timeout de ACK vs ciclo de vida**: `ackTimeoutSeconds` configurable; si > keepAlive 45 s o ~duración de outage corto, el ACK llega tras `completeInFlightWithoutAck` (RR-4) — cubierto por duplicate. Si `ackTimeoutSeconds` < RTT p99 real, cada mensaje tarde entra en backoff aunque server lo aceptó → inflación de traffic. `RttMeter` da la señal para calibrar, pero no se autoajusta. | 541, 543–546 | Baja |

---

## 5. Frontera propuesta: transport vs outbox vs health vs recovery

Principio: **transport = protocolo cable MQTT + política de reconexión/dispatch; outbox = dueño de la cola persistida; health = observabilidad; recovery = reanimación de sesión/proceso.** "transport NO Room" ya se cumple (puerto); lo que falta es pulir dónde viven las piezas de política compartida.

### 5.1 Ya correcto (mantener, congelar con tests)
| Pieza | Ubicación | Justificación |
|---|---|---|
| `ControlQueueStore`/`ControlItem` (puerto) | transport | Depende solo del consumidor (MQTT lo consulta); el dueño outbox lo implementa. Dirección outbox→transport está justificada por dependency-inversion (consumer-defined port). |
| `RoomControlQueueStore` | outbox | Room es dominio de outbox; mapeo 1:1 (RoomControlQueueStore.kt:15–58). |
| `DispatchLock` | core | Single-flight cross-dominio MQTT/HTTP: no puede vivir en ninguno de los dos (sería acoplamiento circular implícito). Core es el único lugar sin aristas. |
| `RttMeter` | core | Métrica transport-agnóstica; evita edge transport→diagnostics (DEPENDENCY_RULES.md:88). |
| `MqttStatus` | core | Estado espejo consumido por diagnostics/health/ui sin acoplar a transport. |
| `MqttServerNormalizer` | core | Puro, compartido con testConnection y verificación de config. |
| `MobileProtocol` (topics/endpoints) | core | Contrato server↔mobile; prohibido tocar valores sin migración. |
| Health/recovery | tracking/health, tracking/recovery | Ya no conocen MqttManager; leen `MqttStatus`/`LinkState` (diagnostics) y vigilan vía `TrackingWatchdog` (evento connect). `recovery` solo consume `FlushOutcome.transportOk` (TrackingRecoveryWorker.kt:155–158) — frontera limpia por DTO. |

### 5.2 Frontera DENTRO de transport: real vs artificial (regla: dividir por frontera real, no por moda)

Candidatos propuestos (NO ejecutar): `MqttConnectionManager` / `MqttSubscriptionManager` / `MqttAckTracker` / `MqttPresenceDispatcher`.

| Candidato | Veredicto | Justificación |
|---|---|---|
| **MqttAckTracker** (A1–A5: ackFutures, inFlightSequences, handleAck, completeInFlightWithoutAck, RTT hook) | **FRONTERA REAL — extraer** | Máquina de estados pura y autocontenida: entrada = mensaje ACK + registro/limpieza por messageId; salida = CompletableFuture. Cero dependencia de Paho-callbacks (solo `MqttMessage` en handleAck, envuelvable en String). Testable JVM sin broker. Hoy vive disperso en 4 zonas del archivo (63–64, 527–575, 584–586, 588–612): extraer REDUCE acoplamiento, no lo inventa. |
| **MqttPresenceDispatcher** (D1–D10: dispatchLoop, publishWithAck, policy calls, quarantine) | **FRONTERA REAL — extraer** (con puerto de publicación) | El dispatch de presencia es un bucle de estado propio (retryAt mem, lote, backoff, cuarentena) que solo necesita del cliente: `publish(topic,payload,qos)` + señal `ready`. Puertear a `MqttPublisher { suspend fun publish(...): Boolean; val isReady: Boolean }` lo desacopla del ciclo Paho y vuelve testeable el flujo ACK→delete/quarantine/backoff con fake. Es el 40 % del archivo con la lógica de negocio más crítica (retención infinita, FIFO). |
| **MqttConnectionManager** (C1–C8, R1–R6, S1–S3: ciclo cliente + gate + generación + LWT) | **FRONTERA PARCIAL — fusionar con suscripción** | El ciclo del cliente y la suscripción están casados: `connectComplete` → `subscribeAndDispatch`; `ready = connected && subscribed` es UN solo estado; cualquier separación obliga a inventar protocolo de sincronización entre dos managers sobre el MISMO cliente mutable (duplicaría la generación/cliente-check que hoy es 1 variable). Dividir aquí es frontera artificial: la cohesión real es "mantener sesión lista". SÍ es real extraerlo de MqttManager si AckTracker y Dispatcher salen: queda "SessionManager" (connect+subscribe+callbacks+LWT+testConnection) con frontera clara hacia el puerto `MqttPublisher`. |
| **MqttSubscriptionManager** (S1–S3 solo) | **ARTIFICIAL — NO dividir** | Solo existe 1 suscripción, su retry (5 s) y un flag. Extraerlo crea el peor tipo de módulo: lifecycle paralelo del mismo cliente con estado duplicado (connected/subscribed/ready compartidos). No hay frontera de datos ni de fallo que lo justifique. |
| `testConnection`/`friendlyMqttError`/`buildTestClientId` (C7) | **Frontera REAL menor — extraer a `MqttConnectionTester`** (baja prioridad) | Es utilidad del wizard de UI (MainActivity), no del ciclo de vida; no comparte estado con el manager (crea cliente propio). Extraerla reduce el archivo y saca `ui-use-case` de la clase de transporte. Riesgo bajo, beneficio bajo. |

Resultado propuesto (3 piezas, no 4): `MqttSessionManager` (conexión+suscripción+LWT+gate-glue), `MqttAckTracker` (puerto de ACK), `MqttPresenceDispatcher` (dispatch por puerto `MqttPublisher`), + `MqttConnectionTester` (companion). ReconnectGate/StaleConnectingPolicy/DispatchPolicy quedan como políticas puras que ya están bien separadas (testables sin Android).

---

## 6. Riesgos de refactor + tests de caracterización

### 6.1 Tests existentes que hay que congelar ANTES de refactor
| Test | Qué fija | Cobertura hoy |
|---|---|---|
| `transport/MqttRobustnessTest.kt` | clientId (sufijo/sanitización/truncado), timeout 12 s, batch 100, `StaleConnectingPolicy` (guard 15 s, bordes) | Solo companion + policy. NO cubre connectLocked ni callbacks (necesita Android/Paho — es el hueco principal). |
| `transport/DispatchPolicyTest.kt` | FIFO estricto por sequence (ended al final), backoff mem+DB max, nextRetryAt MIN, retención infinita sin ACK, clasificación HTTP | Bueno para selectNext/backoff/classify. NO cubre el LOOP (D5–D9). |
| `transport/ReconnectGateTest.kt` | primer retry 5 s, exponencial+techo, ventanas via-backoff/via-inmediata | Cubre política pura, no el pegamento (scheduleConnectRetry/generación). |
| `transport/EnvelopeTest.kt` | serialización exacta v1 (campos conocidos/omitidos, messageId colisiones) | Contrato de cable: es el que más protege RC-x. |
| `outbox/PositionOutboxDispatcherTest.kt` + `DispatchRobustnessTest`, `PositionOutboxMicroBatchTest` | flush HTTP, clasificación, cuarentena, single-flight contra MQTT | Protegen el otro lado de `DispatchLock`. |

### 6.2 Riesgos de refactor y caracterización recomendada (nuevos, JVM donde se pueda)
| ID | Riesgo | Test de caracterización recomendado |
|---|---|---|
| RF-1 | Al extraer AckTracker, romper la igualdad `expectedSequence != sequence` o el filtro de deviceId → ACKs válidos ignorados / aceptados cruzados | JVM: AckTracker caracterizado con casos: ack correcto; deviceId distinto; sequence distinto; status no-listado; schema≠1; payload basura (no-crash); doble complete. Congelar outputs antes de mover código. |
| RF-2 | Al extraer Dispatcher, perder el orden `DispatchLock.withLock { publish→decide→store }`: doble publish/delete si se queda fuera del lock | Robolectric o fake: dos dispatchers concurrentes (fake publisher lento) + FakeControlQueueStore; aserción: 1 solo publish por messageId, delete llamado ≤1 con resultado coherente. |
| RF-3 | Recrear dispatchJob doble (RR-2) si la extracción cambia el check de `isActive` | Test de concurrencia: N llamadas simultáneas a wakeDispatch → aserción `AtomicInteger(loopStarted)==1` (exponer contador de inicio de loop o espiar el Job). |
| RF-4 | Generación/cliente-check roto en SessionManager (el `client !== newClient` y `connectGeneration` son el pegamento anti-stale) | Con Robolectric o abstracción de Paho (interface `MqttClientHandle`): simular callbacks de cliente viejo tras reconnect → aserción de que no limpia `connecting` del nuevo (hoy RR-1 lo hace — caracterizar el COMPORTAMIENTO ACTUAL incluido el bug antes de decidir fix). |
| RF-5 | `wakeDispatch()` cambia de semántica: hoy despierta el channel Y fuerza startDispatch; si el Dispatcher extraído duerme con `withTimeoutOrNull`, un wake perdido = stall (RR-3). | Test del loop con store que lanza en dueControls → aserción de recuperación al siguiente wake; y con `minFutureRetryAt` nulo → espera indefinida hasta wake (documentar semántica como contrato del puerto). |
| RF-6 | Retención infinita vs cuarentena: si la extracción reordena las ramas (`accepted/duplicate` → `rejected/invalid/expired` → else), un error transitorio de DB dentro del lock podría caer en rama de cuarentena. | Test por rama con store fake que lanza en cada operación: aserción de que el item permanece en cola (nunca desaparece sin ACK de negocio). |
| RF-7 | LWT: mover código de conexión puede cambiar payload/options (orden de `setWill`, cleanSession, keepAlive) → zombie-sessions EMQX regresan (comentario 213–216). | Test de contrato de `MqttConnectOptions` (reflection o builder fake): automaticReconnect=false, connectionTimeout=10, keepAlive=45, cleanSession=true, will en telemetryTopic QoS1 no-retained, credenciales condicionales. |

---

## 7. Severidades consolidadas

| Nivel | Ítems |
|---|---|
| **Alta** | — (ninguna bloqueante; el estado actual es estable y probado) |
| **Media** | RR-1 (callback de cliente viejo limpia guard `connecting` del nuevo), RR-2 (doble arranque de dispatchLoop), RR-3 (stall de items vencidos si DB falla sin wake posterior), RC-1 (LWT sequence=0/payload vs comentario), RC-2 (ackTopic hardcodeado vs config server) |
| **Baja** | RR-4 (ACK tardío perdido → duplicate), RR-5 (fallo de publish de-subscribe lógico 5 s), RR-6 (ackTimeout vs RTT p99 sin autoajuste), RC-3 (métrica reconnect24h muerta), RC-4 (cleanSession → duplicate depende de dedup persistente), RC-5 (ended expirado → cuarentena), RC-6 (contrato idempotente del puerto no congelado en test) |

Refactor recomendado en orden: (1) extraer `MqttAckTracker` (frontera real, riesgo bajo, protegido por caracterización RF-1), (2) extraer `MqttPresenceDispatcher` con puerto `MqttPublisher` (RF-2/3/5/6), (3) dejar sesión+suscripción juntas como `MqttSessionManager` (RF-4/7, NO crear SubscriptionManager), (4) opcional `MqttConnectionTester` para el companion de UI. Correcciones de behavior aparte del refactor: RR-1/RR-2/RC-1/RC-3 con sus tests.

---

## Resumen final (≤15 líneas)

`transport` hoy es dependencia-sano: solo importa `config`+`core` (grep sin `data/outbox/ui`), cumple "transport NO Room" vía puerto `ControlQueueStore`, y outbox lo implementa; health/recovery ya no conocen MqttManager (leen `MqttStatus`/`LinkState` y `FlushOutcome`). El protocolo Envelope v1/QoS1/ACK está bien congelado por EnvelopeTest y las políticas puras (DispatchPolicy/ReconnectGate/StaleConnectingPolicy) son testeables. Riesgos reales encontrados: (1) race de callbacks de cliente viejo que limpia el guard `connecting` del nuevo (RR-1, Media); (2) doble arranque posible de dispatchLoop por check-then-act (RR-2, Media); (3) stall de items vencidos si Room falla sin wake posterior (RR-3, Media); (4) LWT con sequence=0/payload-drift (RC-1, Media) y ackTopic no negociable (RC-2, Media). Frontera interna propuesta: MqttAckTracker (REAL), MqttPresenceDispatcher con puerto MqttPublisher (REAL), MqttSubscriptionManager (ARTIFICIAL — fusionar con conexión en MqttSessionManager), MqttConnectionTester (real menor). Antes de mover una línea: congelar MqttRobustnessTest/DispatchPolicyTest/ReconnectGateTest/EnvelopeTest y añadir caracterización de loop+ACK+lock (RF-1…RF-7). No hay severidad Alta; el refactor de fronteras es seguro por fases con esos tests.
