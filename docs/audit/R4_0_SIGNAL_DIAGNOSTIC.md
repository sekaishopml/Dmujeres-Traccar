# R4_0_SIGNAL_DIAGNOSTIC.md — Diagnóstico de episodios "SIN SEÑAL" (R4-0)

> Fase R4-0 · solo lectura/diagnóstico · sin cambios de código (CODE FREEZE).
> Fuente: PostgreSQL `dmj-db` (tc_events, tc_positions, tc_mobile_messages,
> tc_device_health, tc_recovery_event, tc_devices) · 2026-09-17 23:09 local (−05).
> No se inventa ninguna causa: cada afirmación tiene consulta/evidencia.

## 1) Resumen

Miguel (41) y Joseph (39) presentan **episodios repetidos de "SIN SEÑAL" en el
dashboard que NO son pérdida de GPS ni muerte de proceso**: son **pérdidas del
canal de presencia/transporte (MQTT heartbeat + entrega), con CAPTURA GPS
continua y OUTBOX funcionando** (cero pérdida: lo capturado se entregó al
reconectar). Clasificación principal: **TRANSPORT_LOSS / NETWORK_LOSS con
OFFLINE_BUFFERING PASS**. La causa última del corte de transporte (ruta vs
sesión MQTT/HTTP vs suspensión OEM entre heartbeats) **no es distinguible con
la telemetría actual** → parte del episodio queda **UNKNOWN** con datos
faltantes concretos listados en §20.

## 2) Miguel (41, INFINIX X6876, Android 16, app 1.1.5)

| Campo | Valor |
|---|---|
| sessionId / bootId / journeyId | `1ac8a6d38c674b29` / `804eec8e35ac42e3` / `1789499617449` |
| Batería / batteryOptimization | 83 % / **exempt: true** |
| OEM | `infinix` · **oemConfirmed: true** · readiness: `ready` |
| Pantalla (snapshot) | `screenOn: false`, `idleMs` 928 424 |
| Red reportada | `wifi`, `netCause: ok` |
| MQTT (snapshot) | `conectando...`, `reconnects: 0` |
| Outbox | pending 0 (snapshot) |
| Episodios 24 h | **15**, total **2.5 h** offline, p50 5.1 min, máx **35 min** |

## 3) Joseph (39, HONOR LGN-LX3, Android 15, app 1.1.5)

| Campo | Valor |
|---|---|
| sessionId / bootId / journeyId | `aabac55e04654799` / `e0c2c9adf8174788` / `1789164504889` |
| Batería / batteryOptimization | 58 % / **exempt: true** |
| OEM | `honor` · **oemConfirmed: false** · readiness: `not_ready` · recovery `ok-pending` |
| crashes24h | **3** (ANR 0) |
| Pantalla (snapshot) | `screenOn: false`, `idleMs` 231 112 |
| Red reportada | `wifi`, `netCause: ok` |
| MQTT (snapshot) | `desconectado del servidor`, `reconnects: 0` |
| Episodios 24 h | **24**, total **5.9 h** offline, p50 5.1 min, máx **80 min** |

## 4) Episodios encontrados (offline→recovered, 24 h)

