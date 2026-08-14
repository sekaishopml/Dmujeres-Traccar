# Dashboard — Informe de rendimiento (Fase 4)

Optimizaciones aplicadas a traccar-web v6.14.5 **sin cambiar el diseño ni la esencia**.
Medido el 2026-08-12 sobre el VPS dev, mismo navegador/servidor.

## 1. División de vendors (Vite `manualChunks`)

Se separaron las librerías pesadas en chunks estables (mejor caché y carga en paralelo
HTTP/2). El diseño de las pantallas no cambió: solo la organización de archivos del build.

| Métrica | Antes | Después |
|---|---|---|
| Chunk de carga inicial `index` | **1.6 MB** | **124 KB** |
| Total JS | 7.0 MB | 6.8 MB |
| Nº de chunks | 227 | 158 |
| Chunk `reports` (exceljs) | 912 KB (en ruta lazy) | igual (solo rutas de reportes) |
| Chunk `charts` (recharts) | 316 KB (lazy) | igual |

La carga inicial ya no descarga un monolito de 1.6 MB: el navegador pide en paralelo
`index` (124 KB) + `react-vendor` (448 KB) + `mui` (428 KB) + `map` (1.1 MB) + `vendor`.

## 2. Compresión gzip en el servidor

Traccar upstream incluía un `CompressionHandler` de Jetty pero **sin configuración y como
hermano del servlet en la secuencia (inerte)**. Se configuró para envolver el contexto y
comprimir HTML/CSS/JS/JSON/SVG con gzip.

| Transferencia | Sin comprimir | Con gzip | Reducción |
|---|---|---|---|
| JS+CSS del build | 6631 KB | **2028 KB** | **69 %** |
| `index.js` (124 KB) | 123 KB | 37 KB | 69 % |

El WebSocket y la API no se ven afectados (tests de regresión 10/10 + 10/10).

## 3. MapProvider (abstracción, sin rediseño)

- Nuevo módulo `src/map/provider/MapProvider.js`: API única para elegir proveedor, con
  default **OpenFreeMap** (gratuito, sin key) y fallback seguro.
- El dashboard sigue usando el mismo selector de mapas y los mismos proveedores.
- Decisión del cliente (D-015): Google Maps se ofrece siempre — con API key si existe, y
  sin key con los tiles clásicos `mt0-3.google.com/vt/...` (uso heredado del Traccar
  original). Google Carreteras es el mapa por defecto. Riesgo documentado en DECISIONS.md.
- Se eliminaron API keys hardcodeadas de LocationIQ/OrdnanceSurvey: esos proveedores solo
  se ofrecen si la key está configurada en los ajustes del server.

## 4. Conservado (ya estaba bien)

- Lazy loading de todas las rutas y de los 61 idiomas.
- Clustering nativo de MapLibre y virtualización de la lista de dispositivos.
- `throttleMiddleware` adaptativo para WebSocket (buffer 3 msg/s → flush 1.5–30 s).

## Resultado

La primera pantalla carga significativamente menos y en paralelo, y toda la transferencia
se reduce un 69 % con gzip. No se cambió ningún flujo visual ni funcional del dashboard
original.
