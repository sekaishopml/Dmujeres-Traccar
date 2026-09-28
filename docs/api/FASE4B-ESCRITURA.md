# FASE 4b - Escritura de usuarios y configuracion de equipos

Fecha: 2026-09-25
Alcance: escritura en `services/api` (usuarios y configuracion de equipos)
contra la base nueva `dmt-db` / `dmujeres`.
Produccion **no modificada** (contenedor `dmj-db`/`traccar`, `dmj-traccar`,
`dmj-match` intactos). Sin commits.

---

## 1. Resumen

| Elemento | Valor |
|---|---|
| Rutas nuevas | 4 (24/24 del OpenAPI) |
| `POST /api/v1/users` | administrador; 201 `{usuario}` |
| `PUT /api/v1/users/{id}` | administrador; 200 `{usuario}` |
| `DELETE /api/v1/users/{id}` | administrador; 204 |
| `PUT /api/v1/fleet/{id}` | admin o equipo asignado; 200 `{dispositivo}` |
| Credencial | PBKDF2-HMAC-SHA1/1000/24, sal aleatoria de 24 bytes, hex |
| Auditoria | `audit.dmt_auditoria` en toda escritura, sin secretos |
| DTOs | `Usuario.dispositivoIds`, `Dispositivo.configuracion` |
| Smoke | 49 PASS / 0 FAIL (26 previos + 23 nuevos) con limpieza total |

No se toco ningun archivo del frontend ni de la base; solo `services/api`,
`packages/contracts`, `packages/shared-types` y este documento.

---

## 2. Endpoints nuevos (lineas clave)

| Metodo y ruta | Codigo | Linea |
|---|---|---|
| POST /api/v1/users | `usuarios.crearUsuario` | `src/usuarios.js:252` |
| PUT /api/v1/users/{id} | `usuarios.actualizarUsuario` | `src/usuarios.js:304` |
| DELETE /api/v1/users/{id} | `usuarios.eliminarUsuario` | `src/usuarios.js:389` |
| PUT /api/v1/fleet/{id} | `flota.actualizarDispositivo` | `src/flota.js:153` |
| Credencial heredada | `crearCredencial` | `src/auth.js:41` |
| Sincronizacion de asignaciones | `sincronizarAsignaciones` | `src/usuarios.js:143` |
| Derivacion de `nombre_usuario` | `derivarNombreUsuario` | `src/usuarios.js:120` |
| Guarda de baja | `motivoRechazoEliminacion` | `src/usuarios.js:187` |
| Whitelist de configuracion | `CLAVES_CONFIGURABLES` | `src/dto.js:8` |
| DTO `configuracion` | `configuracionDeAtributos` | `src/dto.js:31` |
| `dispositivoIds` en el DTO Usuario | `SUBCONSULTA_DISPOSITIVOS` | `src/sesiones.js:11` |
| Auditoria con `datos` | `auditar` | `src/sesiones.js:120` |
| Transacciones | `enTransaccion` | `src/db.js:49` |
| Tabla de rutas | 4 entradas nuevas | `src/rutas.js:19,30,32,33` |

### 2.1 `POST /api/v1/users`

Cuerpo: `{nombre, correo, clave, administrador?, soloLectura?, dispositivoIds?}`.
Valida nombre/correo/clave (8-200), correo unico sin distinguir mayusculas y
`dispositivoIds` como lista de `idPublico` o ids legados. Todo ocurre en una
transaccion: usuario + asignaciones; la auditoria se registra despues del
commit (best-effort, como el resto de la API).

### 2.2 `PUT /api/v1/users/{id}`

Mismo cuerpo con todos los campos opcionales. `clave` ausente, `null` o `""`
no cambia la credencial. `dispositivoIds` sincroniza la lista completa sin
borrar historico: altas con `INSERT ... activa=true, desde_en=now()` y bajas
con `UPDATE ... activa=false, hasta_en=now()`.

### 2.3 `DELETE /api/v1/users/{id}`

Baja logica (`habilitado=false`), revoca sesiones y desactiva asignaciones.
Rechaza con 400 la cuenta propia o dejar la instancia sin administradores
activos. Es idempotente: una cuenta ya dada de baja responde 204.

### 2.4 `PUT /api/v1/fleet/{id}`

Cuerpo: `{nombre?, configuracion?}`. Solo acepta la whitelist:

```text
mobile.intervalSeconds, mobile.minIntervalSeconds, mobile.distanceMeters,
mobile.angleDegrees, mobile.accuracy, mobile.bufferEnabled, mobile.bufferMax,
mobile.bufferPolicy, mobile.ackTimeoutSeconds, mobile.maxRetries
```

El merge es `atributos = atributos || $configuracion::jsonb`: nunca pisa otras
claves. Una clave fuera de la whitelist responde `400 DATOS_INVALIDOS`.
Permiso: administrador o usuario con asignacion activa y vigente; el resto
recibe `404` (mismo criterio que el GET, no se filtra la existencia).

---

## 3. Decisiones

1. **Credencial heredada, generada con el mismo formato.** `crearCredencial`
   usa `pbkdf2Sync(clave, sal, 1000, 24, 'sha1')` con sal aleatoria de 24
   bytes y guarda hash y sal en hex, exactamente lo que verifica
   `verificarClave` en `auth.js`. El login del usuario recien creado pasa por
   el mismo camino que los usuarios migrados.
2. **`nombre_usuario` derivado del correo.** El esquema lo admite `NULL`, pero
   la App historica inicia sesion con el; se deriva de la parte local
   (minusculas, sin acentos ni espacios, 40 caracteres) y se desambigua con
   sufijos `2..100` si ya existe. En `PUT` no se recalcula: el correo puede
   cambiar sin romper el login ni la identidad de la cuenta. Queda registrado
   en la auditoria como `nombreUsuario`.
3. **Sincronizacion sin borrar historico.** `operations.dmt_asignacion`
   conserva todas las filas; las bajas solo marcan `activa=false` y
   `hasta_en=now()`. `dispositivoIds` del DTO es la lista de asignaciones
   activas y vigentes (idPublico).
4. **Baja logica y sesiones.** El esquema permite `habilitado=false`, asi que
   se usa baja logica. Ademas se revocan todas las sesiones abiertas y se
   desactivan las asignaciones. No se borra ninguna fila de usuarios,
   asignaciones ni auditoria.
5. **Guarda de ultimo administrador.** `motivoRechazoEliminacion` decide entre
   `ultimo_administrador`, `autoborrado` o `null`. En el sistema actual la
   guarda solo es alcanzable por HTTP cuando el ejecutor se borra a si mismo
   (un administrador activo que ejecuta siempre se cuenta a si mismo), por eso
   el mensaje del 400 de auto-borrado es explicito y la guarda se prueba
   ademas como funcion pura. Con un administrador adicional presente, borrar a
   ese administrador se permite (el smoke lo verifica en sentido inverso: el
   auto-borrado nunca procede).
6. **`configuracion` solo para administradores.** `aDispositivo(fila, usuario)`
   devuelve la whitelist solo si `usuario.administrador`; cualquier otro
   usuario recibe `null`. El DTO nunca expone `atributos` completos.
7. **Auditoria en toda escritura.** Acciones `crear_usuario`, `editar_usuario`,
   `eliminar_usuario`, `editar_dispositivo`; `datos` guarda como maximo
   correo, flags, nombre de los campos cambiados, idPublico de equipos y
   contadores. Nunca claves, hashes, sales ni tokens. La columna `datos` es
   nueva en el INSERT de `auditar` (antes siempre `{}`).
8. **Correccion de borde en `ErrorApi`.** El constructor no asignaba
   `this.mensaje`, asi que `responderError` componia
   `{"error":{"codigo":"..."}}` sin mensaje, contra el contrato. Se corrigio
   en `src/errores.js:13`; el smoke lo verifica ahora en el 400 de
   auto-borrado.

---

## 4. Contratos actualizados

- `packages/contracts/openapi.json`: 4 operaciones nuevas, esquemas
  `UsuarioCreacion`, `UsuarioActualizacion`, `RespuestaUsuario`,
  `ActualizacionDispositivo`, `RespuestaDispositivo`; `Usuario.dispositivoIds`
  y `Dispositivo.configuracion` en `required`. Validado con
  `python3 -m json.tool` (salida: `JSON OK`) y contrastado con
  `services/api/src/rutas.js`: **24 rutas y 24 operaciones, sin diferencias**.
- `packages/shared-types/src/users.ts`: `Usuario.dispositivoIds`,
  `UsuarioCreacion`, `UsuarioActualizacion`, `RespuestaUsuario`.
