# ROUTE SEGMENTATION AUDIT (R3) — Telemetría / Segmentación de Ruta

**Agente I — Telemetry/Route Segmentation Auditor (R3).** 2026-09-17 (TZ local −05).
**Alcance:** solo lectura de código; datos reales verificados hoy por SQL contra `dmj-db` (dispositivos 39 joseph, 50 macias, 40 kevin, 41 miguel, 42 david). Propuesta de diseño pura; **no** se tocó código. Prohibiciones respetadas: nada de alterar posiciones/timestamps, nada de map-matching para ocultar gaps, nada de reglas por dispositivo (§52).

---

## 1. AUDIT DEL ALGORITMO ACTUAL (file:line)

### 1.1 Dónde se decide el corte

| Mecanismo | Archivo:línea | Qué hace |
|---|---|---|
| `shouldCut(a,b)` | `dashboard/src/map/util/pathDecimation.js:949-962` | Único predicado de segmentación. Orden: (1) `dist ≤ SAME_PLACE_M(100m)` → **nunca** corta (parada); (2) fast-gap: `dt > FAST_GAP_DT_MS(45s)` Y `dist > FAST_GAP_DIST_M(200m)`; (3) `dt > MAX_GAP_MS(5min)`; (4) `isTeleport` (>500 m en <15 s, o implícita >35 m/s sin Doppler ≥0.4×, :886-925) |
| `splitByGapAndTeleport` | `pathDecimation.js:965-981` | Parte la secuencia en chunks donde `shouldCut` = true |
| `cleanRoutePositions` | `pathDecimation.js:1030-1161` | Siempre des-spika (fantasmas); con `hideInaccurate` además deduplica, oculta red/inexactos, colapsa rachas. Cortes adicionales `cuts`: cuerda sobre ocultos >150 m y salto aislado >250 m (:1101-1146) |
| `buildCanonicalRouteGeometry` | `canonicalRouteGeometry.js:95-162` | Ordena por fixTime (:110-117), limpia, divide por gaps **y** por `cuts` (:134-151). De aquí salen LÍNEA (`lineSegmentsFor` :322-336, pares consecutivos **dentro** del chunk) y FLECHAS (`generateRouteArrows`, gate `ARROW_MAX_LEG_DT_MS=20s`/`ARROW_MAX_LEG_M=150m` :47-48,284) |

**La línea principal SÍ respeta el corte** (`MapRoutePath.js:84` → geometría canónica; `lineSegmentsFor` nunca produce un segmento entre chunks; verificado con los datos reales: 0 segmentos cruzan cortes, ver §2). El loop de replay también lo salta (`ReplayPage.jsx:454` `shouldCut(from,to)`).

### 1.2 Dónde NO se respeta (los casos reales se pintan continuos)

**B-1 · La línea del replay dibuja rectas a través de cortes.** `replayAudit.js:344-354` `linkTracksFor` construye conectores rectos entre piezas consecutivas (marcha↔parada) **sin evaluar `shouldCut`**; `ReplayPage.jsx:293-302` los mete en `replayLine` y `MapRouteMatch.jsx:85-100` los dibuja como tracks sin segment casado ("línea honesta"). El corte del pipeline vale para los chunks del matcher (`decimateForMatch` :830-872), pero el conector de pieza vuelve a UNIR lo que `shouldCut` había cortado. Reproducciones reales (macias 50): link 10:17:47→10:22:06 = **994 m en 259 s**, link 10:27:03→11:07:40 = **225 m en 40.6 min**, link 19:47:57→19:59:23 = **1957 m en 686 s**; joseph (39): link 18:03:01→18:18:30 = **2941 m en 929 s**. Además `detectStops` fabricó en macias una "parada" de 994 m (ver B-4) cuya polilínea cruda (`stopRaws`, ReplayPage.jsx:278-289) dibuja OTRA recta de 994 m.

