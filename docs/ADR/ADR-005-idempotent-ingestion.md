# ADR-005 — Ingesta idempotente

Estado: **CERRADO** (2026-09-28).

## Contexto

Hoy `db.js:168` inserta cada fix sin dedupe: una retransmisión (timeout de 15 s
con POST aplicado) duplica la posición. `posicion_actual` además retrocede si
llega un paquete viejo (`db.js:191-222`). La identidad actual es solo
timestamp (prohibido por el encargo).

## Decisión

Identidad estable `(dispositivo_id, boot_id, local_sequence)`:
- Cliente: `boot_id` = UUID por proceso (persistido en `meta`); `local_sequence`
  = contador persistente que solo incrementa (no se reinicia en reboot).
- Servidor: columnas nuevas + índice único parcial
  `(dispositivo_id, registrado_en, boot_id, local_sequence) WHERE boot_id IS
  NOT NULL` (parcial para no romper históricos sin identidad; el
  `registrado_en` lo exige el particionado y no debilita el dedupe porque la
  retransmisión trae el mismo captured_at).
- Ingesta: `INSERT ... ON CONFLICT DO NOTHING RETURNING id` → clasifica
  `accepted | duplicate`; lote nuevo `/api/mobile/v1/positions`.
- Regla de posición viva: `dmt_posicion_actual` y `ultima_conexion_en` solo
  avanzan (`EXCLUDED.registrado_en > actual`), nunca retroceden.

## Alternativas descartadas

- UUID por evento: válido pero no permite orden ni detectar huecos de subida.
- Dedupe por hash de payload: costoso y frágil (campos variables).

## Consecuencias

- Reintentos seguros; métrica `duplicate` visible en logs.
- Migración `002_idempotencia_posiciones.sql` aditiva.
