# Geocodificacion inversa (Nominatim)

Fecha: 2026-09-26
Alcance: direccion de puntos en `services/api` para replay, reportes y Web nueva,
contra la base nueva `dmt-db` / `dmujeres`.
Produccion **no modificada** (contenedor `dmj-db`/`traccar`, `dmj-traccar`,
`dmj-match` intactos). Sin commits.

---

## 1. Resumen

| Elemento | Valor |
|---|---|
| Proveedor | Nominatim (OpenStreetMap), endpoint `reverse` |
| Ruta nueva | `GET /api/v1/geocode/reverse?lat=&lon=` (25/25 del OpenAPI) |
| Respuesta | `200 {"direccion": "..."}` o `200 {"direccion": null}` |
| Validacion | `400 DATOS_INVALIDOS` si faltan, no son numeros o estan fuera de rango |
| Cache | `Map` en memoria, clave `lat/lon` con 5 decimales, TTL 24 h, tope 5000 (FIFO) |
| Ritmo | maximo 1 peticion por segundo a Nominatim |
| Tiempo limite | 4 s por peticion con `AbortController` (la ruta no pasa de ~5 s) |
| Sin red | `direccion: null`; nunca lanza ni devuelve 5xx por el geocodificador |
| Modulo | `services/api/src/geocodigo.js` (nuevo) |
| Smoke | 51 PASS / 0 FAIL (49 previos + 2 nuevos) |

---

## 2. Como funciona

`direccionDe(lat, lon)`:

1. Valida la coordenada (lat -90..90, lon -180..180). Invalida devuelve `null`.
2. Busca en cache la clave redondeada a 5 decimales (`-2.15767/-79.89906`).
   Si hay acierto vigente lo devuelve sin esperar al ritmo.
3. Si no hay acierto, espera su turno en la cola (1 peticion/s), consulta
   `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=..&lon=..&zoom=18&addressdetails=1&accept-language=es`
   con `User-Agent` propio y `fetch` con `AbortController` (4 s).
4. Compone la direccion corta a partir de `address`:
   `road`/`pedestrian`/`footway` (calle), `suburb`/`neighbourhood` (barrio),
   `city`/`town`/`village` (ciudad), `state`, `country`. Une las partes no
   vacias con `", "`. Si no hay `address` (o no aporta nada), usa
   `display_name`.
5. Memoriza solo los aciertos y la devuelve. Cualquier fallo (HTTP != 200,
   JSON invalido, corte por tiempo, aborto de la peticion) devuelve `null`.

`direccionEnCache(lat, lon)` expone solo el paso 2: devuelve lo cacheado o
`null`, sin red ni espera. Es la via que usa el listado de paradas.

---

## 3. Endpoint

```
GET /api/v1/geocode/reverse?lat=-2.1577&lon=-79.8991
Cookie: dmj_sesion=...
```

| Caso | Estado | Cuerpo |
|---|---|---|
| Coordenada valida y resuelta | 200 | `{"direccion":"Doctor Luis Orrantia Cornejo, Kennedy, Guayaquil, Guayas, Ecuador"}` |
| Coordenada valida sin respuesta del proveedor | 200 | `{"direccion":null}` |
| Falta `lat` o `lon` | 400 | `{"error":{"codigo":"DATOS_INVALIDOS","mensaje":"Falta el parámetro lon."}}` |
| No numerico o fuera de rango | 400 | `{"error":{"codigo":"DATOS_INVALIDOS","mensaje":"El parámetro lat debe ser un número entre -90 y 90."}}` |
| Sin sesion | 401 | `{"error":{"codigo":"NO_AUTENTICADO","mensaje":"La sesión no existe o expiró."}}` |

La ruta exige sesion como el resto de `/api/v1`; no exige permisos sobre
equipos porque la direccion es informacion de referencia, no de un recurso.

Ejemplo real (Guayaquil, -2.1577, -79.8991):

```bash
curl -s -b galletas.txt \
  "http://127.0.0.1:8081/api/v1/geocode/reverse?lat=-2.1577&lon=-79.8991"
# {"direccion":"Doctor Luis Orrantia Cornejo, Kennedy, Guayaquil, Guayas, Ecuador"}
```

---

## 4. Cache en memoria

- Clave: `lat/lon` redondeado a 5 decimales (`toFixed(5)`), suficiente para
  agrupar posiciones GPS repetidas en la misma cuadra.
