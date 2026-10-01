# 2026-10-01 — Paradas unidas a la línea, paradas de un mismo lugar e Inicio nuevo (panel 1.15.0)

- `componentes/replay/trazo.ts`: `segmentosParaDibujar` (pipeline de dibujo fuera del componente, verificable con jiti), `cortarTramosEnParadas`, `conectarParadas` (desde el centro), `recortarEnParadas`, `suavizarCaminata`, color por hora. `flechas.ts`: primera flecha a 14 m visible a todo zoom; conexiones dentro de las líneas.
- `services/api/src/paradas.js`: `ultimoVisto` (fixes aproximados cercanos sostienen la estancia). 28/28 pruebas.
- Ficha desplegable de parada (`InsigniasParadas`), área de toque ±12 px, sin leyenda de hora.
- Ícono: `public/favicon.png` y `marca/labios-{negro,blanco}.png`; **se tocó `componentes/marco/Logo.tsx` y `Marco.tsx`**.
- `paginas/Inicio.tsx` rediseñado (cifras en línea, tabla compacta propia, Para revisar con jornada >16 h y app desactualizada, Últimas actividades).
