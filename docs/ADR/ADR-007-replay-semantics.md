# ADR-007 — Semántica del Replay

Estado: **CERRADO** (2026-09-28). **Revoca la decisión estética previa**
("que el tramo estimado parezca ruta normal", 2026-09-27).

## Contexto

El Replay actual dibuja los tramos resueltos por calles (`estimados` del API)
con la misma línea que el GPS real (`BANDA_ESTIMADA` en
`apps/web/src/paginas/operacion/replay.ts`). La regla de oro del proyecto
prohíbe presentar un tramo reconstruido como GPS registrado.

## Decisión

Tres capas explícitas y rotuladas:
- **REAL**: fixes registrados; línea sólida coloreada por velocidad + chevrones.
- **MATCHED**: hueco con observaciones suficientes, ajustado a vía por map
  matching; línea continua fina con patrón propio + etiqueta "ajustado a vía".
- **ESTIMATED**: hueco sin observaciones, ruta A→B; punteado gris + etiqueta
  "tramo estimado".

El API renombra `estimados` → `reconstruidos` con `metodo: 'MATCHED' |
'ESTIMATED'` y `mapaVersion`/`algVersion`. La leyenda del mapa los explica.

## Alternativas descartadas

- Mantener el estilo "ruta normal": incumple auditabilidad.
- Ocultar los tramos estimados: pierde contexto visual útil.

## Consecuencias

- IA-2 cambia `ruteo.js` (contrato), `replay.ts` (tipos/dibujo) y la leyenda.
- El CSV y las posiciones registradas no cambian (siguen siendo solo reales).
