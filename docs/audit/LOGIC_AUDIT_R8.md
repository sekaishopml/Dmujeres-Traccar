# Auditoría de lógica — D-Mujeres Tracking Android (R8)

- **Alcance**: `mobile/app/src/main/java/com/dmujeres/traccar` (pipeline de captura, estados/concurrencia,
  auto-jornada, resiliencia, coherencia de datos, batería/recursos, errores silenciados, deuda).
- **Método**: lectura estática del código real (no se ejecutó la app ni tests). READ-ONLY:
  no se modificó ningún archivo de código. Fecha: 2026-09-19.
- **Síntomas de campo a los que responde este informe**: procesos congelados por OEM (huecos de
  10–45 min), despertar FCM que no logra fix, "solo marca puntos donde estuvo sin trazar ruta".
- **Ya resuelto y no re-auditado aquí**: `docs/audit/R3..R7` (arquitectura, OEM, transporte, seguridad).
  Varios hallazgos de esta ronda son de lógica fina que esa documentación no cubría.

---

## (a) TOP-15 de hallazgos

Leyenda severidad: **ALTA** = rompe una garantía funcional/privacidad o explica un síntoma de campo;
**MEDIA** = degrada resiliencia/diagnóstico o exige condiciones raras; **BAJA** = deuda/higiene.

