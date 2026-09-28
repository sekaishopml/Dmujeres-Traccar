# FASE 4 (parte 1) - API propia `/api/v1`

Fecha: 2026-09-25
Alcance: construir `services/api` contra la base nueva `dmt_*` (`dmt-db` / `dmujeres`).
Producción: **no modificada** (contenedor `dmj-db`, DB `traccar`, servicios `dmj-*` intactos).
Sin commits.

---

## 1. Resumen

| Elemento | Valor |
|---|---|
| Servicio | `services/api` (Node 24 LTS, ESM, HTTP nativo) |
| Dependencia directa | `pg` 8.23.0 (unica) |
| Puerto desarrollo | `127.0.0.1:8081` (`DMJ_API_PORT`); no expuesto a Internet |
| Rutas | 20/20 del OpenAPI `packages/contracts/openapi.json` |
| Sesion | cookie propia `dmj_sesion` (HttpOnly, SameSite=Lax, `Secure` con `DMJ_TLS=1`) |
| Autenticacion | credencial heredada PBKDF2-HMAC-SHA1/1000/24 bytes (sal y hash en hex) |
| Base | `127.0.0.1:5443` / `dmujeres` / usuario `dmt` (credenciales solo en `.env`) |
| Smoke | 26 PASS / 0 FAIL con datos reales migrados |

---

## 2. Como arrancar

```sh
cd /home/DMujeres-Tracking/services/api
npm24 install          # unica dependencia: pg
npm24 start            # arranca en 127.0.0.1:8081
node24 smoke.mjs       # prueba de humo completa (arranca y mata su propio servidor)
npm24 run check        # node --check de todos los archivos
```

Nota: `npm24` ejecuta npm bajo Node 20 (shebang del paquete), por lo que puede
emitir `EBADENGINE` por `engines.node >= 24`. Es una advertencia del envoltorio,
no del codigo; los scripts internos invocan `node24` de forma explicita.

### Variables de entorno

`src/entorno.js` carga `/home/DMujeres-Tracking/.env` (ruta override:
`DMJ_ENV_FILE`) y luego aplica `process.env`, que tiene precedencia. Nunca
imprime valores.

| Variable | Uso | Defecto |
|---|---|---|
| `DMJ_API_HOST` / `DMJ_API_PORT` | bind de la API | `127.0.0.1` / `8081` |
| `DMJ_DB_HOST` / `DMJ_DB_PORT` | base nueva | `127.0.0.1` / `5443` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | credenciales base | sin defecto (obligatorias) |
| `DMJ_DB_URL` | alternativa a las anteriores | vacio |
| `DMJ_SESION_HORAS` | TTL de sesion web | `12` |
| `DMJ_TLS` | agrega `Secure` a la cookie | `0` |
| `DMJ_ENTORNO` | `desarrollo`/`pruebas`/`produccion` | `desarrollo` |
| `DMJ_ZONA_HORARIA` | ventanas por defecto | `America/Guayaquil` |
| `DMJ_REFRESCO_SEGUNDOS` | sondeo sugerido | `5` |
| `DMJ_MAPA_ESTILO_URL` / `DMJ_MAPA_ZOOM` | mapa MapLibre | estilo demo / `12` |
| `DMJ_TRACKING_URL` | si se define, `ready` sondea ese endpoint | vacio |
| `DMJ_COMMIT` / `DMJ_BUILD_TIME` | `/version` | `desconocido` / arranque |
| `DMJ_TEST_EMAIL` / `DMJ_TEST_PASSWORD` | smoke (usuario no admin) | ver seccion 6 |
| `DMJ_TEST_ADMIN_EMAIL` / `DMJ_TEST_ADMIN_PASSWORD` | smoke (opcional) | vacio |
| `DMJ_SMOKE_PORT` | puerto del servidor de humo | `18081` |

---

## 3. Rutas implementadas (20/20)