**Joseph (39):** 11:55→12:01 (5.4'), **12:13→13:34 (80')**, 13:44→14:03 (18.6'),
14:13→14:18, 14:28→14:34 (5'), 14:44→15:04 (20'), **15:14→16:05 (50')**,
16:15→16:21 (5'), 16:35→16:36 (0.8'), 16:46→16:52 (5'), 17:11→17:16 (5'),
**17:27→18:18 (50')**, 18:28→18:34 (5'), **18:44→19:10 (25.7')**, 19:54→19:59 (5'),
20:18→20:31 (13'), 20:42→20:48 (5.1'), 21:11→21:17, 21:27→21:31, 21:47→21:52,
22:02→22:08, 22:18→22:24, 22:34→22:39, **22:49→23:10 (20.5')**.

**Miguel (41):** 18:13→18:18, 18:28→18:34 (5'), **18:44→19:19 (35')**,
19:29→19:50 (20.3'), 20:00→20:05, 20:15→20:36 (20'), 20:46→20:51 (5.1'),
21:01→21:18 (16.7'), 21:29→21:35, 21:45→21:50, 22:00→22:06, 22:16→22:21,
22:31→22:37, 22:47→22:52, 23:02→23:08 (5.1').

**Patrón dominante:** p50 = **5.0–5.1 min** (un intervalo de heartbeat perdido
→ el servidor marca `TIMEOUT_OFFLINE` y el siguiente heartbeat lo recupera con
`reason=RECONNECT`). Los episodios **largos** (20–80 min) son los que incluyen
caída real de entrega con buffering.

## 5) Duración

| Métrica | Joseph | Miguel |
|---|---|---|
| Episodios / tiempo offline | 24 / 5.9 h | 15 / 2.5 h |
| p50 / máx | 5.1 min / 80 min | 5.1 min / 35 min |
| Episodios ≥20 min | 7 | 4 |

## 6) Correlación GPS

**GPS = OK y CAPTURANDO durante los silencios.** Evidencia (episodio Joseph
15:14→16:05): fixes con fixtime 15:20:01, 15:34:54, 15:48:01–15:50:01, con
`qualityClass=EXCELLENT/GOOD` y `gnssUsed=12`; (Miguel 18:44→19:19): fix
18:49:35 (`DEGRADED`) seguido de 19:19:30 `GOOD` fresco. **No es GPS_LOSS.**
Cadencia reducida durante estacionamiento por diseño (energy profile).

## 7) Correlación NETWORK

Interfaz reportada `wifi`/`mobile` y `netCause: ok` en las health locales, pero
**cero mensajes al servidor** durante el hueco (evidencia §11). Con la
telemetría disponible no se puede distinguir "interfaz arriba sin ruta" de
"sesiones caídas con red operativa" → **NETWORK_LOSS probable en episodios
largos; causa de capa = parcialmente UNKNOWN** (datos faltantes §20).

## 8) Correlación MQTT

- Presencia servidor: `mobilePresenceOffline (reason=TIMEOUT_OFFLINE)` →
  `mobilePresenceRecovered (reason=RECONNECT)`. Joseph: 24 offline / 72
  recovered / 72 suspect; Miguel: 15/28/27.
- Snapshot actual: Joseph `desconectado del servidor`; Miguel `conectando...`;
  ambos `reconnects: 0` → **la métrica de reconexiones no refleja las caídas
  (métrica muerta ya detectada en R3, agente D)**.
- EMQX (10 h): **sin warnings de auth/caída para joseph/miguel**.

## 9) Correlación HTTP

`tc_mobile_messages` (created = llegada real): Joseph **15:04→16:05 = 0
mensajes**; 16:05:00–16:05:31 = **23 mensajes** (posiciones 52818–52837 +
heartbeats + presencia). El HTTP tampoco entregó durante el hueco → el silencio
no es solo-presencia: **el transporte completo estuvo caído 60 min**.

## 10) Outbox

- Joseph: `outbox` 0 → 2 → **21** (health 16:05:00) → drena a 0 tras el lote.
- Miguel: `outbox` 0 → 2 → 0 (health 18:49/19:19/19:20).
- **OUTBOX PASS**: nada se perdió; lo capturado se entregó al reconectar
  (delta `servertime−fixtime` 899–2700 s en los fixes buffered).

## 11) Server

Recepción **por lotes** tras reconexión (23 msgs/31 s en Joseph). `accepted` al
primer intento, sin duplicados ni errores. Latencia de ingesta normal (2–7 s)
cuando hay entrega. `mobileStalled` (Joseph 69/día, Miguel 75/día) es
consecuencia del silencio, no causa.

## 12) Presence

El dashboard "SIN SEÑAL" corresponde a la presencia **offline por timeout**
(servidor). Es un reflejo correcto de que no llegaban heartbeats; **no es
DASHBOARD_DELAY**.

## 13) Recovery

- Joseph: 38 `RECOVERY_SENT`/`RECOVERY_ATTEMPT` (FCM), 35 `RECOVERY_TIMEOUT`,
  **3 `RECOVERY_SUCCESS`, 21 `RECOVERY_SERVER_ACK`, 17 `RECOVERY_ACK_REJECTED`,
  19 `GPS_CONFIRMED`** (probes que sí despertaron y confirmaron tracking).
  Razones: `TIMEOUT_NO_DELIVERY` (FCM no llegó), `TIMEOUT_NO_SERVER_ACK`,
  `TIMEOUT_NO_FGS` — **contradictorio** con `STARTED already-running`.
- Miguel: hasta 17:54 **56 `SKIPPED_NO_TOKEN`** (sin token FCM); desde 18:18,
  19 sent / 18 timeout.
- **RECOVERY PARCIAL**: los probes funcionan cuando llegan (ACK + GPS_CONFIRMED),
  pero la tasa de éxito es baja y hay inconsistencias de verificación.

## 14) OEM

- Miguel: `oemConfirmed: true`, `readiness: ready`.
- Joseph: `oemConfirmed: false`, `readiness: not_ready` (HONOR) + **3 crashes
  en 24 h**. Guía OEM pendiente de completar en el teléfono.

## 15) Screen

`screenOn: false` en ambos snapshots (pantalla apagada durante los episodios,
coherente con reposo). **La columna `screenon` de `tc_device_health` está vacía
para estas filas** → dato faltante de observabilidad (§20).

## 16) Battery

`exempt: true` en ambos (sin optimización de batería), 58 % (Joseph) / 83 %
(Miguel). La restricción de batería **no** está activa → no es la causa directa
demostrable de estos episodios.

## 17) Thermal

**Sin datos**: no existe columna/campo de thermal en la telemetría actual →
UNKNOWN (no se atribuye).

## 18) Clasificación (solo con evidencia)

| Episodio | Clasificación | Evidencia |
|---|---|---|
| Joseph/ Miguel, transitorios ~5 min (p50) | **TRANSPORT_LOSS (heartbeat/presencia)** con captura y outbox OK | timeout de presencia + recuperación por RECONNECT; posiciones fluyen entre episodios |
| Joseph 15:14→16:05 y 17:27→18:18 (50'); Miguel 18:44→19:19 (35') | **NETWORK_LOSS / TRANSPORT_LOSS + OFFLINE_BUFFERING PASS** | 0 mensajes al servidor en el hueco; outbox 0→21→0; lote aceptado sin pérdida; GPS EXCELLENT en captura |
| Causa de capa (ruta vs sesión) | **UNKNOWN parcial** | sin contadores de intentos fallidos ni estado de ruta (§20) |
| `TIMEOUT_NO_FGS` vs `already-running` | **Inconsistencia de verificación (observabilidad)** | evidencia en tc_recovery_event |
| Joseph crashes24h=3 | Riesgo separado a vigilar (no es la causa de estos episodios) | lastDiagnostics |

**Descartados con evidencia:** GPS_LOSS (fixes EXCELLENT durante silencios),
PROCESS_DEATH (heartbeats locales escritos durante el hueco y subidos después),
DASHBOARD_DELAY (0 mensajes servidor confirma silencio real),
OUTBOX_BACKLOG como pérdida (drenó completo).

## 19) Evidencia (consultas reproducibles)

```sql
-- Episodios: pares offline→recovered (tc_events), duración
SELECT deviceid, eventtime, type, attributes::text FROM tc_events
 WHERE deviceid IN (39,41) AND type IN ('mobilePresenceOffline','mobilePresenceRecovered')
   AND eventtime > now() - interval '24 hours' ORDER BY deviceid, eventtime;

-- Captura durante el hueco Joseph 15:14-16:05 (fixtime vs servertime)
SELECT fixtime, servertime, extract(epoch from (servertime-fixtime))::int delta,
       attributes::jsonb->>'qualityClass', attributes::jsonb->>'gnssUsed'
  FROM tc_positions WHERE deviceid=39
   AND fixtime BETWEEN '2026-09-17 15:10' AND '2026-09-17 16:15' ORDER BY fixtime;

-- Nada llegó durante el hueco (created = recepción real); lote a las 16:05
SELECT to_char(created,'HH24:MI:SS'), positionid, status FROM tc_mobile_messages
 WHERE deviceid=39 AND created BETWEEN '2026-09-17 15:05' AND '2026-09-17 16:10' ORDER BY created;

-- Outbox y salud local subidos en el lote (15:19/15:34 escritos a esa hora)
SELECT id, ts, eventtype, healthstate, fgs, motion, network, outbox
  FROM tc_device_health WHERE deviceid=39
   AND ts BETWEEN '2026-09-17 15:00' AND '2026-09-17 16:10' ORDER BY id;

-- Recovery ladder (probes, timeouts, éxito con evidencia server-side)
SELECT ts, eventtype, reason, status, source FROM tc_recovery_event
 WHERE deviceid=39 AND ts > now() - interval '24 hours' ORDER BY ts DESC LIMIT 20;
```

## 20) Datos faltantes (observabilidad a mejorar — NO bloquea R4, sí limita causa exacta)

1. No hay contador de **intentos de conexión fallidos** ni causa de capa
   (ruta/interfaz/DNS) durante el silencio; `reconnects: 0` no refleja caídas.
2. `tc_device_health.screenon` vacío en las filas de estos episodios.
3. Sin telemetría **thermal**.
4. Sin marca de **recepción** en `tc_device_health` (no se distingue vivo vs
   backfill por fila, salvo por `tc_mobile_messages`).
5. `TIMEOUT_NO_FGS` vs `STARTED already-running`: semántica de verificación a
   revisar.

## 21) Conclusión

**SIN SEÑAL ES:**

**TRANSPORT_LOSS / NETWORK_LOSS del canal dispositivo→servidor (MQTT presencia +
HTTP entrega), con CAPTURA GPS funcionando y OUTBOX preservando todo (sin
pérdida); en los episodios cortos (~5 min) es una pérdida transitoria de
heartbeat; la causa de capa exacta del corte es UNKNOWN con la telemetría
actual (datos faltantes §20).**

**Decisión R4 (según regla):** los episodios son NETWORK_LOSS/TRANSPORT_LOSS
con Outbox OK y replay correcto → **CONTINUAR R4**, añadiendo a la campaña:
(1) completar guía OEM en Joseph (oemConfirmed=false, 3 crashes), (2) registrar
intentos de conexión/estado de ruta durante episodios (mejora de
observabilidad post-R4, no durante CODE FREEZE), (3) vigilar la tasa real de
recovery FCM.

---

# R4-0.5 TRANSPORT ANALYSIS (transporte profundo)

> Añadido 2026-09-17 ~23:40 (−05). Solo lectura; CODE FREEZE respetado.

## A. Constantes reales del sistema (código, file:line)

| Componente | Valor real | Fuente |
|---|---|---|
| Heartbeat app (intento de presencia) | watchdog cada **60 s** si el fix está rancio | `tracking/TrackingWatchdog.kt:153-155` |
| Cadencia esperada por el servidor | `heartbeatSeconds=180` (3 min) | `server Keys.java:110` |
| SUSPECT servidor | `suspectSeconds=300` (5 min) | `server Keys.java:118` |
| OFFLINE servidor | `offlineSeconds=600` (10 min) | `server Keys.java:121` |
| GPS stale | `gpsStaleSeconds=600` | `server Keys.java` |
| MQTT grace | `mqttGraceSeconds=180` | `server Keys.java` |
| Verificación FCM app | 6 s (+ recheck 30 s) | `recovery/FcmRecoveryMessagingService.kt` |
| Cadena de etapas FCM | RECEIVED→STARTED→FGS_ACTIVE→TRACKING_ACTIVE→GPS_CONFIRMED (+SERVER_ACK solo server) | `server FcmRecoveryPolicy.java:76-105` |

**Lectura:** el teléfono solo emite presencia cuando NO tiene fix fresco, por lo que
en reposo su silencio natural (fix cada ~15 min) puede superar suspect(5 min) y
offline(10 min) si la entrega falla; los ciclos observados (p50 5.1 min) encajan
con: silencio → SUSPECT(5') → OFFLINE(10') → siguiente despertar (~5' después).

## B. Máquina de estados observada (real, por attempt)

```
ONLINE
  │ (silencio de entrega: ni posiciones ni presencia llegan al server)
  ▼
TIMEOUT_SUSPECT (5 min)  ── presencia SUSPECT
  ▼
TIMEOUT_OFFLINE (10 min) ── presencia OFFLINE   ← dashboard "SIN SEÑAL"
  ▼
FCM probe (stalled:15/30min) → RECOVERY_ATTEMPT/SENT
  ▼
[thaw breve] RECEIVED → STARTED{already-running} → [a veces] posición (SERVER_ACK)
  │            └─ el proceso se congela de nuevo: FGS_ACTIVE nunca llega
  ▼
RECOVERY_TIMEOUT{TIMEOUT_NO_FGS} (server, 5 min de evidenceTimeout)
  ▼
siguiente thaw (heartbeat/probe) → RECONNECT → ONLINE (lote drena Outbox)
```

Evidencia del thaw/flush (Joseph, attempt `fcm-3d4d67b1`):
`22:39:29 ATTEMPT/SENT → 22:39:30 RECEIVED+STARTED(already-running) →
22:39:36 RECOVERY_SERVER_ACK (position-after-probe, fix 22:39:31 delta 4 s) →
22:39:46 fix capturado NO entregado (delta 1841 s) → 22:54:57 fix capturado →
23:10:27 lote entregado → ONLINE`.

## C. Tabla de correlación (R4-0.5)

| DEVICE | EPISODE | DURATION | NETWORK | MQTT | HTTP | HEARTBEAT | OUTBOX | PROCESS | FGS | RECOVERY | OEM | ROOT_CAUSE | CONFIDENCE |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Joseph (39) | 12:13→13:34 | 80 min | wifi/netCause ok (sin evidencia de caída de ruta) | presencia OFFLINE (TIMEOUT_OFFLINE); EMQX sin drops de auth | 0 mensajes al server en el hueco; lote al reconectar | silencio >10 min pese a intento cada 60 s ⇒ no salió del dispositivo | 0→2→21→0 (drenó en 31 s) | vivo intermitente; **crashes24h=3 = terminaciones NO limpias (kill OEM/reboot/crash)** | `fgs=true` en health locales escritos en el hueco | probes FCM: RECEIVED/STARTED/GPS_CONFIRMED a veces; TIMEOUT_NO_FGS en otras | HONOR, `oemConfirmed=false`, readiness not_ready | **Freeze/suspensión del proceso por OEM (thaw solo con FCM)** | **HIGH** (patrón + contador de terminaciones) |
| Joseph (39) | 14:13→14:18 … p50 5.1 min (24 eps) | 5 min | ídem | ídem (SUSPECT/OFFLINE↔RECONNECT) | silencio por debajo del lote | ídem | 0→2→0 | vivo | fgs=true | — | ídem | **OEM freeze corto** (pierde 1–2 ventanas) | HIGH |
| Joseph (39) | 22:39 attempt | — | wifi | — | ACK posición a 7 s del probe | — | flush 1 fix | vivo y funcional (recibió+envió) | **FGS_ACTIVE nunca ACKeado** ⇒ TIMEOUT_NO_FGS **no prueba ausencia de FGS** | ídem | **OBSERVABILITY_BUG + RACE** (ver §D) | HIGH |
| Miguel (41) | 18:44→19:19 | 35 min | wifi/netCause ok | presencia OFFLINE↔RECONNECT | 0 mensajes en el hueco; fix 18:49:35 entregado 19:19:30 | silencio del dispositivo | 0→2→0 | vivo (health local en el hueco) | fgs=true | SKIPPED_NO_TOKEN hasta 17:54; luego timeouts | INFINIX, `oemConfirmed=true`, un clean-stop 24h=1 | **Suspensión OEM/Doze (thaw periódico)** | MEDIUM-HIGH |
| Miguel (41) | 15 eps p50 5.1 min | 5 min | ídem | ídem | ídem | ídem | 0→2→0 | vivo | fgs=true | — | ídem | OEM/Doze corto | MEDIUM |

## D. `reconnects=0` — causa exacta (sección 3 del encargo)

**CONTADOR NUNCA INCREMENTADO.** Verificado por grep exhaustivo:
`reconnects24h` se **lee** en `DiagnosticsActivity` y `DiagnosticsCollector`, pero
**no existe ningún escritor** (`reconnects24h =`, `++`, `inc…` → 0 resultados).
Clasificación: **OBSERVABILITY_BUG (métrica desconectada, jamás incrementada)**.
No es reset/evento en memoria: simplemente no hay quien lo escriba.

## E. TIMEOUT_NO_FGS vs already-running — clasificación (sección 4)

- El timeout registrado es de **fuente SERVER** (evidenceTimeout), no el local.
- El cliente **sí estaba vivo**: RECEIVED + STARTED + una **posición real 7 s
  después** (SERVER_ACK `position-after-probe`).
- `FGS_ACTIVE` requiere un ACK del cliente (`RECOVERY_FGS_ACTIVE`) que **nunca
  llegó**; el handler local de 6 s no pudo completarlo (proceso congelado tras
  el flush) o su ACK se perdió — la telemetría no distingue ambos.
- **Clasificación: `OBSERVABILITY_BUG` (etiqueta no concluyente) +
  `RACE_CONDITION` posible. `REAL_FGS_ABSENCE` NO demostrada;
  `FALSE_FGS_ABSENCE` probable en este attempt (app funcionalmente viva).**

## F. Heartbeat vs timeout (sección 6)

| Tramo | Valor |
|---|---|
| Intervalo app (intento) | 60 s (watchdog) condicionado a fix rancio |
| Intervalo app real en reposo | ~15 min (coincide con cadencia stationary de captura) |
| Suspicion server | 5 min sin nada |
| Offline server | 10 min sin nada |
| Tiempo hasta reconnect observado | p50 ≈ 5.1 min tras OFFLINE |
| Probes FCM | a los 15 y 30 min de stalled |

Conclusión: en reposo el margen entre el silencio natural (~15 min) y
suspect(5')/offline(10') es **negativo** si falla una sola entrega ⇒ los ciclos
"SIN SEÑAL" son de esperar cuando el proceso se congela entre despertares.

## G. Network / Outbox (secciones 7-8)

- **NETWORK_LOSS no evidenciado** en estos episodios (netCause ok, wifi);
  tampoco evidencia server/broker (0 auth-fail, 0 drops EMQX) → **E SERVER
  descartado**; `TRANSPORT_SOCKET_LOSS` vs `APP freeze` no distinguible sin
  contadores de intentos (gap), pero el patrón thaw/flush + terminaciones no
  limpias favorece **freeze de proceso**.
- Outbox: crecimiento ~2 posiciones/30 min (cadencia stationary), pico 21,
  drenaje **23 msgs/31 s (~45 msg/min)**, duración <1 min. **Integridad 20/20,
  0 duplicados, fixTime preservado** (evidencia R4-6).

## H. Observabilidad faltante (sección 9 — solo documentar)

mqttConnectAttempts / mqttConnectSuccess / mqttConnectFailure /
mqttDisconnectCount / mqttDisconnectReason / mqttReconnectAttempts /
mqttReconnectSuccess / mqttReconnectFailure; heartbeatSent/Received/Timeout;
httpAttempt/Success/Failure; screenOn/off por heartbeat (`screenon` vacío);
thermal; marca de recepción por fila de health; writer real de `reconnects24h`.

## I. Decisión (secciones 10-11)

**G COMBINED**:
- **C — ANDROID BACKGROUND BEHAVIOR** (suspensión en reposo; batteryOptimization
  `exempt:true` ⇒ no es el Doze clásico)
- **D — OEM LIMITATION** (ciclos de freeze con thaw solo por FCM; Joseph/HONOR
  `oemConfirmed=false` + 3 terminaciones no limpias; Miguel/Infinix 1)
- **F — OBSERVABILITY GAP** (`reconnects` muerto; `TIMEOUT_NO_FGS` no prueba
  ausencia de FGS; sin contadores de intentos)

**Descartados:** A TRANSPORT BUG (no hay ruta de código que pierda el canal; el
canal se restablece y drena), B NETWORK_LOSS (sin evidencia de caída de red),
E SERVER (broker/ingesta limpios), H UNKNOWN (hay patrón consistente).

**Correlación de crashes con episodios:** `crashes24h` cuenta **terminaciones no
limpias** (crash/kill OEM/reboot) y **no expone timestamps** ⇒
`NO_CORRELATION_FOUND` (por falta de marca temporal), aunque el mecanismo
(despertares breves + terminaciones no limpias) es coherente con los ciclos.

---

R4-0.5 STATUS:
DIAGNOSED

ROOT CAUSE:
G COMBINED — ciclos de suspensión/freeze del proceso por OEM (HONOR especialmente, oemConfirmed=false; también INFINIX) con thaw intermitente por FCM + OBSERVABILITY GAP (reconnects nunca incrementado; TIMEOUT_NO_FGS no prueba ausencia de FGS). Captura GPS y Outbox íntegros (0 pérdidas, 0 duplicados). Sin bug de transporte demostrado; NETWORK_LOSS y SERVER descartados en estos episodios.

NEXT:
R4-1 BASELINE (con acción operativa paralela: completar guía OEM en Joseph/HONOR y revisar Infinix; los gaps de observabilidad se resuelven post-freeze con evidencia)