- Entrada: `{ direccion, guardadoEn }`. TTL de 24 h; al leer una entrada
  vencida se descarta.
- Tope: 5000 entradas. Al superarlo se evicta la mas antigua (orden de
  insercion del `Map`, FIFO simple). Una clave reinsertada pasa al final.
- Solo se memorizan direcciones resueltas. Los fallos no se cachean para
  permitir reintentos cuando vuelva la red.
- Es por proceso: cada instancia de la API tiene su propia cache. No se
  comparte entre workers ni se persiste; al reiniciar se parte vacia.

### Efecto en las paradas

`GET /api/v1/reports/stops` rellena `direccion` **solo desde la cache**: nunca
consulta Nominatim durante el listado, de modo que la respuesta no se frena
aunque haya decenas de paradas. Si la coordenada de una parada ya fue
consultada por el replay o por la Web, la parada sale con direccion; si no,
sale `null`. En la primera consulta de un punto la respuesta puede tardar
hasta ~5 s; el resto de consultas del mismo punto son inmediatas.

---

## 5. Ritmo y politica de uso de Nominatim

Nominatim es un servicio comunitario con una politica de uso estricta:

- Maximo absoluto de 1 peticion por segundo por aplicacion. Se cumple con una
  cola minima en `esperarTurno`: cada peticion arranca como minimo 1 s despues
  del inicio de la anterior. Las respuestas de cache no pasan por la cola.
- `User-Agent` obligatorio que identifique la aplicacion. Valor actual:
  `DMujeresTracking/0.1 (contacto interno: soporte@dmujeres.local)`.
- Tiempo limite de 4 s por peticion para no encolar trabajo muerto.
- Uso previsto: consultas puntuales desde el replay/la Web, no renderizado de
  mapas ni geocodificacion masiva. No se debe usar esta ruta para volcar
  historicos completos.
- La Web que muestre estos datos debe mantener la atribucion
  "© OpenStreetMap contributors" en el mapa.

---

## 6. Limites y que pasa si no hay red

El geocodificador es un extra de legibilidad, nunca un requisito:

- Si el servidor no tiene salida a Internet, Nominatim tarda o no responde,
  la peticion se corta a los 4 s y la ruta responde `200 {"direccion":null}`.
- Un error del proveedor (429, 5xx, JSON raro) tambien termina en `null`.
- Si el cliente corta la conexion, el `signal` de la peticion aborta el
  `fetch` en curso.
- Las paradas con `direccion: null` siguen mostrandose con latitud/longitud;
  la Web puede reintentar la consulta del punto cuando convenga.

---

## 7. Archivos y lineas clave

| Elemento | Ubicacion |
|---|---|
| Modulo completo | `services/api/src/geocodigo.js` |
| Constantes (UA, 4 s, 1 s, TTL 24 h, 5000) | `src/geocodigo.js:10` |
| Cola de ritmo | `esperarTurno`, `src/geocodigo.js:68` |
| Consulta a Nominatim | `consultarNominatim`, `src/geocodigo.js:81` |
| Composicion de la direccion | `componerDireccion`, `src/geocodigo.js:118` |
| API publica | `direccionDe` `src/geocodigo.js:136`, `direccionEnCache` `src/geocodigo.js:158` |
| Ruta | `obtenerDireccion`, `src/geocodigo.js:179`; tabla en `src/rutas.js:36` |
| Paradas con cache | `aParada`, `src/reportes.js:39-52` |
| Tipos TS | `packages/shared-types/src/geocode.ts` |
| Contrato | `packages/contracts/openapi.json` (`/api/v1/geocode/reverse`, `RespuestaDireccion`) |

---

## 8. Verificacion

- `node24 --check` de `geocodigo.js`, `rutas.js`, `reportes.js` y `smoke.mjs`: OK.
- `python3 -m json.tool packages/contracts/openapi.json`: JSON valido.
- `node24 services/api/smoke.mjs`: 51 PASS / 0 FAIL. El paso nuevo acepta
  direccion no vacia o `null` (red ausente) e informa cual ocurrio.
- `curl` autenticado real a Guayaquil:
  `{"direccion":"Doctor Luis Orrantia Cornejo, Kennedy, Guayaquil, Guayas, Ecuador"}`.
- Cache reflejada en paradas: una parada con `direccion: null` pasa a mostrar
  la direccion en `GET /reports/stops` despues de consultar la misma
  coordenada en `/geocode/reverse`, sin que el listado llame a Nominatim.