**B-2 (P1) `syncDelayMs` compara bases horarias distintas.** `replayAudit.js:72-79` resta `serverReceivedAt` (ISO-8601 **UTC**, atributo de la app) sobre `fixTime` (columna tc_* en hora **local −05**, serializada con `Z` por la API). Evidencia: joseph fixTime `11:40:37Z` vs `serverReceivedAt 16:40:40Z` → `syncDelayMs` mediana **18 004.7 s** para TODOS los fixes de TODOS los dispositivos (joseph 113/113, macias 50/50, kevin 317/317 marcados `synced`+`clock` por `flagAnomalies`/`isOfflineSynced` :230-236, `CLOCK_SKEW_MS=1h` :32). La señal real (lotes joseph 975–3600 s; backfill macias 35 243–36 592 s; normal 2–7 s) queda enterrada bajo el offset estructural de +5 h.

**B-3 (P2) Dos clasificadores de hueco que se contradicen.** `flagAnomalies` (GAP: `dt > MAX_GAP_MS && dist > SAME_PLACE_M`, :215) y `offlinePeriods` (:243-264) ignoran el fast-gap (45 s–5 min) que `shouldCut` sí corta: la línea se corta en macias 10:22:06→10:26:43 (277 s, 270 m) pero el panel NO lo lista como "sin cobertura"; y a la inversa, periodos de 40 min stationary (dist ≤100 m) no se listan (correcto) aunque el conector B-1 sí los dibuja.

**B-4 (P2) Parada fantasma por outlier sin tope de distancia.** `detectStops` (:296-339) usa `forEachRobustRun` (:95-125) que tolera un punto lejano como outlier si su Doppler < 3 kn — **sin límite de distancia** — y a diferencia de `collapseStillClusters` (:136-187 `runIsStationary`), **no valida el diámetro**. Efecto medido: macias raw[17]=10:13:10 y raw[18]=10:17:47 (994 m aparte, doppler≈0 por estar quieto) entran como racha "quieta" de 4.6 min → parada fantasma (`stop[17,19)`), que alimenta los rectas de B-1.

**B-5 (mejora, no bug de dibujo) Las señales de la mensajería/presencia no llegan al dominio del dashboard.** `tc_events` (mobileStalled, presence OFFLINE→ONLINE, NetworkLost/Restored), `tc_recovery_event` (RECOVERY_*), `tc_mobile_messages` (outbox, heartbeats `positionid=0`) y `tc_device_health` (outbox 255 en macias 17:24) existen en DB pero el pipeline del dashboard no los consulta: hoy no hay forma de distinguir CAPTURE_GAP de DELIVERY_DELAY ni de detectar RECOVERY.

### 1.3 Qué corta BIEN (verificado ejecutando el pipeline real con `node`)

- joseph: 9 chunks, 0 segmentos cruzando cortes, recta dibujada más larga **103 m / 6 s** (pair 15:49:11→15:49:17, 62 km/h impl. **corroborado** por doppler 49.6→18.3 km/h; el teleport :905-925 lo respeta). Los 7 saltos reales (2291, 3552, 1856, 856, 4268, 3759, 2941 m) quedan cortados; los gaps stationary 900–1830 s con dist 1–13 m NO se cortan por SAME_PLACE (correcto: son paradas).
- macias: 10 chunks, longest drawn 97 m/5 s; todos los saltos buffered (970 m/463 s, 3278 m/277 s, 994 m/259 s, 270 m/277 s, 1302 m/454 s, 1957 m/686 s) cortados por fast-gap o max-gap.
- kevin: 317 fixes, 0 gap/teleport/speed — control limpio 1:1 (latencia 3 s).
- miguel/david: micro-excursiones stationary (24–152 m) quedan dibujadas honestas (≤100 m, no cruzan cuadras); los spikes 80–142 m nocturnos de miguel los cubre `filterSpikes`/`collapseNoisyRuns`.

**Banda ciega detectada:** huecos `45 s < dt ≤ 300 s` con `100 < dist ≤ 200 m` NO se cortan hoy (ni fast-gap por distancia ni max-gap por tiempo). No hubo caso real hoy en esa banda con dist grande (los buffered de macias son 259–323 s con 270–3278 m, todos ≥200 m), pero es la única ventana sin cobertura en `shouldCut`.

---

## 2. EVIDENCIA POR CASO REAL (SQL reproducible, TZ local −05)

