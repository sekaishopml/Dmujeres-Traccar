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
- **Escala**: `--tamano-xs` 11, `--tamano-etiqueta` 10.5, `--tamano-sm` 12.5,
  `--tamano-base` 13.5, `--tamano-seccion` 14, `--tamano-md` 15, `--tamano-lg`
  17, `--tamano-titulo` 20, `--tamano-dato` 23, `--tamano-xl` 22, `--tamano-2xl`
  27 (px). Los escalones del dash se detallan en la sección 6.
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
  Contenido estándar 16 px (el Replay lo anula con margen negativo), hojas
  12–14 px, entre secciones 22 px, filas de tabla 7 px.
- **Radios**: `--radio-xs` 3 (superficies del dash), `--radio-sm` 5
  (controles), `--radio` 6 (hojas y diálogos), `--radio-md` 8 (paneles
  flotantes), `--radio-lg` 10 (primer nivel del acceso), `--radio-pill` 999
  (solo chip de cuenta y filtros contados).
- **Sombras**: en reposo no se proyecta sombra (`--sombra-1` es apenas un
  filo); `--sombra-2` solo para diálogos, toasts y menús flotantes;
  `--sombra-3` para presentaciones. Nunca decorativas.
- **Movimiento**: lenguaje único en `tokens.css` (`--dmj-mov-rapida` 120 ms,
  `--dmj-mov-media` 180 ms, `--dmj-mov-lenta` 260 ms; curvas estándar, de
  entrada y de salida). Sin rebotes, giros decorativos ni animaciones largas.
  Catálogo, reglas de qué se anima y degradación con `prefers-reduced-motion`
  en `docs/BRANDING-MOVIMIENTO.md`.
- **Foco**: anillo verde (`:focus-visible`, `--acento`) siempre visible con
  teclado.
- **Estructura**: lateral 236 px (riel 64), barra superior 48 px
  (`--alto-barra`). El relleno de `.contenido` es exactamente 16 px porque el
  Replay a pantalla completa lo compensa con `margin: -16px`.

## 6. Lenguaje del dash operativo (estilo editorial)

El panel es una herramienta de operación diaria: su estética es editorial, no
de landing. Estas reglas existen para que no vuelva el estilo de "tarjetas
idénticas en rejilla" que se percibía como hecho por una IA.

### 6.1 Superficies y secciones

- Las tablas y las listas viven **directamente sobre el fondo** (`--fondo`),
  separadas por líneas finas; no se envuelven en tarjetas.
- `.bloque` es una **hoja**: superficie blanca, filo de 1 px y radio
  `--radio-xs`. Se reserva a agrupaciones reales (formularios, fichas de
  detalle, expedientes, paneles laterales, gráfico de Batería).
- Cada sección (`section.seccion`) lleva una cabecera `.cabecera-seccion`:
  título, cuenta a la derecha, acciones al extremo y una regla con segmento
  navy de 30 px. No hay una caja por sección.
- Prohibido: anidar tarjetas dentro de tarjetas, repetir el mismo bloque
  redondeado por toda la página, decorar con degradados.

### 6.2 Jerarquía tipográfica

Cuatro escalones que no se mezclan, con tokens con nombre:

| Escalón | Token | Valor | Componente |
| --- | --- | --- | --- |
| Título de página | `--tamano-titulo` | 20 px / 700 | `EncabezadoPagina` |
| Título de sección | `--tamano-seccion` | 14 px / 650 | `CabeceraSeccion` |
| Dato | `--tamano-dato` | 23 px / 700 | tiras de cifras y métricas |
| Etiqueta | `--tamano-etiqueta` | 10.5 px / 700 | versalitas de dato y cabeceras de tabla |

- Toda cifra va con `font-variant-numeric: tabular-nums`.
- El encabezado de página lleva sobrelínea de contexto (OPERACIÓN,
  ADMINISTRACIÓN), título y línea de detalle, cerrado con regla.
- Columnas de números a la derecha (`.num`); la etiqueta va sobre el dato,
  nunca al lado compitiendo en peso.

