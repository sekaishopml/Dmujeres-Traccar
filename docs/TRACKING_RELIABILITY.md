# TRACKING_RELIABILITY.md — Confiabilidad de tracking y continuidad (FASE 5)

## 1. Definiciones (no son sinónimos)

| Concepto | Definición | Fuente |
|---|---|---|
| `journeyTime` | último fix aceptado − primer fix aceptado de la jornada | posiciones reales |
| `trackingTime` | Σ min(dt, 5 min) entre fixes consecutivos | posiciones reales |
| `gapTime` | journeyTime − trackingTime | derivado |
| `continuity%` | trackingTime / journeyTime × 100 (denominador explícito) | derivado |
| Proceso vivo | evidencia local: heartbeat Room (5 min), callbacks FLP, fix aceptado | `health_snapshots` |
| Jornada abierta | `mobile.journeyId > 0` | atributo device |

**Un hueco es `dt > 5 min`** (`MobileContinuityPolicy.GAP_THRESHOLD_MS`).
Nunca se interpola: un gap no genera posiciones.

## 2. Fórmulas (implementadas, testeadas)

```
journeyTime   = lastFixTime - firstFixTime
trackingTime  = Σ min(fix[i] - fix[i-1], GAP_THRESHOLD_MS)
gapTime       = max(0, journeyTime - trackingTime)
continuityPct = journeyTime > 0 ? 100 × trackingTime / journeyTime : 0
dataIntegrity = NO se inventa: el servidor reporta solo lo medible
                (aceptadas vs cuarentena del cliente; ver limitaciones)
```

## 3. Caso real qa-f0 (ZTE, cfreezer confirmado)

Medido con el motor real contra la BD de producción
(sesión `531acd1c027e442d`, 250 fixes):

| Métrica | Valor | Prompt §42 |
|---|---|---|
| journeyTime | 11h26m32s | ≈11h27 ✓ |
| trackingTime | 1h01m56s | ≈1h02 ✓ |
| gapTime | 10h24m35s | grande ✓ |
| Hueco mayor | 10h29m35s (09:45:48 → 20:15:24) | ≈10h29 ✓ |
| continuity | 9.02% | — |
| outbox | replay 100% (sin pérdida) | ✓ |

El endpoint `GET /api/devices/47/continuity?sessionId=...` devuelve
exactamente esos números (verificado HTTP 200 con sesión de dashboard).

## 4. Outbox (sin cambios estructurales; F0)

```
GPS → persist (Room) → ACK → confirmed → DELETE
```
- Reintentos con backoff, `SequenceState`, `DeadLetter` (cuarentena),
  `DispatchLock`, retención dura (100 000 / 7 d), replay al volver red.
- Desconexión de 2 h no pierde posiciones (capacidad local > 2 h a 10 s).
- `BufferDrainPolicy` limita lotes por drenaje (anti hambruna + anti tormenta).

## 5. Métricas expuestas

| Métrica | Dónde | Denominador |
|---|---|---|
| continuity% | `/api/devices/{id}/continuity` | journeyTime |
| fixes aceptados | idem (summary.fixes) | — |
| gaps | idem (lista con start/end) | — |
| pendientes / ACK / retries / cuarentena | atributos `mobile.pending`, `mobile.ackTotal`, `mobile.retryTotal`, `mobile.quarantinedTotal` | acumulados reportados por el cliente |
| batteryHistory | atributo `mobile.batteryHistory` (24 muestras) | solo se interpreta junto a tracking activo |

## 6. Limitaciones honestas

- `dataIntegrity` global server-side requiere el conteo de generadas que solo
  el cliente conoce (atributo acumulado, no por jornada) → se reporta el
  contador de cuarentena y se evita un “100%” no medido.
- La continuidad usa el umbral de 5 min; con intervalos base mayores el
  umbral seguiría siendo 5 min (documentado; configurable en código).
- Un fix re-entregado por red se descarta en el servidor (`network_relay`) y no
  cuenta como hueco si hubo fixes reales en la ventana.
