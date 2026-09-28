# ADR-003 — AlarmManager solo para recuperación

Estado: **CERRADO** (2026-09-28).

## Contexto

En el caso Pilay (27/09) el `Handler` del servicio quedó congelado por
Doze/reinicio y la app mantuvo cadencia lenta durante un viaje completo. El
watchdog actual (`TrackingController`, `LocationWatchdog`) corre en un
`Handler` que Doze suspende.

## Decisión

`AlarmManager.setAndAllowWhileIdle()` cada **9 minutos** es el mecanismo de
**rescate**: despierta para pedir un fix fresco y vaciar la cola. **No es el
reloj del tracking** y no se construye ningún SLO de 5 min que dependa de
ella. La cadencia real la define la captura continua (Fused + FGS).

## Alternativas descartadas

- Alarmas de 1-5 min: el sistema las limita (~9 min) igualmente.
- `setExactAndAllowWhileIdle`: requiere permiso especial y no aporta aquí.
- Wake lock permanente: descartado por consumo (834 mAh/24 h medidos antes).

## Consecuencias

- SLO honesto: con cobertura, ningún hueco mayor a 11 min; los huecos de 5-11
  min quedan explicados en diagnóstico.
- VALIDAR EN FASE DE TESTING: intervalo efectivo por OEM (Infinix/Xiaomi).
