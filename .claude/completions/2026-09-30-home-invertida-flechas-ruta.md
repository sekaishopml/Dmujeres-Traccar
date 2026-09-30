# 2026-09-30 — Home invertida, arranque con carga, reportes por persona, flechas de ruta

## App 2.3.2
- Pantalla principal: Iniciar/Finalizar jornada y Actualizar en el contenido (arriba); el pie azul marino lleva el cronograma de actividades (resumen + "Sincronizado a las HH:MM") y la versión al fondo. Logo más abajo (60dp) y la píldora "En línea" más separada (56dp).
- Arranque: círculo de carga (ProgressBar indeterminado, color marca) abajo, sobre la versión.
- Actualización: logo más arriba (pesos de espaciado 0.55 / 1.45).
- Nuevo drawable `ds_footer_touch` (zona tocable translúcida dentro del pie).

## Panel 1.8.0
- Reportes (Cronograma y Recorridos): sin "Todas las personas"; lista alfabética y la primera persona por defecto. Se quitó el contador de personas del cronograma y `PlanillasEquipo`.
- Repetición de ruta (`componentes/replay/flechas.ts`):
  - Flechas sobre la línea dibujada (GPS o ajustada a calles), no sobre el fix crudo: el rumbo sale de ±15 m a lo largo del trazo, así no hay flechas locas por la deriva del GPS.
  - Zoom progresivo anidado: una cada 1,28 km a z12, duplicando por nivel hasta una cada 10 m a z19 (filtro `['<=', ['get','n'], ['zoom']]`).
  - Cada flecha lleva su hora de paso (`t`). El clic (flecha o línea) toma la flecha más cercana en ≤24 px, elige el fix más próximo en el tiempo, pone el aro sobre la línea, centra el mapa (easeTo) y abre un globo con fecha, hora y batería. El globo solo aparece tras un clic.

## App 2.3.3
- Logo más abajo (84dp) y píldora de estado más separada (72dp).
- Iniciar/Finalizar jornada y Actualizar justo debajo de los indicadores (el bloque ya no ocupa todo el alto); el espacio libre queda entre los botones y el pie marino. Se quitaron las barras de carga de los botones antiguos para que no salten al cargar.
