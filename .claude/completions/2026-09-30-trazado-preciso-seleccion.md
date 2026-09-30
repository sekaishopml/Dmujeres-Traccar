# 2026-09-30 — Trazado solo con GPS preciso y selección sobre la línea (panel 1.9.0)

## Servidor (services/api, reiniciado)
- `ruteo.js`: `PRECISION_MAX_TRAZO_M = 50` y `esPrecisoParaTrazo`. `reconstruirTramos` trabaja solo con fixes precisos: los de antena/wifi (90–270 m, Manzaba 29/09) ya no entran al /match ni a las estimaciones (causaban colas y picos). El crudo no cambia. Prueba nueva `test/precision-trazo.test.mjs` (24 pasan).

## Panel
- `componentes/replay/flechas.ts` reescrito como "trazado con hora":
  - `sinPicos`: quita del ajuste a calles las idas y vueltas cortas (giro >=150° con lado <35 m). Era el "inicio de la línea" de Manzaba que no se dejaba elegir.
  - `lineasDeRecorrido`: líneas con hora por vértice; en tramos MATCHED la hora sale de proyectar cada fix sobre la línea (si estuvo quieta, la hora se acumula ahí).
  - `puntoEnLineas(lineas, t)`: posición sobre la línea en un instante.
  - `flechasDeLineas`: flecha cada 10 m desde 5 m del inicio (el arranque también se elige), niveles de zoom anidados. Sin excluir paradas: todo lo dibujado se puede elegir.
- `paginas/Replay.tsx`: la línea se arma solo con fixes precisos; los aproximados se dibujan como círculos huecos (capa `replay-aproximado`, clicables).
- `ReproductorReplay.tsx`: marcador, halo, globo y centrado usan la misma posición sobre la línea (antes el marcador iba al fix crudo y el aro al clic: dos círculos, uno rojo). El aro rojo pasó a halo marino bajo el marcador. Centrado en la zona visible (descuenta panel lateral y franja). El globo agrega "Ubicación aproximada ±N m" en fixes imprecisos.