```sql
-- Saltos de ruta (par consecutivo): dt, dist, implícita, doppler, corte teórico
WITH p AS (SELECT fixtime, servertime, latitude, longitude, speed, attributes::json a,
  lag(fixtime) OVER (ORDER BY fixtime) pfix, lag(latitude) OVER (ORDER BY fixtime) plat,
  lag(longitude) OVER (ORDER BY fixtime) plon, lag(servertime) OVER (ORDER BY fixtime) psrv
  FROM tc_positions WHERE deviceid=:D AND fixtime::date='2026-09-17')
SELECT to_char(fixtime,'HH24:MI:SS'),
  extract(epoch FROM (fixtime-pfix))::int dt_s,
  2*6371000*asin(least(1,sqrt(pow(sin(radians(latitude-plat)/2),2)+cos(radians(plat))*cos(radians(latitude))*pow(sin(radians(longitude-plon)/2),2)))) d_m,
  extract(epoch FROM (servertime-fixtime))::int delivery_s
FROM p WHERE pfix IS NOT NULL HAVING ... -- dist>800 o dt>180
```

### joseph (39, HONOR, app 1.1.5) — 113 posiciones hoy (11:40–21:17)

| # | Par (fixTime) | dt | dist | impl. | doppler | delivery (servertime−fixtime) | `shouldCut` |
|---|---|---|---|---|---|---|---|
| 1 | 11:45:39→12:01:01 | 921 s | 2291 m | 9.0 km/h | 9.0 (implied) | 18 011→18 025 s* | **cut** |
| 2 | 15:04:30→15:20:01 | 931 s | 223 m | 0.9 | **49.6 EXCELLENT conf 92 (miente: implied 0.9)** | 20 700 s | **cut** |
| 3 | 15:20:01→15:34:54 | 893 s | 3552 m | 14.3 | 18.3 doppler | 20 700→19 807 s* | **cut** |
| 4 | 15:34:54→15:48:01 | 787 s | 1856 m | 8.5 | 14.2 doppler, **fixAgeSec=118** | 19 020 s* | **cut** |
| 5 | 15:50:01→16:05:21 | 920 s | 856 m | 3.4 | 3.4 | 18 919 s* | **cut** |
| 6 | 17:17:43→17:33:00 | 917 s | 4268 m | 16.8 | 16.7 implied, EXCELLENT conf 87 | 20 700 s* | **cut** |
| 7 | 17:33:00→18:03:01 | 1801 s | 3759 m | 7.5 | 7.5 | 18 900 s* | **cut** |
| 8 | 18:03:01→18:18:30 | 929 s | 2941 m | 11.4 | 11.4 | 18 003 s* | **cut** |

\* con `serverReceivedAt` (UTC) la resta suma el offset estructural +5 h — ver B-2.

- **GPS suspendido en stationary:** gaps de 541–1830 s con dist 1–13 m (SAME_PLACE, sin corte) intercalados: la app para el GPS quieto (76 `mobileStalled`, 55 heartbeats `positionid=0`) y lo reactiva al moverse: el hueco abarca el arranque real del trayecto y la recta cruda cruzaría cuadras — hoy se corta correctamente.
- **Entrega en lotes tras reconexión:** servertime−fixtime 2 s → 3 600 s (12:33:59); batch 15:48:46–15:50:01 con latencia decreciente 975→918 s (mismo flush); `tc_mobile_messages` 16:05 = 23 msgs en un minuto; `tc_recovery_event` hoy: 32 RECOVERY_SENT / 29 RECOVERY_TIMEOUT / 3 RECOVERY_SUCCESS; `tc_device_health` oscila LIVE↔DEGRADED↔SILENT con outbox 0–2.
- 18 `mobilePresenceOffline` + 18 `deviceOnline`; 60 suspect/recovered (105 transiciones de presencia del informe forense).
- Pipeline (reproducido con `buildCanonicalRouteGeometry` sobre las 113 posiciones): hideInaccurate=true → 9 chunks, 36 segmentos, **0 rectas sobre cortes**; el REPLAY (match activo) dibuja 1 conector recto de 2941 m (B-1).

### macias (50, ZTE, app 1.1.5) — 50 posiciones hoy (10:03–21:22)

