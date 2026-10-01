# Replay: paradas en la pista, microparadas y menos ruido (panel 1.3.0)

- Paradas fuera del gráfico de batería; ahora en la pista de tiempo (bloques numerados), microparadas (rayas) y cortes de señal (punteado), con globo al pasar y clic que selecciona y vuela el mapa.
- Franja ampliada: lectura con estado/parada arriba, gráfico batería + velocidad (eje derecho) a todo el ancho, mandos + pista + velocidades en una fila.
- Botón ampliar: el icono late al pasar el cursor y la franja insinúa el crecimiento (respeta prefers-reduced-motion).
- `dominio/depuracion.ts`: estancias vecinas se funden (centros ≤80 m, separación ≤6 min, excursión ≤130 m) y se recortan bordes en marcha (≥5 km/h). Quita la "estrella" de líneas en paradas largas.
- `dominio/replay.ts`: `microparadasDeRecorrido` (40 s–3 min, radio 20 m + precisión ≤15, mediana <3 km/h, sin silencios >2 min) y `duracionCorta`.
- Verificado con Manzaba 29/09: parada 18:05 en un solo bloque; microparadas 19:05 (44 s), 19:07 (1 min 5 s), 19:09 (52 s), 19:13 (1 min 24 s).
- Nota: `vite build` publica en vivo (dmj-panel sirve apps/panel/dist).
