# Identidad de marca — DMujeres Tracking

Guía para el panel web (`apps/web`). Todo cambio visual debe respetar estas
reglas; los valores viven en `src/estilos/tokens.css` y no se repiten a mano en
las hojas de estilo.

## 1. Principios

1. **Sobriedad corporativa**: herramienta de trabajo para jornadas completas.
   Sin degradados de marca, sin adornos ni efectos llamativos.
2. **Densidad útil**: información primero; bordes finos, tipografía pequeña,
   tablas y paneles compactos.
3. **Un solo acento**: navy institucional + verde operativo. El rojo nunca es
   marca: se reserva a peligro y error.
4. **Consistencia**: un token para cada decisión; nada de valores sueltos.

## 2. Paleta

### Marca

| Token | Hex | Uso |
| --- | --- | --- |
| `--navy` | `#0b1f33` | Color institucional: barra lateral, titulares, bases oscuras. |
| `--navy-2` | `#14365c` | Hover de superficies navy y enlaces. |
| `--acento` | `#1f7a4d` | Verde operativo: acción principal, estado activo, realces. |
| `--acento-2` | `#17623d` | Hover de la acción principal. |
| `--acento-claro` | `#5ecf9b` | Texto de acento sobre navy (sidebar, acceso). |
| `--acento-suave` | `#e7f3ed` | Fondo de realce verde muy suave. |

### Neutros

| Token | Hex | Uso |
| --- | --- | --- |
| `--fondo` | `#f4f6f8` | Fondo general del panel. |
| `--tarjeta` | `#ffffff` | Tarjetas, tablas y paneles. |
| `--texto` | `#1b2733` | Texto principal. |
| `--apagado` | `#5d6b7a` | Texto secundario y etiquetas. |
| `--gris` | `#5f6b78` | Estados deshabilitados y puntos neutros. |
| `--linea` | `#e3e8ee` | Bordes y separadores estándar. |
| `--linea-2` | `#eef2f6` | Bordes suaves internos. |
| `--linea-3` | `#ccd6e0` | Bordes marcados y paneles flotantes. |

### Semánticos de estado

Cada estado tiene color base (texto/punto) y tinte de fondo para chips.

| Estado | Base | Tinte | Uso |
| --- | --- | --- | --- |
| Éxito | `--exito` `#2f7d32` | `--exito-suave` `#e9f4ea` | En línea, activo, operativo. |
| Alerta | `--alerta` `#a85a00` | `--alerta-suave` `#fdf1e3` | Sin señal, señal débil, avisos. |
| Peligro | `--peligro` `#c4003b` | `--peligro-suave` `#fdeaef` | Error, batería baja, borrar. |
| Información | `--info` `#0b6fa4` | `--info-suave` `#e8f3f9` | Detenido, datos en reposo. |

Reglas de color:

- El acento verde es escaso: acción principal, elemento activo y marca. No se
  usa como fondo de bloques grandes ni como decoración.
- Rojo solo para lo que exige acción o destruye. Un texto rojo informativo es
  un error de uso.
- Texto principal y secundario deben cumplir AA (≥ 4.5:1) sobre su fondo.
- Para estados "fin de recorrido" se mantiene la convención verde inicio /
  rojo fin, ya asumida por el mapa y la cronología.

## 3. Tipografía

- **Familia**: stack del sistema (`--fuente`), sin webfonts ni dependencias.
  Monospace (`--fuente-mono`) solo para hashes, coordenadas e identificadores.
- **Escala**: `--tamano-xs` 11, `--tamano-sm` 12.5, `--tamano-base` 13.5,
  `--tamano-md` 15, `--tamano-lg` 17, `--tamano-xl` 22, `--tamano-2xl` 27 (px).
- **Pesos**: 400 normal, `--peso-medio` 550 (navegación), `--peso-fuerte` 650
  (títulos de sección y botones), `--peso-titulo` 700 (titulares y cifras).
- **Interlinea**: `--interlinea` 1.45; cifras con `font-variant-numeric:
  tabular-nums`.