- **Corte total de conectividad 10:04→20:14:** `tc_events` `mobileNetworkLost` 10:04 → `mobileNetworkRestored` 20:14; 40 `RECOVERY_ATTEMPT` → 40 `RECOVERY_TIMEOUT` (el poll nunca obtuvo token); 3 presence offline.
- **Backfill de una ráfaga:** `tc_mobile_messages` 311 creadas en el minuto 20:14 (vs 9 en 10:03) con 27 positionid distintos; heartbeats `positionid=0` = 294 del día; outbox llegó a **255** (`tc_device_health` 17:24).
- **Fixes buffered 10:04:33–10:27:03 entregados todos a servertime 20:14:25:** delivery = **35 243–36 592 s**; calidad **EXCELLENT conf 92–100 doppler** (la captura fue buena; lo que falló fue la entrega). `fixAgeSec` **ausente** en esos fixes → la app no reportó edad; sin `servertime−fixtime` no hay señal.
- Saltos y cortes (todos `shouldCut=TRUE`):

| Par | dt | dist | impl. | calidad extremos | regla que corta |
|---|---|---|---|---|---|
| 10:05:02→10:12:45 | 463 s | 970 m | 7.5 km/h | GOOD / EXCELLENT 92 | fast-gap |
| 10:13:10→10:17:47 | 277 s | **3278 m** | 42.6 | EXCELLENT 92 / 100 | fast-gap |
| 10:17:47→10:22:06 | 259 s | 994 m | 13.8 | 100 / DEGRADED 72 | fast-gap |
| 10:22:06→10:26:43 | 277 s | 270 m | 3.5 | 72 / EXCELLENT 100 | fast-gap |
| 10:27:03→11:07:40 | 2437 s | 225 m | 0.3 | EXCELLENT / GOOD | max-gap |
| 11:45:57→12:05:14 | 1157 s | 131 m | 0.4 | GOOD / GOOD | max-gap |
| 12:05:14→16:30:02 | 15 888 s | 131 m | 0.0 | GOOD / GOOD | max-gap |
| 19:40:23→19:47:57 | 454 s | 1302 m | 10.3 | GOOD / GOOD | fast-gap |
| 19:47:57→19:59:23 | 686 s | 1957 m | 10.3 | GOOD / GOOD | fast-gap |

- **Gaps buffered de 4.3–5.4 min (259–323 s):** los ≥200 m los cubre fast-gap; **el par de 323 s (20:59→21:04, 18 m) es stationary** → SAME_PLACE, sin corte (correcto). Banda ciega real: 4.6 min con ~150 m pasarían hoy sin corte.
- **Parada fantasma (B-4):** `detectStops` → `index=17 n=2 10:13:10→10:17:47 dur 4.6 min` con los extremos a **994 m**; la racha entró por outlier doppler<3 kn y `detectStops` no valida diámetro (a diferencia de `collapseStillClusters`).
- Pipeline: 10 chunks, longest drawn 97 m/5 s; REPLAY dibuja 5 conectores, 3 de ellos sobre `shouldCut=true` (994 m, 225 m, 1957 m) — B-1.

### kevin (40) — control

317 fixes hoy, entrega 3 s, 0 saltos, 0 gaps (`flagAnomalies` solo reporta `synced`/`clock` = 317/317, todo artefacto del B-2). 1 `mobileNetworkLost→Restored` breve sin efecto en la ruta.

### miguel (41)

Hoy 22 fixes (18:01–21:19): 152 m/934 s y 2412 m/1795 s cortados por max-gap. **Anoche (buffer 18.2 h):** fixes con `gnssUsed=0/30` (fused de red), doppler≈0 y `qualityClass` GOOD/EXCELLENT **mentirosa**, con micro-saltos 80–142 m (27–41 km/h implied estacionario): firma de multipath de red, no de hueco de ruta. Señal discriminatoria disponible: `gnssUsed=0` + excursion-revierte + doppler≈0.

### david (42)

Stationary; micro-jumps 24–66 m con `fixConfidence` 70–72 (DEGRADED) a las 18:26, 19:35, 20:14–20:27 que **revierten al punto base** (ping-pong). SAME_PLACE=100 los tolera (correcto); `isPingPong/isSandwich` ya los captura como fantasmas si son ≥80 m. No son huecos de ruta; son ruido clasificable.