| Metodo | Ruta | Fuente principal |
|---|---|---|
| POST | `/api/v1/auth/login` | `iam.dmt_usuario` + `iam.dmt_sesion` |
| POST | `/api/v1/auth/logout` | `iam.dmt_sesion` (revoca) |
| GET | `/api/v1/auth/me` | `iam.dmt_sesion` + `iam.dmt_usuario` |
| GET | `/api/v1/fleet` | `tracking.dmt_dispositivo` + `dmt_posicion_actual` + `telemetry.dmt_bateria` |
| GET | `/api/v1/fleet/{id}` | idem, permiso por `operations.dmt_asignacion` |
| GET | `/api/v1/fleet/{id}/position` | `tracking.dmt_posicion_actual` |
| GET | `/api/v1/positions/live` | `tracking.dmt_posicion_actual` (sin `MAX()`) |
| GET | `/api/v1/replay` | `tracking.dmt_posicion` agrupado por dispositivo |
| GET | `/api/v1/replay/{deviceId}` | `tracking.dmt_posicion` por `fijado_en` |
| GET | `/api/v1/reports/trips` | `tracking.dmt_posicion` segmentado |
| GET | `/api/v1/reports/stops` | `tracking.dmt_posicion` segmentado |
| GET | `/api/v1/reports/summary` | idem, agregado global y por dispositivo |
| GET | `/api/v1/battery` | `telemetry.dmt_bateria` (ultima muestra por dispositivo) |
| GET | `/api/v1/battery/{deviceId}` | `telemetry.dmt_bateria` por ventana |
| GET | `/api/v1/users` | `iam.dmt_usuario` (solo administrador) |
| GET | `/api/v1/users/{id}` | `iam.dmt_usuario` (solo administrador) |
| GET | `/api/v1/config` | `system.dmt_configuracion` + entorno |
| GET | `/api/v1/health` | proceso (publica) |
| GET | `/api/v1/ready` | `SELECT 1` + tabla de tracking (publica) |
| GET | `/api/v1/version` | `system.dmt_version_esquema` (publica) |

Convenciones respetadas: DTOs en espanol, `Pagina<T>` con `pagina`/`tamano`
(1-based, 25 por defecto, 200 maximo), `desde`/`hasta` ISO-8601, `orden` con
prefijo `-`, ids por `idPublico` UUID o id legado numerico, errores
`{"error":{"codigo","mensaje"}}` con codigos `NO_AUTENTICADO`,
`SIN_PERMISO`, `NO_ENCONTRADO`, `DATOS_INVALIDOS`, `ERROR_INTERNO`,
`SERVICIO_NO_DISPONIBLE`.

---

## 4. Decisiones

1. **Credencial heredada tal cual.** El login verifica
   `pbkdf2Sync(clave, Buffer.from(sal,'hex'), 1000, 24, 'sha1')` contra
   `hash_clave` y compara con `timingSafeEqual`; nunca regenera ni registra el
   hash. Se conservan usuarios y claves migrados (plan maestro, seccion 6).
2. **Sesion en base.** El token es aleatorio de 32 bytes; en
   `iam.dmt_sesion` se guarda solo su SHA-256 (`token_hash`), con `tipo='web'`,
   IP, user-agent y `expira_en`. Logout revoca en el servidor y expira la
   cookie. Cada login crea una sesion nueva (rotacion).
3. **Cookie.** `dmj_sesion`: `HttpOnly`, `SameSite=Lax`, `Path=/`,
   `Max-Age=DMJ_SESION_HORAS`; `Secure` solo si `DMJ_TLS=1` (en desarrollo sin
   TLS, `Secure` impediria el login del navegador).
4. **Permisos en un solo predicado SQL.** `administrador` ve todo; usuario
   normal solo dispositivos con `operations.dmt_asignacion` activa y vigente.
   Un recurso no visible responde `404` (no se filtra su existencia). `users`
   exige `administrador`; el resto recibe `403`. `solo_lectura` no tiene rutas
   de escritura en este contrato (solo login/logout).
5. **Vivo sin `MAX()`.** `/positions/live` y `/fleet/{id}/position` leen
   `tracking.dmt_posicion_actual`. Esa tabla estaba vacia tras la migracion;
   se agrego `scripts/migration/04_refrescar_posicion_actual.sql` (idempotente,
   solo base nueva) para poblarla con la ultima posicion por dispositivo
   habilitado. En operacion la mantendra `services/tracking`.
