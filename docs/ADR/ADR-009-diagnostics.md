# ADR-009 — Diagnóstico de salud

Estado: **CERRADO** (2026-09-28).

## Contexto

La app ya reporta un latido de diagnóstico cada 10 min (versión, GPS, batería,
permisos, cadencia, buffer) y el servidor lo guarda en atributos
(`services/tracking/src/movil.js`). Pero el panel no deriva un estado de salud
con causa: el dueño ve "sin señal" sin explicación.

## Decisión

El servidor calcula y expone por equipo:
`last_fix_age`, `capture_gap`, `upload_lag`, `buffer_depth`,
`offline_duration`, cadencia esperada vs efectiva, y deriva
`HEALTHY | DEGRADED | OFFLINE | RECOVERING | MISCONFIGURED`. El panel muestra
el estado **con la causa** ("Último GPS hace 8 min", "Batería restringida",
"Permiso de fondo ausente"). La app añade `boot_id`, `recovery_count`,
`fgs_state`, `movement_state` y `buffer_overflow`.

## Alternativas descartadas

- Solo latido crudo sin derivación: obliga al operador a interpretar datos.
- Estado solo en la app: el operador necesita verlo desde el panel.

## Consecuencias

- IA-2: completar `ServiceHeartbeat` y añadir cálculo + vista en el panel.
- Los umbrales de derivación quedan configurables en servidor (no en la app).
