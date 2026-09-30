# 2026-09-30 — App 2.4.0: tablets en vertical y horizontal

- `Responsivo.kt`: tablet = smallestScreenWidthDp >= 600.
  - Orientación (MainApplication, ActivityLifecycleCallbacks): teléfono fijo en vertical; tablet libre.
  - `centrar` / `centrarHijos`: margen interno lateral para limitar el ancho del contenido (600 dp columnas, 760 dp paneles, 1100 dp dos columnas). Cabeceras y pies conservan su fondo a todo el ancho.
- Pantalla principal:
  - Tablet vertical: layout de teléfono con contenido y pie centrados; banner alineado.
  - Tablet horizontal: `layout-sw600dp-land/activity_locked_home.xml`, dos columnas (logo + estado | indicadores + jornada + actualizar), pie marino en una fila (cronograma + versión), consola arriba a la derecha. Mismos ids que el de teléfono.
- Cronograma y consola: ancho de panel. Asistente, login, depuración y actualización: columna.
- Giro sin perder estado: el cronograma guarda vista (mes/día), día y mes; el asistente guarda el paso.
- No verificado en dispositivo: el único emulador del servidor es Android 2.3 ARM sin KVM.