| # | Sev | Archivo:línea | Hallazgo | Impacto real | Fix propuesto |
|---|-----|---------------|----------|--------------|----------------|
| 1 | **ALTA** | `recovery/FcmRecoveryMessagingService.kt:101-103` (+`recovery/FcmRecoveryPolicy.kt:41-70`) | Un probe FCM válido hace `config.trackingEnabled = true` y arranca el servicio **sin comprobar consentimiento** (el payload no lo verifica). | Si la colaboradora apagó el seguimiento (o la jornada se cerró y el server no lo sabe aún), un probe la re-activa: se rastrea sin acción del usuario. Riesgo de privacidad/legal y contradice `SessionKeeperPolicy`/`BootReceiver`, que sí exigen `trackingEnabled`. | Gate `config.trackingEnabled` (o flag de consentimiento explícito) en `onMessageReceived`; si está off → ACK `RECOVERY_BLOCKED` con reason `DISABLED_BY_USER`. Igual en `ScheduleVerify`. |
| 2 | **ALTA** | `sensors/MotionSensor.kt:130-133`, `:146-154`, `:114-124` | `maybePauseIfStationary()` desregistra el listener tras 5 min quieto, pero **no limpia `registered`**; `reRegisterOnFix()` hace early-return con `registered.get()==true`. El acelerómetro no se vuelve a registrar nunca dentro de la jornada. Además `state` queda en `STATIONARY` (no `UNKNOWN`). | Tras 5 min parada, toda la evidencia de sensor se apaga para auto-jornada, `MovementStartPolicy`, rescate R5 y `LocationSensorFusion`; peor: miente "STATIONARY" en vez de "desconocido". Contribuye a falsos negativos de movimiento con GPS ralo. | Marcar pausa con flag propio (`pausedForStationary`) o `registered.set(false)` en `unregisterListener()`; al pausar, `state = UNKNOWN`; test de ciclo pausa→fix→re-registro. |
| 3 | **ALTA** | `tracking/TrackingService.kt:247-248` vs `:585`, `:1574`; `location/MovementRescueController.kt:56-63`, `:109` | `MovementRescueController` recibe `scope = serviceScope` **por valor** al construir. En el camino `pendingStart` (`finishStopping`→`startTracking`) se crea un `serviceScope` nuevo (`:1574`), pero el controller sigue lanzando su tick y sus rescates en el scope **cancelado**. | Tras un stop+start en la misma instancia, el observador de rescate queda muerto: no hay `nudgeRefresh + requestOneShotFix` periódico. Es exactamente el escenario "proceso vivo pero sin GPS y nadie lo empuja". | Inyectar `scopeProvider: () -> CoroutineScope` (como el resto de componentes) y usarlo en `start()`/`startRescue()`. |
| 4 | **ALTA** | `recovery/SessionKeeper.kt:68-139` vs `recovery/FcmRecoveryMessagingService.kt:79`; `recovery/RescueWindow.kt:24` | El guardián por alarma **no abre ninguna ventana de CPU** al despertar; solo FCM abre `RescueWindow` (90 s). `setAndAllowWhileIdle` despierta el proceso pero tras entregar el broadcast rigen de nuevo las restricciones (y congeladores OEM actúan en segundos). | El wake del guardián puede morir antes de que el GPS enganche → "los despertares SÍ ocurren pero no hay fix" y huecos de 10–45 min persistidos. Es un hueco de diseño, no de configuración. | Abrir `RescueWindow` (acotada) también en `SessionKeeperReceiver` y en el camino `ACTION_START` de recuperación; o mover el burst de adquisición a un `LocationEngine.requestOneShotFix()` inmediato al arrancar recovery. |
| 5 | **ALTA/MEDIA** | `recovery/FcmRecoveryMessagingService.kt:93-99`; `tracking/TrackingService.kt:819-825` vs `:603-610` | Con el servicio ya vivo, el FCM solo llama `TrackingService.refresh` → `engine.nudgeRefresh()` (re-registra el request pasivo). **No pide un fix one-shot** durante la ventana de 90 s, a diferencia de SignificantMotion (`requestOneShotFix`). | "Despertar FCM que no logra fix": con FLP en cadencia de quietud (60 s) y cold-start GPS de 30–60 s, la ventana se consume sin una adquisición activa. | En el path `already-running` (y en `onManualRefresh`), llamar también `engine.requestOneShotFix()` / burst tipo `MovementRescueController`. |
| 6 | **MEDIA** | `tracking/TrackingService.kt:1094-1108`; `tracking/TrackingSessionController.kt:44`; `location/FixFilter.kt:85-86` | En recuperación de jornada, `session.startedTrackingAt` es el **inicio original** (horas atrás). El anti-livelock compara `now - startedTrackingAt >= 5 min` → **cierto en el primer intento**, así que el primer fix con accuracy ≥150 m se fuerza a `Accept(lowQuality=true)` de inmediato. La ventana "con memoria del bueno" que documenta `FixFilter` no sobrevive a la muerte del proceso (es in-memory). | Un fix de 300 m de precisión (red) se encola como semilla de la ruta y de la regla OR tras cada recuperación → trazo con drift y distancia inflada justo al reanudar. | Forzar el bypass solo con `emptyWindowRejects >= N` **de esta corrida** (contador que no use el inicio de la jornada) o persistir la ventana/último bueno en `AppConfig`. |
| 7 | **MEDIA** | `tracking/TrackingService.kt:874-875`, `:1084-1085`; `location/FixFilter.kt:262-283` | `observedAt` se serializa desde `location.time` **sin clamp de futuro**; el filtro no valida relojes adelantados. Un fix con `time` futuro además vuelve "viejo" al anterior y hace pasar la regla de frecuencia (`timeMs - ref.timeMs >= frequencyMs`). | Posiciones con timestamp futuro → el server puede `expired/invalid` (cuarentena) y el trazo/orden temporal se rompe. Es el caso típico tras rollback NTP o GPS week rollover. | Clampear `observedAt` a `[journeyStart - margen, now + tolerancia]`, contar `clock_future` en rejectBreakdown y/o rechazar `time > now + 2 min` en `FixFilter.evaluate`. |
| 8 | **MEDIA** | `tracking/AutoJourneyPolicy.kt:90-91`; `tracking/TrackingService.kt:1007-1022`, `:1058`, `:1044-1069`; `config/AppConfig.kt:740` | Tres problemas en auto-jornada: (1) `isMovingSample` acepta **solo sensor** (`motionMoving == true`) → una caminata/meneo sostenido 90 s abre jornada en contra del propio KDoc ("evita pasos dentro de casa"); (2) `autoJourneyStartRequested` solo se resetea si el start **falla** (`:1058`) — en un start exitoso nunca se re-arma dentro de la misma instancia; (3) el arranque automático no pasa por `startIfReady` (permisos/readiness) y `applyRemote` puede encenderlo remotamente (`AppConfig.kt:740`). | Jornadas falsas (caminando), función que no vuelve a disparar y apertura remota sin confirmación en dispositivo. | Para START exigir evidencia cinemática (velocidad >4 m/s o desplazamiento), no sensor solo; resetear flags al cerrar jornada; aplicar al menos los mismos gates de permiso/readiness; no exponer `autoJourney` por remoto sin consentimiento local. |
| 9 | **MEDIA** | `recovery/SessionKeeper.kt:41-51`, `:78-85` | `schedule()` usa `setExactAndAllowWhileIdle` si `canScheduleExactAlarms()`; si esa llamada lanza (carrera de revocación, appops/OEM), **no cae a la alarma inexacta**: el `onFailure` solo loguea y la cadena queda rota sin re-armarse hasta el próximo `startTracking` o cambio de modo. | El guardián anti-OEM muere en silencio: a partir de ahí, congelado = hueco hasta que algo más arranque el servicio. | En el `onFailure` de exacta, programar `setAndAllowWhileIdle` (fallback); registrar contador `keeperScheduleFail24h`; considerar re-arm independiente desde el watchdog. |
| 10 | **MEDIA** | `tracking/TrackingService.kt:1289-1305`; `tracking/TrackingWatchdog.kt:269-274`; `data/PositionDao.kt:140-151` | En `stop_capture`, se pausa la captura al 100 % del tope (100 000) y se reanuda al **80 %**; el único drenaje es el goteo de 50 msg/30 s del watchdog. Con ~20 000 mensajes de histéresis a 100/min, la captura queda **pausada horas** aunque haya espacio. | En flotas con `stop_capture` (evitar drop), jornadas largas sin red dejan de capturar mucho después de que el buffer ya drenó. Se ve como "no traza" con cola baja. | Reanudar con umbral cercano al tope (p. ej. `>= max - 500`), o drenar agresivo (`drainBacklog`) mientras esté pausado, o combinar con `drop_oldest` para el excedente. |
| 11 | **MEDIA** | `outbox/PositionOutboxDispatcher.kt:181-185`; `transport/MqttManager.kt:440-446`, `:625-627`; `tracking/JourneyStopCoordinator.kt:36-43` | Errores silenciados en puntos ciegos: lectura de Room del flush devuelve `transportOk=true` sin log (fallo de DB parece "nada que enviar"); el dispatch MQTT traga excepciones de DB (`emptyList`); ACKs ilegibles se ignoran sin log; el drain de cierre va en `runCatching` sin registrar el error. | Diagnóstico imposible de "no traza": la cola puede estar intacta y el sistema se ve "sano". | Log/breadcrumb en cada catch con contador en health; outcome diferenciado `dbError` en `FlushOutcome`. |
| 12 | **MEDIA** | `tracking/TrackingWatchdog.kt:325-338`; `tracking/TrackingStatePolicy.kt:33` | Al revocar **FINE** en caliente solo se publica `PERMISSION_MISSING` en la notificación: la alerta del watchdog exige `!backgroundGranted && fineGranted` (solo BACKGROUND). No se alerta ni se ordena parar; el motor sigue re-solicitando cada 2 min y re-afirmando FGS location. | Revocación de permiso silenciosa para la usuaria; servicio en estado degradado indefinido; en Android 14 el `startForeground(location)` puede lanzar SecurityException repetido. | Alerta dedicada para FINE revocado (una por episodio) y transición a un estado explícito que suspenda captura y ofrezca reparar permisos. |
| 13 | **MEDIA/BAJA** | `outbox/PositionOutboxDispatcher.kt:332-351`; `tracking/TrackingService.kt:709-723`, `:1613-1628` | Hooks estáticos (`wakeDao`, `mqttReady`, `onFlushOutcome`, `wakeCtxProvider`) se setean al arrancar y **nunca se limpian** al destruir el servicio: retienen lambdas que capturan `this@TrackingService`/`AppConfig` muertos. | Fuga de memoria acotada (1 instancia) y callbacks que leen estado de un servicio viejo si algo dispara el wake tras `onDestroy`. | Limpiar hooks en `onDestroy`, o encapsular en un holder con dueño de ciclo de vida (no singleton). |
| 14 | **MEDIA/BAJA** | `location/ActiveGpsPolicy.kt:78-136`; tests `ActiveGpsPolicyTest.kt:130-183`; comentarios `FixFilter.kt:27`, `:57` vs `LocationEngine.kt:396-398` | Existen **dos máquinas de estado de movimiento**: `AdaptiveDistancePolicy.nextMode` (histéresis 5/1 m/s) que solo usan los tests, y `MovementStartPolicy` (5/1.5/1 m/s + desplazamiento + sensor) que es la real vía `LocationEngine.applyAdaptiveMode`. Los comentarios dicen "15 m" donde el valor es 24 m. | Falsa confianza: los tests congelan una política que producción no ejecuta; el razonamiento de "duty cycle" apunta a la lógica equivocada. | Borrar `nextMode`/tests o unificar en una sola política; corregir comentarios (24 m). |
| 15 | **BAJA** | `tracking/TrackingService.kt:1241-1256`, `:1371`, `:1412`; `tracking/JourneySummaryPresenter.kt:38` | Cada fix aceptado hace `serviceScope.launch` en `Dispatchers.Default` (sin actor); el orden de ejecución no está garantizado respecto al orden de llegada, y `journeyStartAt` (journeyId) se lee sin lock mientras `summarizeAndReset` puede ponerlo a 0 en paralelo. | En ráfagas (5 s + reintentos) puede quedar un `sequence` con orden temporal invertido; una posición del cierre puede viajar con `journeyId=0`. Sin pérdida de datos, pero ensucia continuidad server-side. | Cola/actor único para el encolado (o `launch(start = CoroutineStart.UNDISPATCHED)` no basta: usar `Channel`), y snapshot de `journeyId` al inicio del fix. |