- `packages/shared-types/src/fleet.ts`: `Dispositivo.configuracion`,
  `CLAVES_CONFIGURABLES`, `ClaveConfigurable`, `ActualizacionDispositivo`,
  `RespuestaDispositivo`.
- `apps/web` compila sin cambios (`tsc -b`, exit 0) y
  `packages/shared-types` tambien (`tsc -p tsconfig.json`, exit 0).

---

## 5. Verificacion (evidencia real)

### 5.1 Sintaxis

```sh
cd /home/DMujeres-Tracking/services/api && npm24 run check
```

```text
check-ok
```

### 5.2 Contrato

```sh
python3 -m json.tool packages/contracts/openapi.json > /dev/null && echo "JSON OK"
# JSON OK
# rutas openapi: 24 | rutas implementadas: 24 | sin diferencias
```

### 5.3 Smoke completo (`node24 smoke.mjs`, exit 0)

Administrador temporal `prueba-admin@dmujeres.local` insertado por SQL con
hash PBKDF2 (misma formula) y eliminado junto con sus sesiones, asignaciones y
auditoria; equipos 47/50 restaurados a su estado previo. Nunca se toco al
administrador real (id 1).

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
PASS  admin temporal creado por SQL y login 200
PASS  POST /users 201 crea usuario con dispositivoIds
PASS  credencial PBKDF2 heredada (48 hex) y nombre_usuario derivado del correo
PASS  asignacion activa del equipo A en operations.dmt_asignacion
PASS  login del usuario nuevo 200
PASS  PUT /users sincroniza equipos: activa B y desactiva A conservando historico
PASS  PUT /users edita nombre y clave
PASS  login con la clave nueva 200
PASS  login con la clave anterior 401
PASS  PUT /users con clave vacia no cambia la credencial
PASS  POST /users con usuario no admin 403 SIN_PERMISO
PASS  DELETE /users con usuario no admin 403 SIN_PERMISO
PASS  PUT /fleet aplica whitelist y nombre
PASS  merge conserva las demas claves de atributos
PASS  PUT /fleet rechaza claves fuera de la whitelist 400
PASS  PUT /fleet: equipo asignado 200 (no admin) y equipo ajeno 404
PASS  configuracion solo para administradores
PASS  400 al intentar borrar el ultimo admin (auto-borrado)
PASS  guarda de ultimo administrador activo (funcion de decision)
PASS  DELETE /users 204 con baja logica y limpieza de sesiones/asignaciones
PASS  login tras la baja 401
PASS  auditoria registra las escrituras sin claves ni hashes
PASS  limpieza del bloque de escritura (usuarios, sesiones, asignaciones, auditoria)

Smoke API v1: 49 PASS, 0 FAIL
RESULTADO: PASS
```

Verificacion directa posterior del smoke (psql):

```text
usuarios_temp | sesiones_temp | asignaciones_temp | auditoria_temp
--------------+---------------+-------------------+----------------
            0 |             0 |                 0 |              0
```

```text
 id | nombre | intervalo | buffer_max | journey
----+--------+-----------+------------+---------
 47 | qa-f0  |           |            | t
 50 | macias | 10        | 5000       | t
```

(El smoke escribe `mobile.intervalSeconds=45` en el 47 y `mobile.bufferMax=7`
en el 50; la limpieza restaura el estado exacto.)

### 5.4 Muestras HTTP reales

```json
// POST /api/v1/users -> 201
{"usuario":{"id":15,"idPublico":"01a0dc13-f615-77fa-ace2-ab10cca9fe43",
 "nombre":"Usuario doc","correo":"doc-usuario@dmujeres.local",
 "administrador":false,"soloLectura":false,"habilitado":true,
 "dispositivoIds":["01a0dbc3-52ba-77bb-b9e7-a65db9c1733f"]}}
```

```json
// PUT /api/v1/fleet/{id} -> 200 (recortado)
{"dispositivo":{"id":47,"idPublico":"01a0dbc3-52ba-77bb-b9e7-a65db9c1733f",
 "nombre":"qa-f0","identificadorUnico":"qa-f0","habilitado":true,
 "estado":"SIN_SENAL","versionApp":"2.1.63","jornadaActiva":true,
 "bateriaPct":50,"pendientes":0,
 "configuracion":{"mobile.intervalSeconds":45}}}