---

## 3. DISEÑO PROPUESTO — Segmentación basada en evidencia

**Principio:** el corte ya está calibrado por la evidencia (`shouldCut`). Lo que falta es **decir QUÉ fue** cada discontinuidad (narrativa para el operador) y **una sola fuente de verdad** para línea/panel/match. Se conservan todos los umbrales medidos hoy; nada mágico nuevo.

### 3.1 Modelo (puro, testeable, sin React/mapa)

```ts
// domain/RouteSegmentModel.js
type RouteSegmentType =
  | 'OBSERVED_CONTINUOUS'   // hay evidencia de continuidad
  | 'CAPTURE_GAP'           // no se capturó: GPS suspendido / sin fixes; se CORTA el dibujo
  | 'DELIVERY_DELAY'        // se capturó bien; llegó tarde (lote/backfill); línea normal + indicador
  | 'RECOVERY_GAP'          // interrupción de servicio (red/proceso) con recuperación declarada; se CORTA + indicador
  | 'UNKNOWN';              // señales insuficientes (tiempo inválido, sin reloj ni eventos); neutro

interface RouteSegmentBoundary {      // discontinuidad a→b (una pata)
  fromIndex: number; toIndex: number;
  fromFixTime: string; toFixTime: string;
  type: RouteSegmentType;
  cut: boolean;                        // ¿la línea se dibuja entre ambos? (cut=true ⇒ NO segmento)
  signals: {
    captureDtMs: number;               // fixtime_b − fixtime_a (columnas tc_*, misma base local)
    captureDistM: number;
    impliedSpeedMps: number;
    reportedSpeedKnA?: number; reportedSpeedKnB?: number;
    speedSourceA?: string; speedSourceB?: string;
    deliveryDelayMsA?: number;         // servertime − fixtime (misma base tc_*); NUNCA mezclar serverReceivedAt UTC
    deliveryDelayMsB?: number;
    backfillBatch: boolean;            // Δservertime ≤ 2 s con Δfixtime > 45 s (lote: macias 20:14:25 ×6)
    qualityClassA/B, fixConfidenceA/B, gnssUsedA/B?;
    heartbeatsInGap: number;           // tc_mobile_messages positionid=0 con created ∈ (a,b)
    recoveryEventsInGap: string[];     // NetworkLost/Restored, presence OFFLINE→ONLINE, RECOVERY_SUCCESS/ATTEMPT
  };
}

interface RouteSegment { kind: 'observed'|'boundary'; points?: Position[]; boundary?: RouteSegmentBoundary }
interface RouteSegmentPolicy { /* constantes calibradas abajo */ }
```

### 3.2 `RouteSegmentPolicy` — constantes calibradas con los datos de HOY

| Constante | Valor | Justificación (evidencia) |
|---|---|---|
| `SAME_PLACE_M` | 100 m (existente) | joseph gaps 900–1830 s con 1–13 m; macias 4675 s con 70 m; david 24–66 m revirtiendo; parada real ≠ hueco de ruta |
| `MAX_GAP_MS` | 300 s (existente) | todo par real de HOY con dt>300 s y dist>100 m fue hueco (joseph 787–1801 s, macias 454–15 888 s) |
| `FAST_GAP_DT_MS` / `FAST_GAP_DIST_M` | 45 s / 200 m (existente) | continuos reales de hoy: máx **103 m / 6 s** (joseph, corroborado doppler); buffered macias: 259–277 s con **270–3278 m** → separación de clase clara, no umbral mágico |
| `DELIVERY_DELAY_MIN_MS` | 600 s | normal hoy 2–7 s (kevin 3 s); lotes joseph 975–3 600 s; macias 35 243–36 592 s. Piso 10× la normal con 2 órdenes de margen |
| `BACKFILL_BURST` | Δservertime ≤ 2 s ∧ Δfixtime > 45 s | macias: 6 fixes con servertime idéntico 20:14:25 |
| `TELEPORT_*` | existentes (500 m/15 s; 35 m/s; corrob. 0.4) | joseph 103 m/6 s/62 km/h corroborado → no corta; correcto |
| Regla de coherencia | `cut ⇔ type ∈ {CAPTURE_GAP, RECOVERY_GAP}` | el tipo y el corte nunca divergen (elimina B-1/B-3) |
| Offset horario | **derivado por dispositivo**: `median(servertime − fixtime)` del rango | evita hardcodear −05 y hace robusto el `deliveryDelayMs` (fix B-2) |

