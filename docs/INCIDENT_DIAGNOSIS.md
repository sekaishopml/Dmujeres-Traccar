# INCIDENT_DIAGNOSIS.md — Diagnóstico forense automático (FASE 7/§20/§41)

## 1. Herramientas implementadas

| Herramienta | Fuente | Uso |
|---|---|---|
| Continuidad por jornada/sesión | `GET /api/devices/{id}/continuity` | duración, tracking, gaps con start/end |
| Snapshots de salud | `tc_device_health` (+ atributos `mobile.health*`) | proceso/FGS/GPS/red/motion/outbox por snapshot |
| Eventos de recovery | `tc_recovery_event` | intentos, etapas, resultado, causa |
| Timeline (dashboard) | página `/reports/dmujeres` | estado actual + continuidad por jornada |
| Diagnóstico móvil | `mobile.diagnostics` (caja negra) | contadores, última causa de red, errores de arranque |

## 2. Procedimiento ante un hueco (TRACKING_GAP)

1. **Medir**: `/api/devices/{id}/continuity?journeyId=…` (o `sessionId`) →
   `journeyTime/trackingTime/gapTime/continuityPct` + gap mayor con
   `startMs/endMs`.
2. **Última evidencia por capa** (atributos y snapshots):
   - último fix (`mobile.lastFixTime`, `lastFixAt`),
   - último ACK (`mobile.ackAt`/`lastAckAt`),
   - último heartbeat (`health_snapshots` / `mobile.lastPresenceAt`),
   - MQTT/red (`mobile.network`, `mobile.netCause`, `mobile.validated`),
   - outbox (`mobile.pending`, `mobile.quarantinedTotal`).
3. **Recovery**: `SELECT * FROM tc_recovery_event WHERE deviceid=? ORDER BY ts DESC`.
4. **Causa probable**: solo con evidencia. Reglas aplicadas:
   - gap + `RECOVERY_TIMEOUT` + freezer de fabricante conocido → `OEM_PROCESS_FREEZE`;
   - gap sin eventos de recovery + sin red → `NETWORK`;
   - captura sigue con red y MQTT → `OUTBOX`/`SERVER`;
   - sin datos concluyentes → `UNKNOWN` (nunca se inventa).

## 3. Caso real qa-f0 — diagnóstico reconstruido

```
TRACKING_GAP
  device: qa-f0 (ZTE Z2450, MyOS 14)
  journey (session): 11h26m32s (250 fixes)
  tracking:          1h01m56s
  gap:               10h24m35s
  hueco mayor:       09:45:48 → 20:15:24 (10h29m35s)
  last GPS:          09:45:48
  resume:            20:15:24
  outbox:            replay íntegro (sin pérdida; posiciones posteriores confirmadas)
  proceso:           sin callbacks FLP durante el hueco (health snapshots ausentes)
  recovery:          RECOVERY_ATTEMPT/SENT/TIMEOUT (Android/OEM no despertó)
  probable cause:    OEM_PROCESS_FREEZE (ZTE cfreezer, evidencia frozen-list
                     con unfreeze reason=screen_on)
  confidence:        HIGH (freezer confirmado + patrón de gap + recovery timeout)
```

Este diagnóstico hoy se obtiene sin intervención manual (continuidad +
atributos + recovery events + perfil OEM).

## 4. Interpretación de límites (no llamar NETWORK_FAILURE sin prueba)

- `no ACK` no implica red caída: puede ser outbox pendiente por rate-limit.
- `MQTT DISCONNECTED` no implica sin Internet (`mobile.validated`).
- `proceso congelado` no implica app muerta; el heartbeat local (Room) y los
  snapshots distinguen Doze/freezer de crash (arranque anormal + contador
  `crashes24h`).