- Nada de mayúsculas completas fuera de sobrelíneas y etiquetas; la voz de la
  interfaz va en español, frase normal.

## 4. Marca gráfica

- **Wordmark**: "DMujeres Tracking". "DMujeres" en `--peso-titulo` y color
  institucional; "Tracking" en `--peso-fuerte` y verde operativo (blanco y
  `--acento-claro` sobre fondos navy). Componente: `src/componentes/Logotipo.tsx`.
- **Monograma**: tile redondeado navy con una ruta de tres tramos y dos nodos
  (inicio verde, fin blanco). Misma geometría en `public/favicon.svg`,
  `public/favicon.png` y `public/logo.png`.
- **Variantes**: `claro` (fondos navy), `compacto` (solo monograma, riel
  plegado), `grande` (acceso y carga).
- **Espacio libre**: mínimo el ancho del nodo del monograma alrededor de la
  marca. Tamaño mínimo del wordmark: 13.5 px de alto tipográfico.
- **Prohibido**: recolorear el monograma, deformarlo, añadirle sombras, brillos
  o degradados, poner el wordmark sobre fondos de bajo contraste o usar
  logotipos antiguos (`logo antiguo`, `hero.png`, clip-arts, emojis).

## 5. Espaciado y superficies

- **Espaciado**: escala base de 4 px (`--espacio-1` … `--espacio-8`).
  Contenido estándar 16 px, tarjetas 12–14 px, huecos de rejilla 12 px.
- **Radios**: `--radio-xs` 4 (detalles), `--radio-sm` 6 (controles pequeños),
  `--radio` 8 (controles/tarjetas), `--radio-md` 10 (acceso/paneles),
  `--radio-lg` 12 (primer nivel), `--radio-pill` 999 (chips).
- **Sombras**: `--sombra-1` reposo, `--sombra-2` flotantes y diálogos,
  `--sombra-3` presentaciones. Máximo una sombra por elemento; nunca
  decorativas.
- **Transiciones**: `--transicion-rapida` 120 ms (hover), `--transicion`
  160 ms (fondos/color), `--transicion-lenta` 240 ms (capas). Sin rebotes ni
  animaciones largas.
- **Foco**: anillo verde (`:focus-visible`, `--acento`) siempre visible con
  teclado.

## 6. Iconografía

- SVG propio en `src/componentes/Icono.tsx`: trazo 1.7, viewBox 24, color
  `currentColor`, esquinas redondeadas.
- Un icono por concepto, sin reutilizar el mismo para acciones distintas.
- Tamaños: 17 px por defecto, 20 px en la navegación, 22 px en estados vacíos.
- Nunca emojis, capturas ni iconos de terceros.

## 7. Voz visual

- Española, directa y técnica: "Sin señal", "Batería baja", "Salir".
- Sin exclamaciones, sin humor, sin emojis, sin promesas comerciales.
- Los textos funcionales de las páginas no se cambian por motivos estéticos.

## 8. Qué evitar

- Degradados o brillos como recurso de marca.
- Un segundo acento fuerte compitiendo con el verde operativo.
- Rojo fuera de peligro/error, o verde de acento en grandes superficies.
- Chips, botones o badges con más de un color de acento a la vez.
- Radios tipo burbuja (> 12 px) salvo chips; sombras difusas grandes.
- Texto por debajo de 11 px, contraste por debajo de AA, grises sobre grises.
- Logos rasterizados viejos, clip-arts genéricos, emojis, marcos decorativos.

## 9. Checklist antes de fusionar un cambio visual

1. ¿Todos los colores salen de `tokens.css`? ¿Ningún hex suelto nuevo?
2. ¿El acento verde se usa solo donde corresponde (acción/activo/marca)?
3. ¿Los textos nuevos cumplen AA sobre su fondo?
4. ¿Los iconos son SVG propios de trazo 1.7?
5. ¿Se respetan los estados de foco con teclado?
6. ¿`npm run build` (tsc + vite) pasa sin errores?