**Respuesta a la pregunta del encargo** — ¿cómo se clasifican los gaps de ~4.6 min de macias con implícita 42.6 km/h? La implícita **corroborada por el propio par no existe** (doppler en los extremos 0–92 conf, pero la "42.6" sale de `dist/dt` con 4.6 min de silencio): la señal decisiva es la **ausencia de fixes intermedios en un intervalo > FAST_GAP_DT_MS con desplazamiento > FAST_GAP_DIST_M** — es la firma de captura con pantalla apagada (joseph) o buffering (macias: además backfill + outbox 255 + RECOVERY_TIMEOUT). Con evidence adicional se desambigua: macias 10:17:47→10:22:06 llega con `Δservertime≈0` dentro del lote 20:14:25 → el hueco es de **captura** (CAPTURE_GAP), no de entrega; el tramo 10:04:33→10:05:02 (_delivery_ 36 592 s pero dt continuo 7 s) es **DELIVERY_DELAY**.

### 3.3 `RouteSegmentAnalyzer` — pseudocódigo

```
analyze(positions, { events, heartbeats, offsetMs }) -> RouteSegment[]:
  sort por fixTime (estable)
  offsetMs = mediana(servertime − fixtime) del rango          // base única tc_*
  para cada par consecutivo (a, b):
    S = señales(a, b, events, heartbeats, offsetMs)
    si fixTime inválido o dt ≤ 0            → UNKNOWN (cut=false)
    si dist ≤ SAME_PLACE_M                  → OBSERVED_CONTINUOUS (parada; flag stop; nunca corta)
    si dt > MAX_GAP_MS o (dt > FAST_GAP_DT_MS ∧ dist > FAST_GAP_DIST_M) o isTeleport(a,b):
        cut = true
        tipo = RECOVERY_GAP  si events del hueco muestran interrupción de servicio
                              (NetworkLost…Restored, presence OFFLINE→ONLINE, RECOVERY_*) sin heartbeats
             = CAPTURE_GAP  si heartbeatsInGap > 0 o recovery-attempts dentro del hueco
                              (dispositivo vivo, GPS suspendido: caso joseph)
             = CAPTURE_GAP  si no hay evidencia en absoluto (conservador: se corta igual)
    si no:
        tipo = DELIVERY_DELAY si deliveryDelay(b) > DELIVERY_DELAY_MIN_MS ∨ backfillBatch(a,b)
             = OBSERVED_CONTINUOUS en otro caso
        cut = false
  emitir segmentos: run de OBSERVED/DELIVERY continuos + boundary por corte
  los DELIVERY/UNKNOWN anotan indicadores; los CAPTURE/RECOVERY anotan hueco visible
```

Reglas derivadas de datos, no de a priori: los tres umbrales numéricos son los que ya tiene el código; lo nuevo es (a) el desambiguado por evidencia de mensajería/presencia, (b) la base horaria correcta para `deliveryDelay`, (c) que **corte y tipo salen de la misma decisión**.

---

## 4. MATRIZ DE TESTS DETERMINISTA (casos A–G)

Puros: `RouteSegmentAnalyzer.analyze(positions, context)` con inputs sintéticos (y los reales congelados como fixtures). Salida esperada: `type` + `cut` + `indicator`.