6. **Replay por `fijado_en`.** El recorrido reconstruye la hora del fix GPS
   (`fijado_en`, con respaldo en `registrado_en`). Como el indice B-tree es
   `(dispositivo_id, registrado_en DESC)`, el SQL acota primero por
   `registrado_en` con un margen de 24 h y luego filtra/ordena por
   `coalesce(fijado_en, registrado_en)`. `EXPLAIN (ANALYZE, BUFFERS)` confirma
   `Index Scan using dmt_posicion_2026_09_dispositivo_id_registrado_en_id_idx`
   (971 filas, < 1 ms). En los datos migrados `fijado_en = registrado_en` en
   las 80.809 filas.
7. **Reportes segmentados desde el historico.** `operations.dmt_jornada_tramo`
   esta vacia (el servicio de tracking aun no la calcula), asi que la API
   deriva viajes y paradas de `tracking.dmt_posicion` con reglas fijas:
   movimiento si `velocidad_kmh > 5`, corte de isla si el hueco supera 900 s,
   viaje >= 60 s y >= 0.05 km, parada >= 180 s; distancia por Haversine.
   Cuando el tracking publique tramos, la API podra cambiar de fuente sin
   tocar el contrato.
8. **Replay: huecos y resumen.** Hueco si el salto entre posiciones supera
   600 s (`SIN_SENAL`); el resumen calcula distancia geometrica, duracion,
   velocidades y bateria inicial/final. La lista de replay y los reportes usan
   por defecto el dia local (`America/Guayaquil`); la ventana maxima es 31 dias
   (replay) y 92 dias (reportes), con `400 DATOS_INVALIDOS` si se supera.
9. **`ready` con semantica verificable.** `baseDatos` = `SELECT 1`;
   `tracking` = la tabla del modelo de vivo responde (o el endpoint
   `DMJ_TRACKING_URL` si esta configurado, con timeout de 2 s). Si la base cae,
   `503`; degradado se informa `200` con `estado: "degradado"`.
10. **Logs y cierre.** Una linea JSON por peticion a stdout
    (metodo, ruta, estado, duracion, usuario; nunca cuerpos, cookies ni
    claves). `SIGTERM`/`SIGINT` cierran el servidor y el pool, con salida
    forzada a los 10 s. `AbortController` corta la espera cuando el cliente se
    desconecta y en el sondeo de `ready`; `pg` 8.23 no acepta `AbortSignal` en
    la consulta, por lo que se complementa con `statement_timeout` (15 s) y
    `query_timeout`.
11. **Version legible.** `/version` reporta `0.1.0` (package.json),
    `versionApi: "v1"` y `versionEsquema: "08"` (maximo de
    `system.dmt_version_esquema`).

---

## 5. Archivos creados

```
services/api/package.json                     ESM, engines.node >= 24, solo pg
services/api/package-lock.json
services/api/smoke.mjs                        smoke end-to-end con datos reales
services/api/src/servidor.js                  HTTP nativo, rutas, cierre ordenado
services/api/src/rutas.js                     tabla de las 20 rutas
services/api/src/entorno.js                   carga .env + process.env
services/api/src/db.js                        pool pg, timeouts y abort
services/api/src/errores.js                   errores normalizados
services/api/src/http.js                      paginacion, rangos ISO, orden
services/api/src/log.js                       log JSON a stdout
services/api/src/sesiones.js                  cookies y tabla iam.dmt_sesion
services/api/src/dto.js                       DTOs en espanol
services/api/src/geo.js                       Haversine, huecos y resumen
services/api/src/segmentos.js                 segmentacion SQL de viajes/paradas
services/api/src/auth.js                      login/logout/me
services/api/src/flota.js                     flota y predicado de permisos
services/api/src/posiciones.js                vivo y ultima posicion
services/api/src/replay.js                    recorrido por ventana
services/api/src/reportes.js                  viajes, paradas y resumen
services/api/src/bateria.js                   bateria de flota y detalle
services/api/src/usuarios.js                  usuarios (administrador)
services/api/src/config.js                    configuracion de la Web
services/api/src/salud.js                     health/ready/version
scripts/migration/04_refrescar_posicion_actual.sql   refresco del modelo vivo
docs/api/FASE4-API.md                         este documento
```