```

```json
// PUT /api/v1/fleet/{id} con clave no permitida -> 400
{"error":{"codigo":"DATOS_INVALIDOS","mensaje":"La clave mobile.noPermitida no
 es configurable. Permitidas: mobile.intervalSeconds, mobile.minIntervalSeconds,
 mobile.distanceMeters, mobile.angleDegrees, mobile.accuracy,
 mobile.bufferEnabled, mobile.bufferMax, mobile.bufferPolicy,
 mobile.ackTimeoutSeconds, mobile.maxRetries."}}
```

```json
// DELETE /api/v1/users/{id} de la propia cuenta admin -> 400
{"error":{"codigo":"DATOS_INVALIDOS","mensaje":"No puede eliminar su propia cuenta."}}
```

`DELETE /api/v1/users/{id}` de otro usuario -> `204` sin cuerpo.

### 5.5 Produccion intacta y base nueva sana

```sh
docker exec dmj-db psql -U traccar -d traccar -tAc "select count(*) from tc_positions;"
docker inspect dmt-db --format '{{.State.Health.Status}}'
docker inspect dmj-db --format '{{.State.Health.Status}}'
docker exec dmt-db psql -U dmt -d dmujeres -tAc "select count(*) from tracking.dmt_posicion;"
systemctl is-active dmj-traccar dmj-match
```

```text
27046
healthy
healthy
80809
active
active
```

El incremento de `tc_positions` (26937 -> 27046 durante toda la sesion) es
trafico real de la App; `services/api` no tiene ninguna referencia al puerto
5433 ni a la base `traccar`.

### 5.6 Secretos

- Las claves de prueba viven solo en `services/api/smoke.mjs` como credenciales
  efimeras (se crean y se borran en la misma ejecucion).
- El hash del administrador temporal lo genera el propio smoke; no se imprime.
- `audit.dmt_auditoria.datos` se verifico sin valores de claves ni hashes.
- Este documento no contiene claves, hashes ni sales.

---

## 6. Archivos tocados

```text
services/api/src/dto.js            CLAVES_CONFIGURABLES, configuracionDeAtributos, DTOs
services/api/src/db.js             enTransaccion (BEGIN/COMMIT/ROLLBACK)
services/api/src/errores.js        ErrorApi.mensaje (correccion de contrato)
services/api/src/sesiones.js       SUBCONSULTA_DISPOSITIVOS, auditar(datos)
services/api/src/auth.js           crearCredencial + dispositivoIds en login
services/api/src/usuarios.js       POST/PUT/DELETE y sincronizacion de equipos
services/api/src/flota.js          PUT /fleet/{id}, whitelist y merge
services/api/src/replay.js         aDispositivo con usuario
services/api/src/rutas.js          4 rutas nuevas (24 en total)
services/api/smoke.mjs             bloque de escritura con admin temporal
packages/contracts/openapi.json    4 operaciones y 5 esquemas nuevos
packages/shared-types/src/users.ts tipos de usuario
packages/shared-types/src/fleet.ts tipos de equipo y configuracion
docs/api/FASE4B-ESCRITURA.md       este documento
```

---

## 7. Dudas y limitaciones

1. **`nombre_usuario` no cambia al editar el correo.** Una cuenta puede seguir
   iniciando sesion con su `nombre_usuario` original aunque cambie el correo.
   Es intencional (no rompe la App), pero conviene confirmarlo.
2. **La guarda de ultimo administrador por HTTP solo se alcanza con el
   auto-borrado** (un admin activo siempre se cuenta a si mismo). Se implemento
   y se prueba como funcion pura; si el orquestador quiere un 400 HTTP del
   caso "borrar a otro ultimo admin", hace falta un entorno sin mas admins o
   autorizar tocar una cuenta admin de prueba.
3. **La sincronizacion de equipos reactiva con una fila nueva.** Si un equipo
   se reasigna tras una baja, se crea otra fila en `dmt_asignacion`; el
   historial queda completo pero el `dispositivoIds` no distingue periodos.
4. **La auditoria es best-effort**: si el INSERT falla, la escritura ya se
   confirmo y el fallo solo queda en el log (`auditoria_no_registrada`). Es el
   comportamiento heredado de FASE 4a.
5. **`Dispositivo.configuracion` se calcula por peticion** con la subconsulta
   de asignaciones y el filtro de administrador; con 19 dispositivos es
   irrelevante, conviene revisarlo si la flota crece mucho.
6. **La API no gestiona `habilitado` desde `PUT /users`** (no estaba en el
   contrato); la unica via de baja es `DELETE`.