| Caso | Input (síntético, determinista) | Esperado |
|---|---|---|
| **A** | 2 fixes: dt **60 s**, dist 50 m, doppler 8 kn, servertime+3 s | `OBSERVED_CONTINUOUS`, `cut=false`, sin indicador (dt>45 s pero dist≤200 m: fast-gap no aplica) |
| **B** | 2 fixes: dt **20 min**, dist 3000 m, EXCELLENT ambos, sin eventos | `CAPTURE_GAP`, `cut=true`, hueco visible; caso espejo joseph 3759 m/1801 s |
| **C** | 3 fixes 10:12:45→10:12:50→10:12:57 (dist ≤30 m) **todas** con servertime 20:14:25 (delivery 36 100 s) | patas 1–2: `DELIVERY_DELAY`, `cut=false`, indicador de retraso (caso macias real, backfillBatch=true) |
| **D** | hueco dt 10 min con eventos `mobileNetworkLost`→`mobileNetworkRestored` + `RECOVERY_SUCCESS` dentro, 0 heartbeats, fix posterior EXCELLENT | `RECOVERY_GAP`, `cut=true`, indicador de reconexión |
| **E** | 2 fixes sin servertime ni eventos; dt válido 8 min, dist 400 m | `UNKNOWN`, `cut=true` (conservador), sin indicador; neutro en UI |
| **F** | 2 fixes: dt **270 s (4m30s)**, dist 50 m, doppler≈0 ambos extremos, misma cuadra | `OBSERVED_CONTINUOUS`, `cut=false` — **sin corte automático** (SAME_PLACE) |
| **G** | 2 fixes: dt **270 s (4m30s)**, dist **3278 m** (o 270 m), EXCELLENT | discontinuidad evaluada: `CAPTURE_GAP`, `cut=true` (fast-gap) — reproducción exacta de macias 10:13:10→10:17:47 y 10:22:06→10:26:43 |

Casos de regresión adicional (del suite existente y de hoy): pares continuos 103 m/6 s/62 km/h corroborado → no corta (teleport); stationary 900–1830 s con 1–13 m → no corta; `node --test` puro sin React/mapa (mismo patrón que `arrowGates.test.js`).

---

## 5. VISUAL (reglas 33/51) e impacto en el dashboard

**Orden de implantación: modelo → clasificación → API → frontend → visual.** El frontend dibuja; el dominio decide.

| Tipo | Línea | Puntos | Indicador |
|---|---|---|---|
| OBSERVED_CONTINUOUS | normal (color por velocidad actual) | visibles | — |
| CAPTURE_GAP | **NO se dibuja** la recta (discontinuidad visible: el operador eligió "no dibujar la recta" al ver que no hay evidencia) | ambos extremos visibles | etiqueta "sin captura X min" en el hueco |
| DELIVERY_DELAY | normal (la geometría no cambia: la captura fue válida) | visibles | chip/etiqueta con delay (p. ej. "entregado 10 h 1 min después"; macias 36 592 s) |
| RECOVERY_GAP | NO se dibuja la recta | visibles | icono de reconexión + eventos (NetworkLost→Restored) |
| UNKNOWN | neutro (comportamiento actual, sin corte visual agresivo) | visibles | neutro |

- `MapRoutePath.js` y `MapRoutePoints.jsx` ya dibujan desde la geometría canónica → sustituir `shouldCut` por `analyzer.cut` (mismo resultado hoy, misma fuente).
- `ReplayPage.jsx`: `replayLine` debe excluir los conectores B-1 (`linkTracksFor` solo si `!shouldCut`) y pasar los `signals` al panel Detalles (sustituye la fila `coverageGap` :1002-1009 y las banderas `flagLabels`).
- `replayAudit.js`: `offlinePeriods`/`flagAnomalies` se computan a partir del Analyzer (una sola verdad; elimina B-3). `integritySummary` suma por tipo (captureGapCount, deliveryDelayCount, recoveryGapCount).
- API: exponer al dashboard las señales de contexto ya existentes en server (`tc_events` filtradas por device/ventana — `mobileStalled`, presence, Network*, `tc_recovery_event`, y resumen de `tc_mobile_messages`: heartbeats/outbox) o, mínimo, dejar el Analyzer consumible en el server con el mismo contrato.
- Match: mantener el contrato actual `MatchResource.matchTracks` (tracks ya segmentados, `server/src/main/java/org/traccar/api/resource/MatchResource.java:109-178`): el corte pre-match es el que garantiza que Viterbi nunca case a través del hueco. **Prohibido usar el matcher para "rellenar" un gap.**

---

## 6. BUG DEMOSTRABLE vs MEJORA · RIESGOS · PLAN DE FASES

**Bugs demostrables (pipeline actual):**