No se modifico ningun archivo existente fuera de `docs/api/`.

---

## 6. Verificacion (evidencia real)

### 6.1 Instalacion

```sh
cd /home/DMujeres-Tracking/services/api && npm24 install
```

```text
up to date, audited 15 packages in 544ms

found 0 vulnerabilities
```

### 6.2 Sintaxis

```sh
npm24 run check
```

```text
> for archivo in src/*.js smoke.mjs; do node24 --check "$archivo" || exit 1; done && echo "check-ok"

check-ok
```

### 6.3 Smoke (`node24 smoke.mjs`, exit 0)

Usuario real: `DMJ_TEST_EMAIL` (defecto `fernando@dmujeres.local`) con la clave
migrada en `DMJ_TEST_PASSWORD` (no se documenta el valor). Contra la base nueva:

```text
PASS  health 200 y estado ok
PASS  ready 200 con baseDatos/tracking ok
PASS  version 200 con versionApi v1 y versionEsquema
PASS  sin cookie 401 NO_AUTENTICADO
PASS  login con clave incorrecta 401
PASS  login 200, cookie HttpOnly y SameSite=Lax
PASS  auth/me 200 con el usuario del login
PASS  fleet 200 paginado con datos
PASS  fleet/{id} 200 coincide el dispositivo
PASS  fleet/{idPublico} 200
PASS  fleet/{id}/position 200 con latitud/registradoEn
PASS  fleet de dispositivo ajeno 404
PASS  positions/live 200 con datos y refresco
PASS  replay (hoy) 200 con recorrido disponible
PASS  replay/{deviceId} 200 con posiciones, huecos y resumen
PASS  reports/trips 200 paginado con viajes
PASS  reports/stops 200 paginado con paradas
PASS  reports/summary 200 con totales y desglose
PASS  battery 200 paginado
PASS  battery/{deviceId} 200 con muestras
PASS  users sin ser administrador 403 SIN_PERMISO
PASS  users/{id} sin ser administrador 403 SIN_PERMISO
PASS  parametro invalido 400 DATOS_INVALIDOS
PASS  config 200 con mapa y capacidades
PASS  logout 204
PASS  auth/me tras logout 401
INFO  users como administrador: omitido (defina DMJ_TEST_ADMIN_EMAIL/DMJ_TEST_ADMIN_PASSWORD)

Smoke API v1: 26 PASS, 0 FAIL
RESULTADO: PASS
```

La rama administrador se verifico aparte con una sesion web temporal
(`users` 200, `users/1` 200, `fleet` 9 dispositivos) creada y eliminada
directamente en `iam.dmt_sesion`; no se toco ninguna credencial.

Muestra real de respuestas (recortada):

```json
{"estado":"listo","dependencias":{"baseDatos":"ok","tracking":"ok"}}
{"version":"0.1.0","versionApi":"v1","versionEsquema":"08"}
{"datos":[{"id":59,"idPublico":"01a0dbc3-52ba-751a-bd76-347ade8ea722","nombre":"Fernando",
 "identificadorUnico":"fernando","habilitado":true,"estado":"EN_LINEA",
 "ultimaConexion":"2026-09-25T22:23:08.271Z","versionApp":"2.1.65",
 "jornadaActiva":false,"bateriaPct":52,"cargando":null,"pendientes":null}],
 "total":1,"pagina":1,"tamano":25}
{"posiciones":[{"id":83752,"dispositivoId":59,"latitud":-2.0926942,"longitud":-79.9207526,
 "velocidadKmh":0,"bateriaPct":94,"registradoEn":"2026-09-25T08:04:34.000Z","valida":true}],
 "huecos":[{"desde":"2026-09-25T09:12:13.000Z","hasta":"2026-09-25T09:29:18.000Z",
 "duracionSegundos":1025,"motivo":"SIN_SENAL"}],
 "resumen":{"inicio":"2026-09-25T08:04:34.000Z","fin":"2026-09-25T22:23:06.000Z",
 "totalPosiciones":971,"totalHuecos":25,"distanciaKm":55.894,"duracionMin":858.5,
 "velocidadPromedioKmh":3.9,"velocidadMaximaKmh":115,
 "bateriaInicialPct":94,"bateriaFinalPct":52}}
```