### 6.3 Ritmo, radios y sombras

- Espaciado base de 4 px; 22 px entre secciones, 7 px verticales en tablas,
  10 px en tiras de cifras. No se usa un `gap` único para todo.
- Radios contenidos (sección 5). Píldora solo en el chip de cuenta y filtros
  contados.
- En reposo no hay sombra: la jerarquía la hacen las líneas.

### 6.4 Estados

- Vacío o error: `EstadoVacio`, una línea con icono pequeño y el motivo.
  Nunca una caja grande y hueca.
- Chips de estado: punto de color y texto en versalitas, **sin fondo**; el
  fondo por chip multiplicaba las cajas y competía con los datos. El color
  sigue siendo señal (verde en línea, ámbar sin señal, rojo batería/error,
  azul detenido).

### 6.5 Barra superior

- Migas de ubicación (grupo / página), accesos de operación agrupados con
  separadores verticales, chip de cuenta con avatar, nombre y rol, y Salir.
  La cuenta y la salida viven aquí, no en la lateral.
- Altura 48 px (`--alto-barra`), sin sombra: solo el filo inferior.
- La lateral conserva el navy institucional; el riel de 64 px retira lemas,
  grupos y etiquetas en vez de recortarlos.

### 6.6 Componentes compartidos

Antes de escribir una cabecera o un vacío, usar:

- `componentes/EncabezadoPagina.tsx` — cabecera de página.
- `componentes/CabeceraSeccion.tsx` — cabecera de sección con cuenta y acciones.
- `componentes/EstadoVacio.tsx` — vacío o error en una línea.

Si aparece un tercer uso del mismo bloque de JSX, se extrae a un componente;
no se copia.

### 6.7 Qué no hacer (anti-patrón IA)

- Rejillas de tarjetas idénticas con el mismo peso visual.
- Bordes de color arbitrarios o gradientes decorativos.
- Microcopy genérico ("Todo listo") en vez de decir qué falta y por qué.
- Radios burbuja y sombras difusas en reposo.
- Iconos decorativos en cada fila y sobrelíneas en todas las secciones.

## 7. Iconografía

- SVG propio en `src/componentes/Icono.tsx`: trazo 1.7, viewBox 24, color
  `currentColor`, esquinas redondeadas.
- Un icono por concepto, sin reutilizar el mismo para acciones distintas.
- Tamaños: 17 px por defecto, 19 px en la navegación, 16 px en estados vacíos.
- Nunca emojis, capturas ni iconos de terceros.

## 8. Voz visual

- Española, directa y técnica: "Sin señal", "Batería baja", "Salir".
- Sin exclamaciones, sin humor, sin emojis, sin promesas comerciales.
- Los textos funcionales de las páginas no se cambian por motivos estéticos.

## 9. Qué evitar

- Degradados o brillos como recurso de marca.
- Un segundo acento fuerte compitiendo con el verde operativo.
- Rojo fuera de peligro/error, o verde de acento en grandes superficies.
- Chips, botones o badges con más de un color de acento a la vez.
- Radios tipo burbuja (> 12 px) o sombras difusas en reposo.
- Texto por debajo de 11 px salvo etiquetas de 10.5 px en versalita; contraste
  por debajo de AA, grises sobre grises.
- Logos rasterizados viejos, clip-arts genéricos, emojis, marcos decorativos.

## 10. Checklist antes de fusionar un cambio visual

1. ¿Todos los colores salen de `tokens.css`? ¿Ningún hex suelto nuevo?
2. ¿El acento verde se usa solo donde corresponde (acción/activo/marca)?
3. ¿Los textos nuevos cumplen AA sobre su fondo?
4. ¿Las tablas y listas van sobre el fondo y las secciones usan
   `CabeceraSeccion` / `EncabezadoPagina` / `EstadoVacio`?
5. ¿Los iconos son SVG propios de trazo 1.7?
6. ¿Se respetan los estados de foco con teclado?
7. ¿`npm run build` (tsc + vite) pasa sin errores?
