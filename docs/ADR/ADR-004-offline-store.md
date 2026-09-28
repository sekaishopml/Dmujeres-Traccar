# ADR-004 — Almacén local durable + cola offline

Estado: **CERRADO** (2026-09-28).

## Contexto

Hoy: SQLite `position` con solo los campos del fix
(`DatabaseHelper.kt:64-79`), cap 5000 con descarte silencioso, subida serial
`read→send→delete` con retry fijo de 30 s sin clasificar HTTP
(`TrackingController.kt:281-357`, `RequestManager.kt`). Nada se pierde por
fallo de red (se borra solo tras 2xx), pero hay riesgo de bloqueo y de
pérdida silenciosa al llenar el buffer.

## Decisión

`GPS → LocalPositionStore → UploadQueue → HTTPS → servidor`. La captura
**nunca** depende de la red. El store gana `boot_id`, `local_sequence`,
`journey_id`, `provider`, `movement_state`, `status`, `attempts` y `meta`
(migración v5, aditiva). La cola clasifica respuestas (2xx/4xx/401/retry) y
usa backoff exponencial con jitter (base 5 s, ×2, tope 5 min). Wake lock solo
durante el envío con timeout de 60 s (ya implementado; se conserva). El cap de
5000 pasa a reportar `buffer_overflow` en diagnóstico.

## Alternativas descartadas

- Room: añade dependencias; SQLite crudo ya funciona y es mantenible.
- Envío en paralelo: complejidad sin beneficio para esta flota.
- Persistir solo en memoria: perdería fixes con recreación de proceso.

## Consecuencias

- La identidad estable es `(device_id, boot_id, local_sequence)`; ver ADR-005.
- IA-2 debe migrar sin DROP (hoy `onUpgrade` dropea: corregir).