### 6.4 Produccion intacta y base nueva sana

```sh
docker exec dmj-db psql -U traccar -d traccar -tAc "select count(*) from tc_positions;"
```

```text
26937
```

```sh
docker inspect dmt-db --format '{{.State.Health.Status}}'
docker exec dmt-db psql -U dmt -d dmujeres -tAc "select count(*) from tracking.dmt_posicion;"
docker ps --filter name=dmt-db --format '{{.Names}} {{.Ports}}'
```

```text
healthy
80809
dmt-db 127.0.0.1:5443->5432/tcp
```

La API solo referencia el puerto `5443`; no hay ninguna referencia a `5433`
(base de produccion) en `services/api`.

### 6.5 Secretos

- La clave de la base nueva vive solo en `/home/DMujeres-Tracking/.env` (600).
- Este documento no contiene claves, hashes ni sales.
- `smoke.mjs` recibe la clave por `DMJ_TEST_PASSWORD`; su valor por defecto es
  el de la credencial de prueba (unica excepcion autorizada) y no se imprime
  en la salida.
- Los logs de la API no registran cuerpos, cookies, claves ni tokens.

---

## 7. Dudas y limitaciones

1. **Calidad de la velocidad movil.** En los datos migrados hay tramos con
   coordenadas que avanzan (el atributo `distance` de la App suma ~53,8 km en
   un dia) mientras `velocidad_kmh` reporta 0 (`mobile.speedSource: implied`).
   Los reportes por umbral de velocidad subestiman distancia/viajes en esos
   casos; se espera que `services/tracking` calcule `operations.dmt_jornada_tramo`
   con mejores reglas y la API pase a leerlos. Mientras tanto las constantes de
   segmentacion quedan documentadas en `src/segmentos.js`.
2. **`dmt_posicion_actual` no la mantiene aun nadie.** El script de refresco es
   un parche de arranque; si el servicio de tracking no la actualiza, el panel
   en vivo queda congelado en la ultima corrida del script.
3. **`ready.tracking`** hoy significa "esquema de tracking consultable"; con
   `DMJ_TRACKING_URL` configurado pasa a ser el sondeo real del servicio.
4. **`fleet` filtra `habilitado = true`.** Los dispositivos historicos
   deshabilitados no aparecen en el panel; no hay parametro en el contrato para
   incluirlos.
5. **`battery/{deviceId}` limita a 2000 muestras** (las mas recientes de la
   ventana) y usa 24 h por defecto; el contrato no define paginacion para esa
   serie.
6. **Mapa por defecto** (`demotiles.maplibre.org`) y centro inicial calculado
   del promedio de posiciones vivas; para produccion conviene fijar
   `system.dmt_configuracion` (`web.mapa.estiloUrl`, `web.mapa.centro`) o las
   variables `DMJ_MAPA_*`.
7. **Zona horaria por defecto `America/Guayaquil`** (la operacion real segun
   `docs/agents/INVENTARIO.md`), no la de los ejemplos del contrato.
8. **Smoke administrador opcional**: no hay credencial admin disponible; la
   verificacion temporal no forma parte del script. Si el orquestador entrega
   `DMJ_TEST_ADMIN_*`, el smoke valida `users` 200 automaticamente.

---

## 8. Siguiente fase

- FASE 4 (parte 2): `services/tracking` (OsmAnd/MQTT, mantencion de
  `dmt_posicion_actual`, calculo de `dmt_jornada_tramo` y telemetria).
- FASE 5: Web nueva consumiendo `/api/v1`.
