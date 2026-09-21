# AUDITORÍA DE COBERTURA DE RUTA (pipeline completo INICIAR→DETENER→replay)

Fecha: 2026-09-19 · Alcance: mobile 1.1.17 (`mobile/app/src/main/java/com/dmujeres/traccar`) + server forkeado (`server/src/main/java/org/traccar`) + replay (`dashboard/src`, `infrastructure/matchservice`).
Objetivo único: trazo (replay) que cubra **>90 % del tiempo en movimiento** desde INICIAR hasta DETENER.
READ-ONLY. No re-propone lo ya hecho (regla OR 10 s/24 m/15°, heartbeat 60 s, 1.1.17 arranque de movimiento, FCM 6 min, watchdog, outbox con ACK, map-matching).

Línea base HOY (tc_positions, cobertura por huecos ≤15 s con speed>3 m/s): joseph 1 %, miguel 2 %, david 4 %, kevin 24 %, santiago 34 %; dominan huecos >60 s (9–30/día).

---

## 1. TOP de hallazgos priorizados

| # | Sev | Hallazgo | Evidencia | Impacto en cobertura | Fix propuesto | Esfuerzo |
|---|-----|----------|-----------|----------------------|---------------|----------|
| **H1** | **P0** | **`stale` rechaza fixes REALES recuperados en lote tras congelado OEM.** El FLP acumula fixes mientras el proceso está congelado y los entrega en lote al despertar (`LocationEngine.kt:139-149` procesa TODOS los del `LocationResult`); `FixFilter.evaluate` descarta por edad monotónica todo fix con staleness >120 s (`FixFilter.kt:39` + `FixFilter.kt:264-270`), ANTES del anti-livelock y sin excepción para re-adquisición. Un congelado de ≥2 min ⇒ todo el tramo recuperado se tira (uno o varios `incRejected("stale")` por fix). Esto reproduce exactamente el síntoma dominante: huecos >60 s, 9–30/día. | FixFilter.kt:39,264-270; LocationEngine.kt:139-157; TrackingService.kt:1080-1085 | **Alto (el mayor pendiente)**: cada evento destruye minutos de ruta ya medida por el GPS del sistema | No descartar fixes batched por edad: aceptarlos `lowQuality=true` + `gapMs` cuando llegan agrupados tras silencio (orden correcto garantizado por `orderFixes`, LocationEngine.kt:151-157). Conservar `stale_relay` (dedupe por coords) y un techo defensivo (p.ej. >30 min ⇒ descartar). Añadir contador `stale_accepted` para medir el cambio | **M** |
| **H2** | **P1** | **`degraded` difiere fixes válidos EN MARCHA cuando la ventana (6 evaluados ≈ 1 min) contiene un solo bueno ≤20 m** (`FixFilter.kt:306-308`; ventana honesta con rechazados, `TrackingService.kt:939-951`). Con accuracy oscilante (borde de copa de árboles, ZTE 120–200 m documentado en docs/audit/R6_ROUTE_DENSITY.md), cada pata 81–500 m se descarta mientras haya un bueno reciente: pérdida sistemática y medible (`incRejected("degraded")`). | FixFilter.kt:52,306-308; AppConfig.kt:763-765 (bad=80/good=20) | Medio-alto en kevin/santiago (fixes 80–500 m intermitentes): 30–50 % del tiempo en marcha puede caer en huecos de 20–60 s | En movimiento (speed efectiva ≥1.5 m/s o desplazamiento ≥8 m), NO rechazar por `degraded`: encolar `lowQuality` (el server ya lo marca HIDE conservando fila, MobileQualityFilter.java:90-93). Mantener el rechazo solo en quietud | **S–M** |
| **H3** | **P1** | **Arranque de jornada: primer fix encolado puede tardar hasta 5 min** (`first_fix_bad` exige acc<150 m, `FixFilter.kt:35-36,271-276`; anti-livelock funda ventana a los 10 rechazos (~100 s con cadencia 10 s) o 5 min desde start (`FixFilter.kt:89-94`, `TrackingService.kt:1060-1078`). Además el one-shot de arranque solo se pide en transición STATIONARY→candidato (`LocationEngine.kt:318-328`) o con polling pasivo tras 90 s (`ActiveGpsPolicy.kt:16`): no hay fix one-shot inmediato en `startTracking`. | FixFilter.kt:35-36,89-94; TrackingService.kt:1060-1078; LocationEngine.kt:164-192; ActiveGpsPolicy.kt:16,67 | El primer tramo (hasta 5 min) queda fuera ⇒ afecta la cobertura de TODA jornada iniciada en interior/estacionamiento | (a) `startTracking` → `engine.requestOneShotFix()` inmediato; (b) acortar el force-accept a 10 rechazos aún con acc 150–500 m (`lowQuality`), manteniendo el tope de 5 min; (c) medir TTFF p95 INICIAR→primer encolado | **S** |
| **H4** | **P1** | **Server: vías REJECTED silenciosas mandan el tramo a dead-letter (fila nunca persistida).** (a) `device==null` → REJECTED terminal (`MobileIngestionService.java:168-169`); (b) fallo de Storage al reservar o reuse de clave de dedupe con hash distinto → REJECTED (`MobileMessageStore.java:79-82,96-106`); (c) `observedAt` >7 días → EXPIRED (`MobileEnvelopeValidator.java:27,54-56`). La app cuarentena y borra del outbox (`PositionOutboxDispatcher.kt:263-270`) ⇒ pérdida TOTAL de esa posición sin fila en `tc_positions`. Hoy la tasa debe ser ~0, pero cualquier drift (device no registrado, rotación de deviceId, reenvío con sequence reutilizado) pierde rutas enteras y nadie lo ve. | MobileIngestionService.java:168-169; MobileMessageStore.java:79-106; MobileEnvelopeValidator.java:54-56; PositionOutboxDispatcher.kt:263-270 | Potencialmente total (por device/jornada) si se activa | Contar cada AckStatus y cada veredicto del `MobileQualityFilter` en `mobile.stats` (hoy ACCEPT/HIDE/DUPLICATE/REJECT del filtro no se cuentan, `MobileQualityFilter.java:166-177`); alerta (AdminAlerts) si REJECTED/INVALID/EXPIRED > 0 por device/día; revisión de dead_letters | **S** |
| **H5** | **P2** | **`accuracy_ceiling` ≥500 m y `invalid` se descartan sin dejar rastro en la ruta** (`TrackingService.kt:1039-1046`, `FixFilter.kt:116-122`). En túnel/interior no hay fix real que salvar, PERO el techo también absorbe fixes fused malos que acotarían el hueco (el replay corta la línea ahí aunque el tiempo siga moviéndose). | TrackingService.kt:1039-1046 | Medio en días con copa/túneles | Encolar 500–1000 m como `lowQuality` si hay movimiento (sensor), o al menos registrar la muestra como marcador de presencia (sin coordenada usable no aporta geometría: alternativa = documentar que el corte es honesto) | **S** |
| **H6** | **P2** | **Último tramo en DETENER: la captura muere de inmediato.** `stopTracking()` llama `engine.stop()` primero (`TrackingService.kt:1510-1511`) y `onNewLocation` corta con `stopping` (`TrackingService.kt:1038`); el drain (`StopDrainPolicy` 4 min, `OutboxCoordinator.kt:137-146` + `JourneyStopCoordinator.kt:35-48`) solo sube lo ya capturado. La pata entre el último fix y el toque (≈cadencia 10 s; hasta 60 s si estaba en heartbeat tras quietud) no existe. | TrackingService.kt:1038,1504-1525; OutboxCoordinator.kt:137-146; data/StopDrainPolicy (BufferDrainPolicy.kt:21-37) | ≤10 s por jornada (hasta ~60 s si la ruta termina tras parada) | Al DETENER: pedir un one-shot final y mantener el request 10–15 s antes de `engine.stop()`; encolar los fixes de cierre antes de `flushPendingOnStop()` | **S** |
| **H7** | **P2** | **`implied_speed` en arrancadas 1–15 min** (`FixFilter.kt:295-302`, REACQUIRE 15 min, `REACQUIRE_AFTER_MS=15*60_000`): con referencia a 60 s–15 min y pico real (bus/moto ≥45 m/s implícita por jitter + arranque), rechaza salvo 2 fixes >30 m/s en ventana. Caso estrecho pero real tras hueco medio. Contador `implied_speed` ya existe. | FixFilter.kt:49,295-302 | Bajo-medio (picos aislados) | Tolerar si `dt≥60 s` y el desplazamiento es direccionalmente coherente, o bajar REACQUIRE a 5 min (los huecos reales del fleet son minutos) | **S** |
| **H8** | **P2** | **`deviceId.isBlank()` corta el pipeline SIN contador ni alerta** (`TrackingService.kt:1205-1206`): si prefs se pierden (clear data/restore) la jornada "funciona" (GPS on, corazón vivo) pero CERO posiciones se encolan. | TrackingService.kt:1205-1206 | Total silencioso en ese escenario | `incRejected("no_device")` + alerta + no abrir jornada sin deviceId | **S** |
| **H9** | **P3** | **`clock_future` con tolerancia 2 min** (`FixFilter.kt:114,261-263`): rollover GPS/NTP adelantado >2 min descarta fixes hasta corregir; contra-fuerte de `MAX_FUTURE 24 h` del server (`MobileEnvelopeValidator.java:26,57`). | FixFilter.kt:114,261-263 | Bajo | Bajar a tolerancia server (24 h) o marcar `lowQuality` en vez de rechazar | **S** |
| **H10** | **P3** | **Drift WatchdogPolicy server**: asume captura 5 s en marcha / 120 s quieto (`WatchdogPolicy.java:11-14`) pero la app real es 10 s / 60 s (`ActiveGpsPolicy.kt:102`, `FixFilter.kt:80`). No pierde datos (despierta de más), pero el SILENT→FCM se dispara con base errónea y los informes de silencio mienten. | WatchdogPolicy.java:11-27 | Nulo en cobertura; ruido operativo | Re-mirror a 10 s/60 s | **S** |
| **H11** | **INFO** | **Replay NO oculta puntos grabados** (verificado): `ReplayPage.jsx:280-282` usa `hideInaccurate:false`; `cleanRoutePositions` solo recorta picos imposibles siempre y dedupe/ocultos solo con flag (`pathDecimation.js:1062-1140`); `decimateForMatch` server conserva punto si dist≥12 m o dt≥120 s (`MatchResource.java:55-62,320-338`) y `MAX_REQUEST_POINTS=2000` con `limit` podría truncar jornadas kilopunto (MatchResource.java:222-225). | MatchResource.java:55-62,222-235; pathDecimation.js | Nulo hoy; vigilar el `limit(2000)` en jornadas >5 h a 10 s (1 800 fixes) — el corte es silencioso | Subir/luego paginar MAX_REQUEST_POINTS o truncar por completo con flag en respuesta | **S** |

Notas de descarte (no son pérdida): `rule_deferred` (TrackingService.kt:1237-1249) difiere por diseño; el siguiente fix pasa por frecuencia (10 s). `network_relay`/`stale_relay` son dedupes legítimos.

---

## 2. Cadena de pérdida (dónde se pierde, con contadores existentes)

```
INICIAR
  │  FGS + initCore + MQTT + engine.start (sync, <1-2 s típico; TimeoutFCG pagado primero,
  │  TrackingService.kt:503-533)
  ▼
[ARRANQUE] primer fix encolado = TTFF + filtro primer-fix (acc<150)
  · descartes: first_fix_bad (incRejected, hasta 10 o 5 min)          → H3   [contable ✔]
  · stale de cache inicial → "stale" (no funda ventana)               → FixFilter.kt:264-270
  ▼
[FLP/GPS] LocationEngine (request HIGH_ACCURACY, 10 s moving / 60 s heartbeat)
  · congelado OEM (proceso) → hueco real (no capturable)              → FCM 4/6 min + keeper 2 min
  · batch al despertar → "stale" >120 s: TODA la pata se descarta     → H1 ★ [contable ✔ incRejected("stale")]
  · sin callbacks FLP >10 min → re-init motor 1/15 min                → LocationEngine.kt:224-237
  · rescate R5: burst 90 s, cooldown 5 min, requiere evidencia movimiento (a veces UNKNOWN tras pausa accel)
  ▼
[FILTRO] FixFilter.evaluate (descarta ANTES de encolar)
  · degraded (acc>80 con bueno≤20 en ventana 6) EN MARCHA             → H2 ★ [contable ✔]
  · implied_speed, clock_future, accuracy_ceiling/invalid             → H5,H7,H9 [contable ✔]
  · regla OR (aceptar) → rule_deferred (por diseño)                   → [contable ✔]
  ▼
[PERSISTENCIA] Room insertWithinLimit (retención 100k/7d; drop_oldest solo fuera de ventana)
  · deviceId.isBlank → return SIN contador                            → H8
  · fallo DB → alerta storage, fix no encolado, lastFixAt NO avanza   → TrackingService.kt:1476-1490 [contable ✔]
  ▼
[SALIDA] HTTP batches 50 (wake microbatch 5, trickle 30 s, drain eventos; FIFO+ACK negocio)
  · transport fail/429 → backoff 5s→5min; Room intacto                → PositionOutboxDispatcher.kt:218-240
  · cuarentena solo en NACK terminal (evidencia, no borrado)          → PositionOutboxDispatcher.kt:263-270
  ▼
[SERVER] MobileHttpResource (sem 20, batch≤200, serial)
  · REJECTED (device==null / store fail / hash)  → ACK rejected → dead-letter móvil, SIN fila
  · INVALID/EXPIRED (validador 7 d)              → ACK terminal → dead-letter, SIN fila   → H4 ★
  · DUPLICATE (relay exacto ≤120 s speed 0)      → sin fila (deseado)  → MobileQualityFilter.java:117-127
  · HIDE (acc>80/500 o implícita >140 kn)        → fila CONSERVADA valid=false          → OK cobertura
  · FilterHandler BYPASS para dmj-mqtt (no re-filtra) → PositionPipeline.java:158-165    → OK
  ▼
[TC_POSITIONS] (fuente del replay)
  ▼
[REPLAY] decimation solo picos; hideInaccurate=false; match 97 %        → H11 solo tope 2000 pts
DETENER
  · engine.stop() inmediato → pata final ≤cadencia perdida            → H6
  · drain 4 min + TrackingRecoveryWorker (15 min + backoff) si muere  → OK (Room intacto)
```

Lectura operativa: **la pérdida masiva medida (>60 %) NO está en salida ni servidor ni replay** (drenaje/ACK/HIDE conservan). Se produce en dos puntos de captura: el congelado OEM (irreducible en app; mitigado por FCM/keeper) y **el descarte del lote recuperado (H1)** más las reglas de calidad **en movimiento (H2/H5)**.

---

## 3. Criterios de aceptación ">90 % trazado" (definición medible)

Definición de cobertura (idéntica a la línea base, SQL listo — `speed` en tc_positions va en KNOTS ×0.514444):

```sql
WITH p AS (
  SELECT device_id,
         attributes->>'mobile.journeyId' AS journey_id,
         fix_time, speed,
         LAG(fix_time) OVER w AS prev_t,
         LAG(speed)    OVER w AS prev_speed
  FROM tc_positions
  WHERE attributes->>'mobile.journeyId' IS NOT NULL AND fix_time IS NOT NULL
  WINDOW w AS (PARTITION BY device_id, attributes->>'mobile.journeyId' ORDER BY fix_time)
),
legs AS (
  SELECT journey_id,
         EXTRACT(EPOCH FROM (fix_time - prev_t)) AS dt,
         GREATEST(COALESCE(speed,0), COALESCE(prev_speed,0)) * 0.514444 AS seg_mps,
         EXTRACT(EPOCH FROM (fix_time - prev_t)) <= 15 AS tight
  FROM p WHERE prev_t IS NOT NULL
),
m AS (
  SELECT journey_id,
         SUM(dt) FILTER (WHERE seg_mps > 3)                       AS moving_s,
         SUM(dt) FILTER (WHERE seg_mps > 3 AND dt <= 15)          AS covered_s,
         SUM(dt) FILTER (WHERE seg_mps > 3 AND dt > 60)           AS big_gap_s,
         COUNT(*) FILTER (WHERE seg_mps > 3 AND dt > 60)          AS big_gap_n
  FROM legs GROUP BY 1
)
SELECT journey_id,
       ROUND(100*covered_s/NULLIF(moving_s,0),1) AS coverage_pct,
       big_gap_n, ROUND(big_gap_s,0) AS big_gap_s
FROM m ORDER BY 2;
```

Objetivos por etapa (cierre de jornada = fila del journey_id):

| Etapa | Métrica (fuente) | Objetivo |
|---|---|---|
| Arranque | INICIAR→primer fix encolado (p95) | ≤60 s |
| Captura en marcha | coverage_pct (SQL arriba) | **≥90 %** por device/día |
| Captura en marcha | huecos >60 s con speed>3 (big_gap_n) | ≤1 por hora-jornada |
| Filtro móvil | rejects "stale"+"degraded" en marcha vs fix_received | ≤10 % (hoy H1+H2 dominan) |
| Regla OR | rule_deferred | libre (diferido por diseño; fix posterior siempre pasa) |
| Persistencia | fix_enqueued / (fix_received − rejects) | ≥99 % |
| Cuarentena | quarantinedTotal / dead_letters por jornada | 0 |
| Transporte | lag primer fix→ACK p95; backlog al cerrar | ≤60 s; 0 |
| Server | outcomes rejected/invalid/expired (mobile.stats) | 0; duplicate ≤2 % |
| Server | HIDE (valid=false) en marcha | ≤20 % (fila conservada; no rompe cobertura) |
| Replay | raw vs drawn; snappedRatio | sin recorte (≤2000 no alcanzado); match ≥95 % |

---

## 4. Los 10 arreglos con mayor impacto en el 90 %

1. **H1 — Aceptar fixes batched post-congelado como lowQuality (no `stale`)** (FixFilter.kt:264-270). Mayor apalancamiento: ataca directamente los huecos >60 s dominantes. Contable: `stale_accepted` nuevo.
2. **H2 — `degraded` solo en quietud; en marcha encolar lowQuality** (FixFilter.kt:306-308). Recupera tramos 20–60 s en ZTE/borde de copa.
3. **H3 — One-shot inmediato en `startTracking` + force-accept a 10 rechazos con acc 150–500 m** (TrackingService.kt:594-638; FixFilter.kt:271-276). Arranque ≤60 s p95.
4. **H4 — Conteo/alerta de outcomes server + veredictos del MobileQualityFilter en mobile.stats; alerta REJECTED>0** (MobileIngestionService.java:177-186; MobileQualityFilter.java:166-177). Blindaje del blast radius total.
5. **H5 — Techo accuracy 500–1000 m en marcha ⇒ lowQuality** (TrackingService.kt:1039-1046).
6. **H6 — Pata final: one-shot + 10–15 s de captura tras DETENER antes de engine.stop()** (TrackingService.kt:1504-1525).
7. **H7 — implied_speed: tolerar dt≥60 s o REACQUIRE a 5 min** (FixFilter.kt:49,295-302).
8. **Instrumentación de embudo jornada-scoped** (ya hay contadores en AppConfig: fix_received/rejected/enqueued, ack/retry/quarantined; agregar flush por jornada + tablero): pre-requisito para medir el 90 % por etapa sin esperar el día.
9. **H8 — deviceId blank: contador + alerta + bloqueo de jornada** (TrackingService.kt:1205-1206).
10. **H9/H10 — clock_future ⇒ lowQuality; WatchdogPolicy re-mirror 10 s/60 s** (FixFilter.kt:261-263; WatchdogPolicy.java:11-27). Higiene; evita descartes por reloj y sondeos con base falsa.

**No tocar** (verificado correcto para cobertura): FilterHandler bypass dmj-mqtt (PositionPipeline.java:158-165); HIDE conserva fila (MobileQualityFilter.java:84-93); retención 100k/7d con alerta (OutboxRetentionPolicy.kt:36-66); FIFO+ACK y backoff del outbox (PositionOutboxDispatcher.kt:247-283); drain de cierre 4 min + worker (OutboxCoordinator.kt:137-146; TrackingRecoveryWorker.kt:126-162); decimation replay (MatchResource.java:320-338; pathDecimation.js:855-904 con hideInaccurate=false en ReplayPage.jsx:281).


---

## Estado de ejecución (2026-09-19 noche)

**Implementado en 1.1.18 (vc128, OTA publicada, sha `9340c11c…`):**
- **H1 (P0)**: fixes recuperados en lote tras congelado OEM se ACEPTAN
  `lowQuality` (techo 30 min, `STALE_RECOVER_MAX_NANOS`); >30 min sigue
  descartando. Tests nuevos: `staleRecoveredBatchIsAcceptedLowQuality`,
  `staleVeryOldStillRejects`.
- **H2**: `degraded` en movimiento (implícita ≥1.5 m/s) se encola `lowQuality`
  (el server lo HIDE conservando fila); en quietud sigue rechazando.
  Tests: `degradedFixInMotionIsAcceptedLowQuality` + ajuste del umbral.
- **H3**: `startTracking` pide `requestOneShotFix()` inmediato (TTFF en
  segundos al INICIAR).

**Línea base HOY (cobertura del tiempo EN MOVIMIENTO con huecos ≤15 s):**
joseph 1% · miguel 2% · david 4% · kevin 24% · santiago 34% — dominada por
huecos >60 s de congelados (H1) y arranques (H3).

**Objetivo medible (SQL en el doc):** ≥90% del tiempo en movimiento con huecos
≤15 s, ≤1 hueco >60 s por hora, y sin descartes anómalos. A medir 48 h después
de que la flota actualice a 1.1.18.

**Pendiente del informe:** H4 (server REJECTED sin contador), H8 (deviceId
blank), H10 (mirror watchdog 10s/60s — server), H11 (tope 2000 puntos del
match), pata final al DETENER (H6), refactor de orden (H15).

---

## Caso 20/09: santiago y joseph (post-1.1.18)

- **Ambos actualizaron HOY**: santiago a 1.1.18 a las **08:10**, joseph a las
  **08:23**. Sus rutas de la madrugada/mañana corrieron con versiones viejas:
  santiago (00:00-09:04) en 1.1.16, joseph (mañana) en 1.1.14.
- **santiago — el "corte enorme" (09:04:24 → 09:22:56, 18.5 min, ~2 km)**:
  la noche fue una parada de 8 h 47 con **99 puntos a ±21 m** (interior/estac.)
  — proceso congelado por Hiber (Infinix) con cadencia 5-12 min. Al arrancar
  (09:04), el proceso seguía congelado y el GPS frío dentro del estacionamiento
  → el primer fix útil llegó 18.5 min después y los cold-start salieron con
  precisión 22-78 m (la parte "desalineada"). NO hay evento REASON_FREEZER
  (Hiber congela sin matar) y sin root no hay blindaje extra.
- **FCM está rindiendo excelente**: santiago 55 probes → **52 SOFT_SUCCESS
  (96%)**; cada despertar recupera UNA posición, pero el lote congelado no
  existe (Hiber lo pierde) — por eso los huecos nocturnos de 6-18 min.
- **joseph**: 17 soft + 7 SUCCESS; huecos de 4-16 min continúan EN 1.1.18
  (MagicOS 9 congela aunque esté exento — documentado como límite).
- **El canal GEOFENCE (1.1.19) ataca exactamente este corte**: al salir del
  estacionamiento (radio 150 m del ancla), GMS —que no se congela— entrega la
  transición → fix inmediato + FGS exento. Espera: el corte de 18.5 min baja a
  segundos. **Requiere que la flota pase a 1.1.19.**
- **Alineación**: los fixes de cold-start salen con 22-78 m (dato honesto);
  opción pendiente de decisión: marcar lowQuality en el server para ocultarlos
  del replay (hoy se dibujan porque accuracy ≤80 m).