| ID | Severidad | Evidencia |
|---|---|---|
| B-1 conectores de replay ignoran `shouldCut` | **P1** — recta de hasta 2941 m (joseph) / 1957 m (macias) pintada sobre hueco cuando el match está activo (la vista por defecto del replay con matcher vivo) | §2, `replayAudit.js:344-354` + `MapRouteMatch.jsx:85-100` |
| B-2 `syncDelayMs` mezcla UTC (serverReceivedAt) con local (fixTime) | **P1** — 100% de los fixes de los 5 dispositivos marcados `synced`+`clock`; delays reales (2 s…36 592 s) invisibles bajo el offset +18 000 s | §2, `replayAudit.js:72-79` |
| B-3 GAP del panel usa solo MAX_GAP_MS | P2 — panel contradice la línea (fast-gaps cortados no listados) | `replayAudit.js:215,249` |
| B-4 parada fantasma por outlier sin tope de distancia | P2 — "parada" de 4.6 min con extremos a 994 m (macias); alimenta rectas de B-1 | `pathDecimation.js:95-125,296-339` |
| B-5 banda ciega 45 s–300 s con dist 100–200 m | P3 — sin caso real hoy, pero ventana sin cobertura de regla | `shouldCut` :949-962 |

**Mejoras (no bugs de dibujo):** tipos de segmento con narrativa (DELIVERY/RECOVERY), señales de presencia/outbox en el dominio, indicadores visuales.

**Riesgos:** (1) datos históricos sin `serverReceivedAt`/eventos → el Analyzer degrada a UNKNOWN sin romper; (2) el offset por dispositivo debe derivarse (mediana), no asumirse −05; (3) rendimiento: análisis O(n) por par + índice de eventos por ventana — los ranges ya vienen ordenados; (4) contracto del matcher: NO reenviar tracks que crucen cortes (hoy ya se respeta; conservarlo); (5) no tocar RAW ni timestamps: el Analyzer es solo lectura sobre el array crudo (mismo principio de `replayAudit.js:1-12`).

**Plan de fases:**
- **Fase 0 (hotfix, sin modelo):** B-2 (usar `servertime−fixtime` u offset por dispositivo) y B-1 (conectores solo si `!shouldCut`) + B-4 (validar diámetro en `detectStops` como ya hace `runIsStationary`). Tests de regresión con los pares reales congelados.
- **Fase 1:** `RouteSegmentModel/Policy/Analyzer` puros + matriz A–G + unificar `offlinePeriods`/`flag GAP` sobre el Analyzer.
- **Fase 2:** API (eventos/heartbeats/outbox por ventana) y `integritySummary` por tipo.
- **Fase 3:** frontend/visual (indicadores DELIVERY/RECOVERY/CAPTURE) — el frontend dibuja, el dominio decide.

---

## RESUMEN FINAL

La línea principal YA corta los huecos reales (joseph 7 saltos hasta 4268 m/917 s; macias hasta 3278 m/277 s: `shouldCut` los detecta todos — 0 segmentos cruzan cortes, recta máxima dibujada 103 m). Lo que se pinta continuo es la **línea de replay**: `linkTracksFor` une piezas con rectas sin pasar por `shouldCut` (joseph 2941 m; macias 994/225/1957 m) y una **parada fantasma** (outlier doppler sin tope de distancia) agrega otra recta de 994 m. Además `syncDelayMs` resta `serverReceivedAt` (UTC) sobre `fixTime` (local −05): el 100% de los fixes queda marcado `synced`+`clock` con +18 000 s fantasma, enterrando los delays reales (2 s…36 592 s). Se propone `RouteSegmentAnalyzer` puro que conserva los umbrales medidos (100 m/45 s/200 m/300 s/teleport) y añade la narrativa por evidencia: CAPTURE_GAP (heartbeats dentro del hueco = GPS suspendido, joseph), RECOVERY_GAP (NetworkLost/Restored + recovery timeouts, macias), DELIVERY_DELAY (backfill en lote, Δservertime≈0), UNKNOWN (sin señales). Matriz A–G determinista incluida. Prohibido: tocar timestamps, map-matching para ocultar huecos, reglas por dispositivo.
