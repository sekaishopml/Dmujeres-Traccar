# ADR-010 — Particionado y retención en PostgreSQL

Estado: **CERRADO** (2026-09-28).

## Contexto

`dmt_posicion` y `dmt_evento` están particionadas por mes; el servicio crea la
partición al escribir (`services/tracking/src/db.js` `#asegurarParticion`).
En producción existe `dmt_posicion_2037_10` creada por un fix con reloj
absurdo: evidencia de que faltan validaciones y de que el esquema crece sin
control. No hay retención ni precreación proactiva.

## Decisión

- Se mantiene **PostgreSQL 18.6 + TimescaleDB**. **No se añade PostGIS** (sin
  consultas espaciales que lo justifiquen).
- Particiones: precrear mes actual + 2 siguientes al arrancar el servicio y
  con un job diario; la creación on-write se conserva como red de seguridad.
- Validación: `captured_at` fuera de [ahora−30 d, ahora+24 h] → inválida.
- Retención: por defecto conservar histórico completo (decisión del dueño);
  el job diario reporta tamaño por partición.
- Mantenimiento: `ANALYZE` semanal y monitoreo de tamaño/estadísticas en
  `/api/v1/sistema`.

## Alternativas descartadas

- TimescaleDB hypertables para posiciones: el particionado nativo ya cumple.
- Borrar la partición 2037_10 sin más: primero validar que no tiene filas
  útiles (tiene 1 fix de reloj errado; se purga en la migración 002).

## Consecuencias

- IA-2: migración de validación + job de particiones/retención + panel.