### Detalle corto de los hallazgos más discutibles (honestidad)

- **#1** no es "paranoia": `SessionKeeperPolicy.decide` (`SessionKeeper.kt:146-151`) y `StuckStopPolicy`
  (`StuckStopPolicy.kt:27-31`) usan `trackingEnabled` como consentimiento; el camino FCM es el único que lo
  salta. Si el server deja de mandar probes al cerrar jornada el riesgo baja, pero el cliente no debe depender de eso.
- **#2** contradice el KDoc del propio método (`MotionSensor.kt:126-129` dice "re-registrar en cada fix aceptado").
  El watchdog (`TrackingWatchdog.kt:148-153`) intenta `reRegisterOnFix()` cada 30 s durante huecos, siempre no-op.
- **#3** solo se manifiesta en el camino `pendingStart` (usuario pulsa FIN y luego INICIO mientras el cierre drena
  los 4 min de `StopDrainPolicy.TIMEOUT_MS`). En arranques normales el servicio se recrea y no aplica. Aun así,
  es un mecanismo de seguridad (rescate) que queda muerto y no se notó porque no hay test de ciclo de vida.
- **#4/#5** juntos explican el síntoma "despierta pero no hay fix": ni el guardián ni el FCM (con servicio vivo)
  ejecutan una adquisición activa garantizada dentro de su ventana. `MovementRescueController` haría ese burst,
  pero (a) solo corre cada 15 s si su scope vive (#3) y (b) con GPS_LOST+MOVEMENT puede tardar 2 ticks
  (`MovementRescuePolicy.kt:94-104`).
- **#6** es una regresión real introducida por el anti-livelock R1: el KDoc de `FixFilter` asume que la ventana
  sobrevive a la recuperación, pero `recentFixes` es un `ArrayDeque` en memoria del servicio.
- **#10** es una decisión de producto (histerésis 100→80 %) que en la práctica equivale a apagar la captura
  durante horas; merece revisión con datos de campo de flotas que usan `stop_capture`.

---

## Lo que está bien resuelto (para no "arreglar" lo que ya funciona)

- **Relojes**: uso consistente de `elapsedRealtime` para staleness, polling, keeper y duración de jornada
  (`FixTime.kt`, `TrackingSessionController.kt`, `LocationEnginePolicy.kt`); `FixTime.dtSeconds` prefiere
  monotónico y cae a wall solo con ambos válidos. El diseño "wall para mostrar, monótono para decidir" es correcto.
- **Filtro**: `FixFilter` es puro, testeado (`FixFilterTest`, `FixRobustnessTest`) y cubre NaN/infinitos
  (`invalidLocationReason`), techo de accuracy, primer fix, re-entrega cacheada y re-adquisición tras 15 min.
- **Outbox**: propietario único de posiciones (HTTP), `DispatchLock` compartido con MQTT, ACK de negocio,
  cuarentena transaccional (`PositionDao.moveToDeadLetter`), retención 100 k/7 d sin pérdida silenciosa,
  secuencia durable (`nextSequence` con `raiseSequenceTo`).
- **Conectividad MQTT**: `connectLocked` sincronizado + `ReconnectGate` + `StaleConnectingPolicy` cierran
  carreras de conexiones múltiples; LWT para `network=lost`.
- **Foreground**: `ForegroundClaimPolicy` resuelve el caso real de `ForegroundServiceDidNotStartInTimeException`;
  `reassertForeground` en screen_on/fix es idempotente y barato.
- **Recovery honesto**: `RecoveryJournal` distingue PENDING/BLOCKED/OK y el FGS no declara éxito sin evidencia;
  `RecoveryOutcome.afterAttempt` preserva "blocked".

---

## (b) Refactor recomendado en orden

1. **`TrackingService.kt` (1650 líneas)** — partir en 3 piezas con contrato explícito, sin cambiar semántica:
   - `FixEnqueuePipeline` (filtro + regla OR + construcción de payload + insert + refs de jornada). Hoy
     `onNewLocation` (`:1071-1526`) mezcla captura, salud, auto-jornada, keeper y persistencia en ~450 líneas.
   - `JourneyLifecycleCoordinator` (`startTracking`/`stopTracking`/`finishStopping`/`pendingStart`, `:583-725`,
     `:1538-1598`): hoy el ciclo de vida depende de campos compartidos (`started`, `stopping`, `pendingStart`,
     `serviceScope`) sin un único dueño. Este refactor es el que elimina #3 y #15.
   - `RecoveryEntryPoints` (un solo camino idempotente para ACTION_START, FCM, Boot, keeper, worker) con el gate
     de consentimiento/permissions/readiness en un único lugar → cierra #1/#4/#8.
2. **`MainActivity.kt` (1653 líneas)** — extraer `JourneyControlViewModel` (arranque/fin, gates de readiness,
   diálogos) y dejar la Activity como render. Sin esto, cada cambio de gate (p. ej. #8) se duplica.
3. **`MqttManager.kt` (796 líneas)** — separar conexión (`connectLocked`/`ReconnectGate`), ACK
   (`ackFutures`/`handleAck`) y dispatch de controles (`dispatchLoop`) en 3 clases testeables.
4. **Sensores** — `MotionSensor`: extraer la máquina de pausa/re-registro a una política pura testeable (cierra #2);
   borrar `AdaptiveDistancePolicy.nextMode` y sus tests (cierra #14).
5. **`SessionKeeper`** — introducir `SessionKeeperScheduler` con fallback exacta→inexacta y contador propio
   (cierra #9); `RescueWindow` reutilizable desde keeper/FCM/boot (cierra #4).
6. **`AppConfig.kt` (956 líneas)** — separar "config de captura", "estado de jornada" y "telemetría/health"
   (tres fachadas). Hoy cualquier test/razonamiento tiene que leer 900 líneas.
7. **Errores silenciados** — política única: todo `catch` del plano de tracking loguea con tag + contador
   (`HealthSnapshot`), y los `runCatching` de flush/DB devuelven outcome distinguible (cierra #11).
8. **Geometría duplicada** — unificar `distanceMeters`/haversine (hay 3 copias: `TrackingService.kt:864`,
   `FixFilter.kt:129`, `SpeedEstimator.kt:23`) en `core/Geo.kt`.

## (c) Tests que faltan (los críticos de esta auditoría)

1. **Ciclo pausa/re-registro del acelerómetro** (hoy imposible: `MotionSensor` es singleton Android).
   Refactor mínimo para inyectar `SensorManager`/reloj y test JVM: `STATIONARY 5 min → maybePause → fix aceptado
   → reRegisterOnFix debe registrar`. (Hallazgo #2)
2. **Ciclo de vida de `MovementRescueController` en `pendingStart`**: dos `startTracking` con scope distinto y
   verificar que el tick/rescate usa el scope vivo. (Hallazgo #3)
3. **Consentimiento FCM**: probe válido con `trackingEnabled=false` NO debe arrancar ni mutar config;
   caso `journeyStopRequested=true`. (Hallazgo #1)
4. **Sesión recuperada + primer fix malo**: `startedTrackingAt` viejo no debe forzar `Accept(lowQuality)` en el
   primer rechazo (regresión R1). (Hallazgo #6)
5. **`observedAt` futuro**: fix con `time = now + 10 min` → clamps/rechazo y contador. (Hallazgo #7)
6. **AutoJourney**: (a) sensor-only 90 s no debe dar START; (b) el flag de apertura se re-arma tras cerrar jornada;
   (c) `applyRemote(autoJourney=true)` no arranca sin gates. (Hallazgo #8)
7. **`SessionKeeper.schedule` con excepción en exacta** → debe caer a inexacta (mock de `AlarmManager`). (Hallazgo #9)
8. **`stop_capture` con backlog 80–100 %**: la captura debe reanudar en un tiempo acotado (hoy horas). (Hallazgo #10)
9. **Errores de DB en flush**: `allDue` lanza → el outcome debe distinguir `dbError` y loguear. (Hallazgo #11)
10. **Permiso FINE revocado en caliente**: transición a alerta/suspensión, no solo string de estado. (Hallazgo #12)
11. **Integración de orden**: N fixes en ráfaga → `sequence` en orden de `fixWallMs` y `journeyId` consistente
    al cierre. (Hallazgo #15)
12. **`FixFilter` con `elapsedRealtimeNanos == 0`** (fix sin reloj monotónico): que un fix cacheado viejo no pase
    (hoy el chequeo de staleness se salta por completo).

---

## Anexo: síntoma de campo → hallazgo

| Síntoma reportado | Causas identificadas en código |
|---|---|
| Huecos de 10–45 min con proceso congelado | #4 (keeper sin ventana de CPU), #3 (rescate muerto tras reinicio interno), #9 (cadena del guardián puede morir sin fallback), #2 (sin evidencia de sensor) |
| Despertar FCM que no logra fix | #5 (nudge sin one-shot), #4 (ventana solo en FCM y sin adquisición activa), #3 (burst de rescate inactivo) |
| "Solo marca puntos donde estuvo sin trazar ruta" | cobertura inherente (pocos fixes tras freeze) + #5/#3 (no hay re-adquisición agresiva) + #6 (primer fix malo siembra drift) + #15 (orden/continuidad) |


---

## Estado de correcciones (2026-09-19, sin publicar)

**CORREGIDOS en esta ronda (con tests, 708/0 móvil + lint):**

1. **#1 Consentimiento FCM** — `trackingEnabled=false` → ACK `RECOVERY_BLOCKED /
   DISABLED_BY_USER`, sin mutar estado (`FcmRecoveryMessagingService`).
2. **#2 Acelerómetro** — la pausa por quietud ahora limpia `registered` y deja
   `state=UNKNOWN`; `reRegisterOnFix()` vuelve a funcionar.
3. **#3 Scope del rescate** — `MovementRescueController` recibe
   `scopeProvider: () -> CoroutineScope`; el rescate usa el scope vivo tras
   `pendingStart`. Test actualizado.
4. **#4/#5 Adquisición al despertar** — `RescueWindow` (90 s FCM / 45 s
   guardián, ambas acotadas) + `onManualRefresh` ahora llama
   `requestOneShotFix()` (antes solo nudge pasivo). El guardián también nudgea.
5. **#6 Anti-livelock** — ventana medida desde `runStartedAtMs` (esta corrida),
   no desde el inicio original de la jornada.
6. **#7 Reloj futuro** — `FixFilter` rechaza `clock_future` (>2 min) con test.
7. **#8 Auto-jornada** — START exige evidencia cinemática (velocidad), no sensor
   solo; flags re-armados al cerrar jornada.
8. **#9 Guardián** — si la alarma exacta lanza, cae a la inexacta (la cadena no
   muere en silencio).
9. **#10 stop_capture** — reanuda con margen de 500 (antes 80 % = horas de
   captura apagada); `BufferPausePolicy` + test.
10. **#11 Errores de DB silenciados** — breadcrumbs en flush (outbox) y dispatch
    MQTT.
11. **Ángulo (R8, nuevo)** — `RouteSamplePolicy` (Δ≥15°, pata ≥8 m, v≥1.5 m/s,
    rate-limit 10 s) + hook en el pipeline: giro real pide fix extra inmediato.

**PENDIENTES (documentados, no bloquean este lote):** #12 alerta FINE revocado,
#13 hooks estáticos en `onDestroy`, #14 unificar políticas de movimiento
(borrar `AdaptiveDistancePolicy.nextMode` y sus tests), #15 actor de encolado;
refactors 2–8 del informe; tests 1–12 faltantes de la sección (c).

**Decisión pendiente del dueño:** cadencia base según
`ARCH_RESEARCH_SAMPLING.md` (10 s OR 25 m OR 15° en movimiento; hoy 5 s + ángulo).


---

## Producción (2026-09-19, tarde)

- **Cadencia recomendada aplicada** (`ARCH_RESEARCH_SAMPLING.md`):
  movimiento **10 s O 24 m O 15°** (regla OR ya existente, alineada), FLP con
  minUpdateDistance **0** (filtro en código), heartbeat de quietud **120 s**,
  perfil batería <15 %: **30 s / 300 s**, clamps remotos 10–600 s.
- **Publicado 1.1.15 (vc125)** — "Mejoras de captura y estabilidad", OTA
  verificada (sha256 `d540563a…` = APK servido; `/api/mobile/v1/ota` sirve
  1.1.15). `rollout.json` en 100 % (el mecanismo gradual queda listo para el
  próximo release).
- Gates: móvil **709/0** + lint · server 862/0 · dashboard OK.
- **Pendiente operativo (bloquea la flota)**: kevin tiene "Instalar apps
  desconocidas" DENEGADO → habilitarlo una vez por teléfono (Ajustes → Apps →
  DMujeres → Instalar apps desconocidas) o instalar el APK manualmente.
