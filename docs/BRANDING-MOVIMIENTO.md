# Movimiento e interacción — DMujeres Tracking

Reglas de animación del panel web (`apps/web`). Complementa a
`docs/BRANDING.md`: los valores viven en `src/estilos/tokens.css` y aquí se
explica qué se anima, cuánto, con qué curva y qué queda quieto a propósito.

## 1. Principios

1. **El movimiento informa, no adorna**: confirma una aparición, un cambio de
   estado o una acción. Si no ayuda a leer la pantalla, no se anima.
2. **Densidad y prisa operativa**: las duraciones son cortas (120–260 ms) y
   nunca retrasan una acción. Nada espera a que termine una animación.
3. **Sobriedad**: solo opacidad, color y desplazamientos mínimos (≤ 4 px) o
   escalas casi imperceptibles (0.985). Sin rebotes, giros decorativos,
   latidos de sombra ni desplazamientos largos.
4. **Un token por decisión**: ninguna hoja ni componente inventa duraciones o
   curvas; se usan las variables `--dmj-mov-*` de `tokens.css`.

## 2. Tokens

Definidos en `src/estilos/tokens.css`:

| Token | Valor | Uso |
| --- | --- | --- |
| `--dmj-mov-rapida` | `120ms` | Hover, foco y cambios de color o fondo. |
| `--dmj-mov-media` | `180ms` | Aparición de componentes: diálogo, toast, panel. |
| `--dmj-mov-lenta` | `260ms` | Entrada de página y superficies grandes. |
| `--dmj-mov-curva` | `cubic-bezier(.2, .7, .3, 1)` | Estándar: cambios de estado (color, fondo, riel). |
| `--dmj-mov-curva-entrada` | `cubic-bezier(.16, .84, .44, 1)` | Entrada: arranca rápido y frena al final. |
| `--dmj-mov-curva-salida` | `cubic-bezier(.5, 0, .9, .4)` | Salida: acelera al retirarse. Reservada. |
| `--dmj-mov-distancia` | `4px` | Desplazamiento máximo de una entrada. |
| `--dmj-mov-escala` | `.985` | Escala inicial de diálogos y paneles flotantes. |

Las variables antiguas `--transicion-rapida`, `--transicion` y
`--transicion-lenta` se conservan como alias de las anteriores para no romper
las hojas que ya las usan; en código nuevo se usan las `--dmj-mov-*`.

## 3. Catálogo

| Qué | Gesto | Duración | Curva | Dónde |
| --- | --- | --- | --- | --- |
| Entrada de página | Fundido + 4 px desde abajo | 260 ms | entrada | `.contenido > *` (global.css) |
| Acceso (login) | Fundido + 4 px del formulario; fundido de la marca | 260 ms | entrada | `.login-caja`, `.login-marca > *` |
| Aparición de aviso o banner | Fundido + 4 px desde arriba | 180 ms | entrada | `.aviso`, `.bloque.fallo` |
| Diálogo | Fundido + 4 px + escala 0.985 | 180 ms | entrada | `.dialogo[open] .dialogo-caja` (admin.css) |
| Velo del diálogo | Fundido del fondo | 180 ms | entrada | `.dialogo[open]::backdrop` |
| Toast de confirmación | Fundido + 6 px desde abajo | 180 ms | entrada | `.toast` (admin.css) |
| Panel del Replay | Fundido + escala 0.985 desde su esquina | 180 ms | entrada | `.replay-panel` (operacion.css) |
| Leyenda del mapa | Fundido | 260 ms | entrada | `.replay-leyenda` |
| Franja de tiempo del Replay | Fundido | 180 ms | entrada | `.replay-timeline` |
| Cambio de capa base del mapa | Fundido de opacidad de capas raster | 180 ms | lineal (MapLibre) | `setPaintProperty` en MapaRaster.tsx |
| Pulso de carga | Opacidad 1 → .5 → 1 | 1.8 s, infinito | estándar | `.pulso` en `.vacio` de listas/tablas; texto de `Cargando` a pantalla completa |
| Riel lateral en móvil | Deslizamiento del menú | 180 ms | estándar | `.lateral` (global.css) |
| Filas de tabla | Transición de fondo al pasar el cursor | 120 ms | estándar | `table.tabla td` |
| Botones, enlaces, campos | Transición de color, fondo y borde | 120 ms | estándar | reglas base de global.css |
| Plegado de paradas del Replay | Giro de la flecha | 120 ms | estándar | `.replay-plegar-paradas .icono` |
| Detenciones del Replay | Fondo al pasar el cursor | 120 ms | estándar | `.replay-detenciones li` |

## 4. Entradas que no se repiten

Las entradas se implementan con `animation` al montar el nodo, nunca con
transiciones que dependen del estado. Las páginas se repintan con cada sondeo
(5–15 s) sin volver a insertar el nodo de la ruta, así que la animación de
entrada no se repite; lo mismo vale para tablas, paneles y avisos que
persisten entre consultas.

Regla práctica: si una entrada tuviera que escucharse de nuevo, se aplica a un
nodo que React monta de nuevo (por ejemplo, un toast), no a datos que cambian.

## 5. Mapa (MapLibre)

- El cambio de capa base (Mapa, Satélite, Híbrido, OpenStreetMap) funde por
  opacidad con `setPaintProperty` sobre las capas raster existentes. No se
  agregan, quitan ni reordenan capas: solo cambia su opacidad y visibilidad.
  La duración se lee del token `--dmj-mov-media`.
- La cámara del mapa (`easeTo`, `flyTo`, `fitBounds`) es navegación funcional
  ya existente: llevan a una unidad o parada y no forman parte del lenguaje de
  animación decorativa. No se agregan rebotes ni transiciones nuevas.
- Los datos vivos (marcadores, recorrido, batería) se actualizan sin
  animación de entrada: el movimiento sobre el mapa es el de la propia flota.

## 6. Accesibilidad

`global.css` incluye una regla global:

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 1ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 1ms !important;
    transition-delay: 0ms !important;
  }
}
```

Con movimiento reducido:

- Entradas, pulso de carga, giro de la pantalla de carga y transiciones de
  hover quedan instantáneos; toda la información y los estados siguen visibles.
- El fundido de capas del mapa se sustituye por un cambio directo: MapaRaster
  lee `prefers-reduced-motion` y usa duración 0 (además de leer la duración del
  token para no duplicarla).
- El foco de teclado no depende de ninguna animación.

## 7. Qué no se anima

- Salidas o cierres (diálogos, toasts, menú móvil al cerrar): retrasarían la
  acción siguiente.
- Anchuras y alturas de paneles al plegarse, contenido del panel del Replay y
  cambios de estado de botones (activo, seleccionado).
- Valores de datos: cifras, tablas, series y posiciones se actualizan al
  instante, sin interpolar.
- Sombras, degradados o cualquier propiedad que “lata”.
- Animaciones en bucle salvo el pulso de carga, que es informativo.
- Movimiento sobre el mapa que no sea el fundido de capa base.

## 8. Checklist antes de fusionar

1. ¿Las duraciones y curvas salen de `--dmj-mov-*`? ¿No hay valores sueltos?
2. ¿La animación se ejecuta al montar y no en cada sondeo o re-render?
3. ¿Dura 260 ms o menos y no retrasa ninguna acción?
4. ¿Solo usa opacidad, color, ≤ 4 px o escala ≥ 0.98?
5. ¿Con `prefers-reduced-motion: reduce` la pantalla queda utilizable y sin
   movimiento?
6. ¿`npm run build` (tsc + vite) pasa sin errores?
