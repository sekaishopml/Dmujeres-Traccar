# Panel DMujeres Tracking — guía de construcción

Panel nuevo (`apps/panel`) que reemplaza a `apps/web`. Misma funcionalidad y
mismos endpoints `/api/v1`, diseño nuevo desde cero. Producto: **auditoría de
jornadas de personas** (no taxis ni vehículos): cada persona lleva un teléfono
con la app; se audita a qué hora inició jornada, cuándo salió de casa, cuándo
llegó a la oficina, cuándo se quedó sin batería, dónde retomó y si cargó el
teléfono, y cuándo cerró la jornada. Se mueve en carro, bus, moto o a pie.

## Stack
React 19 + Vite 8 + TypeScript estricto + Tailwind CSS 4 (tokens en
`src/estilos/index.css`, `@theme`) + TanStack Query 5 + React Router 7 +
MapLibre 6 + Chart.js 4 (`react-chartjs-2`) + iconos `lucide-react` + toasts
`sonner`. Alias `@/` → `src/`, `@contratos` → tipos compartidos de la API.

Compilar (usuario opencode, Node 24):
`su opencode -c "export PATH=/opt/node24/bin:\$PATH; cd /home/DMujeres-Tracking/apps/panel && npx tsc -b --noEmit"`

## Lenguaje visual
- Claro, aireado, empresarial. Fondo `bg-fondo`, tarjetas blancas
  `rounded-tarjeta` (16 px) con `shadow-tarjeta` y `border border-borde`.
- Texto y títulos en marino (`text-marino-900`), secundarios `text-texto-2`,
  terciarios `text-texto-3`. Títulos y cifras en Poppins (`font-display`).
- Magenta DMujeres (`marca`) solo para: acción principal, selección/foco y el
  elemento activo. No pintar grandes superficies de magenta.
- Estados (una paleta para chip, mapa y gráfico): `movimiento` (verde),
  `detenido` (azul), `sin-senal` (ámbar), `deshabilitado` (gris),
  `peligro` (rojo). Cada uno con `-suave` para fondos.
- Personas se muestran con `<Avatar nombre estado />` (iniciales), nunca fotos.
- Densidad cómoda: filas de tabla `py-3`, tarjetas `p-5`, separación entre
  bloques `gap-5`/`space-y-5`.
- Nada de datos inventados: campo ausente = `GUION` ("—") de
  `@/dominio/formatoBase`. Nada de adornos que no digan algo real.
- Responsive obligatorio: usable a 360 px de ancho, sin scroll horizontal de
  página (las tablas scrollean dentro de su tarjeta).

## Estructura
- `componentes/marco/Marco.tsx`: lateral + barra superior. El título y la
  descripción de la página salen de `componentes/marco/navegacion.ts`; **las
  páginas NO pintan su propio título**. Para poner filtros/acciones en la barra
  superior usar `<AccionesPagina>…</AccionesPagina>` (exportado por Marco).
- La página ocupa el área de contenido (ya tiene padding). Estructura típica:
  fila de `<Cifra>` (KPIs) → tarjetas con tablas/gráficos.
- `componentes/ui/`: `Boton`/`BotonIcono`/`claseBoton`, `Tarjeta`/
  `CabeceraTarjeta`, `Cifra` (KPI), `Chip`/`ChipEstado`/`Insignia`,
  `Campo`/`Entrada`/`Selector`/`AreaTexto`/`Casilla`, `Segmentado`,
  `Tabla`/`Th`/`Td`/`Fila`, `Dialogo`, `Vacio`/`Cargando`/`ErrorCarga`/
  `Esqueleto`, `Avatar`. Úsalos; si falta algo específico de tu página créalo
  dentro de `componentes/<tu-area>/`. **No modifiques archivos compartidos**
  (`componentes/ui`, `componentes/marco`, `dominio`, `lib`, `estilos`) — si
  necesitas un cambio ahí, dilo en tu informe final.
- `dominio/`: lógica ya probada del panel anterior, reutilizarla tal cual:
  `datos.ts` (fetchers y claves de caché), `estado.ts` (clave/etiqueta/color
  de estado), `formatoBase.ts` y `formato.ts` (fechas, duración, km, batería),
  `rango.ts`, `errores.ts` (mensajeError), `admin.ts` (tipos admin),
  `replay.ts`, `popup.ts`, y **`bitacora.ts`** (línea de tiempo de auditoría:
  `construirBitacora` + `resumenBitacora`).
- `componentes/mapa/MapaBase.tsx`: mapa raster (Satélite/Híbrido/OSM/Google).
- Toasts: `import { toast } from 'sonner'`.

## Funcionalidad
Replicar TODO lo que hace la página equivalente del panel anterior
(`apps/web/src/paginas/<Pagina>.tsx` y `apps/web/src/paginas/admin/*`): mismas
consultas, filtros, permisos (admin / solo lectura), validaciones, mensajes de
error y casos vacíos. Leer la página vieja entera antes de empezar. Mejorar la
presentación, no cambiar el contrato con la API.

## Código
Español en nombres y comentarios, como el resto del repo. Comentarios solo
donde expliquen un porqué. Componentes pequeños y legibles. Sin `any`.
