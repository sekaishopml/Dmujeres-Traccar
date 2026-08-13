# Mediciones reales TimescaleDB — `tc_positions`

Fecha: 2026-08-13 · BD: contenedor `dmj-db` (compose dev) · TimescaleDB latest-pg17
Config: hipertable con compresión (`segment_by=deviceid`, `order_by=fixtime DESC`), política de compresión para chunks > 1 día (`job_id 1000`). Ver `timescale-compression.sql`.

## 1. Carga de datos

- Población previa: **27,394 filas** (27 dispositivos).
- Carga sintética: 10 dispositivos (deviceid 1..10), 1 punto/10 s durante 31 días (2026-07-15 → 2026-08-14), `protocol='load-test'`.
- 3 INSERTs (`generate_series` + `cross join`): 950,400 + 950,400 + 777,600 = **2,678,400 filas** en ~22 s totales (~120 k filas/s).
- Total tras carga: **2,705,794 filas** (38 chunks).

Nota de método: los chunks de julio ya estaban comprimidos por la política y TimescaleDB no admite inserts en chunks comprimidos; se descomprimieron antes de cargar y se recomprimieron después, lo que además permite medir el tamaño "antes" real.

## 2. Tamaño SIN compresión

`pg_total_relation_size` sobre la hipertable solo mide la tabla padre (32 kB); hay que sumar los 38 chunks.

| Métrica | Valor |
|---|---|
| Total (38 chunks, tabla + índices + TOAST) | **642 MB** (673,554,432 B) |
| Promedio por fila (total / count) | **248.9 B/fila** |
| Solo fila (`avg(pg_column_size)`) | 136.0 B |

## 3. Compresión

`compress_chunk(c, true)` sobre `show_chunks(older_than => INTERVAL '1 day')` → **24 chunks comprimidos** (~6 s de duración). Quedan 14 chunks sin comprimir (08-06 → 08-14, rango < 1 día según política; hoy 13/08).

Fuente: `hypertable_compression_stats('tc_positions')` (24 chunks comprimidos).

## 4. Tamaño CON compresión

| Métrica | Valor |
|---|---|
| Chunks comprimidos (24, 1,919,940 filas): companion tables | **92 MB** (92,094,464 B) |
| Chunks recientes sin comprimir (14, 785,854 filas) | 187 MB (195,887,104 B) |
| **Total real del sistema** | **~275 MB** (287,981,568 B) |
| Coste por fila comprimida (steady-state, incl. índices/TOAST) | **47.97 B/fila** |

Antes: 477,667,328 B → Después: 92,094,464 B

### Ratio de compresión

| Métrica | Valor |
|---|---|
| Ratio compresión pura (solo chunks comprimidos) | **5.19x** |
| % ahorro (compresión pura) | **80.7%** |
| Ratio sistema completo (con ventana reciente de 1 día descomprimida) | 2.34x |
| % ahorro (sistema completo) | 57.1% |

## 5. Benchmark de consulta (datos comprimidos)

`EXPLAIN (ANALYZE, BUFFERS)` — ruta de device 3 en un día (2026-07-20), 8,727 filas devueltas, chunk comprimido (ColumnarScan + índice meta de segmento `deviceid + fixtime`):

| Ejecución | Planning Time | Execution Time | Buffers |
|---|---|---|---|
| 1 (fría) | 10.056 ms | 2.337 ms | shared hit=133 |
| 2 | 9.862 ms | 2.373 ms | shared hit=133 |
| 3 (warm) | 0.366 ms | 4.430 ms | shared hit=108 |

**Execution ~2.3–4.4 ms** sobre datos comprimidos; el plan usa el índice de metadatos del segmento comprimido (deviceid + rango de fixtime) antes del escaneo columnar.

## 6. Proyección 5 años (60 meses)

Base: 47.97 B/fila comprimida (medido, incluye overhead de índices/TOAST). Filas/mes por dispositivo: @10s = 259,200 · @30s = 86,400 · @60s = 43,200.

- 10 dispositivos @10s ≈ 2.59 M filas/mes → **124.3 MB/mes comprimidos** → **7.5 GB / 5 años**.

| Dispositivos | @10 s | @30 s | @60 s |
|---|---|---|---|
| 10 | 7.5 GB | 2.5 GB | 1.2 GB |
| 50 | 37.3 GB | 12.4 GB | 6.2 GB |
| 100 | 74.6 GB | 24.9 GB | 12.4 GB |

Presupuesto 100 GB / 5 años → capacidad por intervalo: @10 s hasta **~134 dispositivos**, @30 s hasta ~402, @60 s hasta ~804.

### Recomendación

- **@10 s es viable para el objetivo (D-014: 100 GB / 5 años)** con hasta ~130 dispositivos (100 dispositivos ≈ 75% del presupuesto).
- Subir a **@30 s** si la flota supera ~130 dispositivos (soporta ~400); **@60 s** para >400.
- La ventana reciente (~1 día) queda descomprimida (~250 B/fila), impacto despreciable en el total (< 4% mensual).
- Inserción medida: ~120 k filas/s; compresión de 24 chunks: ~6 s.

## Notas

- La vista `timescaledb_information.compression_stats` no existe en esta versión; se usó `hypertable_compression_stats()` (stats por chunk no disponibles vía `chunk_compression_stats`).
- El tamaño de los chunks comprimidos vía `pg_total_relation_size` solo muestra la cáscara vacía (~32 kB/chunk); el dato real está en las tablas companion `_compressed` (incluidas en `after_compression_total_bytes`).
- No se modificó ningún archivo del proyecto salvo este; la BD quedó con los chunks > 1 día comprimidos (estado previo).
