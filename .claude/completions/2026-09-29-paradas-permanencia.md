# Paradas por permanencia y Batería compacta (panel 1.4.0)

- API: `services/api/src/paradas.js` (+ `test/paradas.test.mjs`) reemplaza la regla por velocidad en
  `/reports/stops` y en el conteo de `/reports/summary`. Parada = permanecer <= 60 m del centro >= 2 min;
  fixes > 80 m de precisión no abren parada; hueco > 30 min corta; paradas seguidas en el mismo sitio
  (<= 3 min entre ellas) se fusionan. Viajes siguen en `segmentos.js` (SQL).
- Casos reales: mantilla 29/09 21:00-21:03 ahora aparece; Fernando ya no tiene la parada falsa de 5 h
  (ubicación por antenas) ni la de 48 min (fixes cada 2 min con velocidad 0).
- Panel: `dominio/depuracion.ts` usa el mismo radio (60 m). `paginas/Bateria.tsx` pasa a lista compacta
  con búsqueda y filtros + detalle lateral.
- Pendiente (app, no hecho): velocidad 0.0 en todos los fixes de mantilla; Pilay y Alejandro reportan por
  OsmAnd (no usan la app DMujeres); Fernando sigue en 2.1.80.
