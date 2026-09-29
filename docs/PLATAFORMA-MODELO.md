# Modelo único de la plataforma — DMujeres Tracking

Estado: vigente al 2026-09-28, rama `plataforma` (commit base `2018699`, con
trabajo en curso en el árbol de trabajo).

Este documento es la referencia humana del modelo de la plataforma: qué es cada
pieza, cómo se relacionan las tablas reales, qué reglas rigen en cada pantalla
y qué ciclos de vida existen. Está escrito para leerse sin conocer el código.
El código y los ADR mandan; cuando algo no coincida con lo que aquí se
describe, se reporta como desvío (informe aparte), no se "arregla" cambiando
este documento.

Regla de oro de todo el sistema: **nunca fabricar datos**. Si hay duda entre
"parecer que funcionó" y "mostrar qué se registró, qué se reconstruyó y qué
está degradado", se muestra lo segundo.

---

## 1. Glosario

| Término | Qué es | Dónde vive | Regla corta |
|---|---|---|---|
| **Persona** | Quien hace ruta (Pilay, Alejandro...). Es a la vez cuenta de acceso y persona de campo; en el panel se administra desde Usuarios. | `iam.dmt_usuario` | Se crea con su cuenta y, en el mismo acto, su equipo de rastreo (1:1). No se le asignan roles ni permisos. |
| **Cuenta** | Las credenciales con las que se entra (usuario y clave), sean de una persona de campo o de administración. Es la misma tabla para ambas. | `iam.dmt_usuario` + `iam.dmt_sesion` | El login es `nombre_usuario` (o el correo). La clave se guarda como hash PBKDF2-SHA1; nunca viaja ni se registra en claro. |
| **Cuenta de administración** | Cuenta del panel (oficina). Se gestiona en Sistema y es la que puede ver y operar todo. | `iam.dmt_usuario` con `administrador = true` | Sin ella no se administra el plantel ni el sistema. Nunca puede quedar la instancia sin al menos un administrador activo. |
| **Equipo** | El dispositivo de rastreo de una persona (el teléfono con la app). En el lenguaje del panel también se le llama "unidad". | `tracking.dmt_dispositivo` | Su `identificador` es el nombre con el que la app se reporta. Para las personas creadas en Usuarios es el usuario en minúsculas. Un equipo dado de baja (`habilitado = false`) no aparece en ninguna lista, ni en En vivo ni en Replay. |
| **Grupo** | Agrupación de personas (por equipo de trabajo, zona o turno). | `iam.dmt_grupo` + `iam.dmt_usuario_grupo` | Es una etiqueta organizativa: no da visibilidad, no da permisos y no cambia el rastreo. Al borrar el grupo, las personas siguen existiendo. |
| **Asignación** | El vínculo "esta persona puede ver y usar este equipo". | `operations.dmt_asignacion` | Tiene historia (`desde_en`, `hasta_en`, `activa`): una asignación no se borra, se cierra. Es la que decide quién ve qué en En vivo, Replay, Reportes y Batería. |
| **Jornada** | El turno de trabajo: la app avisa cuándo se abre y cuándo se cierra. | `operations.dmt_jornada` | Una jornada abierta por equipo. El servidor la reconcilia al arrancar y cierra por timeout las abandonadas (12 h sin actividad). |
| **Tramo** | Las partes de una jornada (movimiento, pausa, sin señal) y también los viajes y paradas derivados. | `operations.dmt_jornada_tramo` (tabla reservada) y derivación en vivo desde `tracking.dmt_posicion` | Hoy los viajes y paradas se calculan del histórico con reglas fijas; la tabla de tramos queda para cuando el motor de tracking la calcule. |
| **Posición (fix)** | Un punto GPS registrado, con hora, velocidad, precisión y batería. Es la verdad del recorrido. | `tracking.dmt_posicion` (particionada por mes) y la última en `tracking.dmt_posicion_actual` | Solo avanza: un paquete atrasado nunca pisa la posición viva ni `ultima_conexion_en`. |
| **Reconstruido** | Un tramo de la ruta que no tiene fixes punto a punto y se completa por calles con el motor de ruteo. | Cálculo en vivo (`services/api/src/ruteo.js`), no se persiste | Se rotula siempre: no se dibuja como GPS registrado. Hay dos métodos: MATCHED y ESTIMATED. |
| **MATCHED** | Reconstrucción "ajustada a vía": había observaciones suficientes y el trazado se pegó a las calles con `/match`. | Respuesta del Replay (`reconstruidos`) | Línea continua fina con estilo propio y etiqueta "Ajustado a vía". |
| **ESTIMATED** | Reconstrucción "tramo estimado": no había observaciones, se resolvió una ruta A→B con `/route`. | Respuesta del Replay (`reconstruidos`) | Punteado gris, sin flechas, etiqueta "Tramo estimado". |
| **REAL** | El GPS registrado, tal como llegó: es la única línea con color por velocidad y flechas de dirección. | `tracking.dmt_posicion` | Es la evidencia. Nunca se mezcla visualmente con MATCHED ni ESTIMATED. |
| **Hueco** | Más de 10 minutos entre dos fixes consecutivos. | Cálculo del Replay (`calcularHuecos`) | Se marca como sin señal. Si hay ruteo disponible, se reconstruye y se rotula; si no, queda el hueco crudo. |
| **Sesión** | La entrada activa de una cuenta: en el panel por cookie, en el teléfono por token. | `iam.dmt_sesion` (tipos `web` y `movil`) | Solo se guarda el hash del token. Al deshabilitar una cuenta, todas sus sesiones se revocan. |
| **Alerta** | Aviso operativo (por ejemplo, un intento de recuperación o un ack del teléfono). | `operations.dmt_alerta` | Origen y severidad; el detalle variable va en `atributos`. |
| **Auditoría** | Quién hizo qué sobre qué, con fecha, IP y agente. | `audit.dmt_auditoria` | Se escribe sola en cada acción administrativa; no se puede usar para guardar claves ni tokens. |
| **OTA** | Actualización de la app por aire, con banner en el teléfono. | Manifiesto `ota/latest.json` + política `ota/rollout.json`, servidos por el canal móvil | Cada versión publicada mayor a la instalada enciende el banner. El panel no tiene versión. |

---

## 2. Modelo de datos mínimo

La base se llama `dmt-db` (PostgreSQL) y usa seis esquemas. Todas las tablas
llevan prefijo `dmt_`, nombres en español singular, PK `id` y una referencia
pública `id_publico` (UUID v7). Las columnas `id_legado` conservan el
identificador del sistema anterior para la trazabilidad de la migración.

### 2.1 Relaciones principales

```
iam.dmt_usuario (persona / cuenta)
 ├── iam.dmt_usuario_rol ────── iam.dmt_rol            (roles; solo se usan para admin y solo lectura)
 ├── iam.dmt_usuario_grupo ──── iam.dmt_grupo          (agrupación de personas)
 ├── iam.dmt_sesion                                    (entradas web / móvil, con hash del token)
 ├── iam.dmt_token_fcm                                (push por equipo)
 └── operations.dmt_asignacion ── tracking.dmt_dispositivo
                                       ├── tracking.dmt_posicion          (histórico, particionado por mes)
                                       ├── tracking.dmt_posicion_actual   (la última, una fila por equipo)
                                       ├── telemetry.dmt_bateria          (muestras de batería)
                                       ├── telemetry.dmt_senal            (muestras de señal/red)
                                       ├── telemetry.dmt_salud_dispositivo(buckets de salud)
                                       ├── operations.dmt_jornada ── operations.dmt_jornada_tramo
                                       ├── tracking.dmt_evento            (eventos, particionado por mes)
                                       ├── operations.dmt_alerta
                                       └── iam.dmt_token_fcm

audit.dmt_auditoria   (quién hizo qué)
system.dmt_configuracion, system.dmt_migracion_mapa, system.dmt_version_esquema
```

### 2.2 Las tablas, una por una

**Identidad y acceso (`iam`)**

- `dmt_usuario`: la persona y la cuenta. Campos que importan: `nombre_usuario`
  (login, único), `nombre` (nombre completo), `correo`, `telefono`, `cargo`,
  `hash_clave`/`sal` (credencial heredada), `habilitado` (baja lógica),
  `administrador`, `solo_lectura`, `atributos` (aquí vive `configApp`, los
  ajustes propios de la app de esa persona).
- `dmt_rol`: catálogo de tres roles: `administrador`, `operador`,
  `solo_lectura`. En la práctica el panel se apoya en los indicadores de la
  cuenta; las personas de campo no llevan roles.
- `dmt_usuario_rol`: qué roles tiene cada cuenta.
- `dmt_grupo` y `dmt_usuario_grupo`: grupos de personas y quién pertenece a
  cada uno. Al borrar un grupo o una cuenta, las membresías se borran solas.
- `dmt_sesion`: entradas de la plataforma. `tipo` es `web` (cookie
  `dmj_sesion`) o `movil` (Bearer del teléfono); se revoca con
  `revocada_en` y expira con `expira_en`.
- `dmt_token_fcm`: token de notificaciones por equipo. El token completo no
  sale nunca; en logs y atributos solo se ve un prefijo.
- `dmt_clave_firma` y `dmt_token_revocado`: compatibilidad con los tokens
  JWT heredados durante el corte.

**Rastreo (`tracking`)**

- `dmt_dispositivo`: el equipo. `identificador` es único y es el nombre con el
  que la app reporta posiciones; `habilitado` es la baja lógica; `estado`
  guarda el último estado reportado (`online`, ...); `ultima_conexion_en` y
  `ultima_posicion_id` solo avanzan; `atributos` guarda todo lo variable de la
  app (`mobile.*`: versión, jornada actual, diagnóstico, GPS, permisos,
  batería, etc.).
- `dmt_posicion`: histórico de fixes, particionado por mes de
  `registrado_en`. La identidad de ingesta es `(dispositivo_id, boot_id,
  local_sequence)` con índice único parcial; `fijado_en` es la hora del fix
  GPS y `registrado_en` la del reloj del teléfono (columna de partición).
  `valida = false` marca fixes simulados (mock).
- `dmt_posicion_actual`: una fila por equipo con la última posición conocida,
  para que el panel no tenga que buscar el máximo en el histórico. Solo
  avanza.
- `dmt_evento`: eventos del equipo (`mobileJourneyStarted`,
  `mobileJourneyEnded`, ...), particionado por mes. La clave lógica de
  dedupe es `(dispositivo, tipo, atributos->>'journeyId')`.

**Telemetría (`telemetry`)**

- `dmt_bateria`: muestras de batería (`porcentaje`, `cargando`,
  `registrado_en`).
- `dmt_senal`: muestras de señal/red (tipo red, gps, mqtt, app, otra).
- `dmt_salud_dispositivo`: buckets de salud del teléfono (fabricante, modelo,
  Android, versión de app, FGS, cola de salida, etc.). Es la materia prima de
  los diagnósticos.

**Operación (`operations`)**

- `dmt_asignacion`: vínculo persona-equipo con historia. `activa = true` y
  vigencia (`desde_en <= now() < hasta_en` o `hasta_en` nulo) son las
  condiciones de visibilidad en todas las pantallas.
- `dmt_jornada`: turnos por equipo. Estados `abierta`, `cerrada`, `anulada`;
  guarda inicio, fin, duración y batería de inicio/fin. El `usuario_id` sale
  de la asignación activa al abrir.
- `dmt_jornada_tramo`: tramos movimiento/pausa/sin señal dentro de una
  jornada. Reservada para cuando el motor de tracking la produzca; hoy los
  reportes y el Replay derivan los tramos del histórico.
- `dmt_alerta`: alertas operativas (recuperación, mensajería móvil, reglas).
  El ack de recuperación del teléfono también se registra aquí.

**Auditoría y sistema (`audit`, `system`)**

- `dmt_auditoria`: una fila por acción administrativa, con el detalle variable
  en `datos`. La API la escribe siempre; un fallo de auditoría no tumba la
  operación.
- `dmt_configuracion`: parámetros de la plataforma.
- `dmt_version_esquema`: qué archivos de esquema se aplicaron y con qué
  checksum (lo muestra Sistema).
- `dmt_migracion_mapa`: diccionario del sistema anterior al nuevo.

### 2.3 Invariantes que sostienen el modelo

1. **Identidad pública**: la API expone `id_publico` (UUID) y acepta también
   el id interno y, durante la transición, el id legado. Ningún DTO devuelve
   nombres de tablas ni columnas internas.
2. **Baja lógica**: `d.habilitado = false` no borra nada. Un equipo dado de
   baja no aparece en ninguna lista, conteo ni selector; su histórico queda
   consultable solo por administración (lectura directa por id).
3. **Asignación con historia**: nunca se borra una fila de
   `dmt_asignacion`; se cierra (`activa = false`, `hasta_en = now()`).
4. **La posición viva solo avanza**: tanto la última posición como
   `ultima_conexion_en` se actualizan solo si el fix es más nuevo.
5. **Ingesta idempotente**: reintentar un lote es seguro; el servidor
   clasifica cada evento como `accepted`, `duplicate`, `invalid` o `dead`.
6. **Nada se borra por red**: si la subida falla, el teléfono conserva los
   fixes en su cola local con su identidad por fila.
7. **Particiones mensuales**: `dmt_posicion` y `dmt_evento` se particionan por
   mes; el servicio precrea el mes actual y los dos siguientes, y además crea
   la partición al escribir como red de seguridad.
8. **Fechas válidas**: un fix fuera de `[ahora − 30 días, ahora + 24 horas]`
   se rechaza sin guardarse (reloj corrupto) para no contaminar particiones.

---

## 3. Reglas de negocio por pantalla

### Inicio

- Muestra la cola de auditoría del día: total de unidades visibles, cuántas en
  línea, detenidas y sin señal/débil, y cuántas con batería al 20 % o menos.
- Lista "Jornadas de hoy": quién abrió turno, a qué hora, cuándo terminó (o
  "En curso") y cuánto duró; desde ahí se salta a Auditar ese día.
- Lista "Salud de la flota" con el estado y la causa en español (por ejemplo
  "Último GPS hace 8 min" o "Sin permiso de ubicación precisa"), nunca un
  estado mudo.
- Lista "Requieren atención": sin reportar, batería crítica y equipos fuera
  de jornada (en ese orden de prioridad). Menos de 20 % de batería y más de 5
  minutos sin reportar cuentan.
- Solo aparecen equipos habilitados y visibles para la cuenta. Un equipo dado
  de baja no cuenta ni se lista, ni aunque la caché del navegador conserve una
  respuesta anterior.

### En vivo

- Mapa con un marcador por equipo visible y con posición conocida, más la
  lista lateral con búsqueda y filtros: Todas, En línea, Detenido, Sin
  señal/débil y Deshabilitado.
- "Deshabilitado" en este filtro significa equipo habilitado pero fuera de
  jornada (el teléfono cerró el turno); es distinto de un equipo dado de baja,
  que no llega a la pantalla.
- El estado se refresca cada 5 segundos; al volver a la pestaña se reconsulta
  de inmediato y con la pestaña oculta se detiene el sondeo.
- El popup y la ficha de cada unidad muestran su última posición, hora del
  último fix, batería y si la jornada está activa.
- Un equipo sin posición conocida aparece en la lista ("Sin posición
  conocida") pero no tiene marcador en el mapa.

### Replay

- Se elige una unidad (solo equipos visibles) y un rango de fechas; por
  defecto, el día de ayer.
- El recorrido se dibuja en tres capas explícitas y rotuladas:
  - **REAL**: los fixes registrados, línea sólida con color según velocidad y
    flechas de dirección.
  - **MATCHED**: huecos con observaciones suficientes, ajustados a vía;
    línea continua fina con etiqueta "Ajustado a vía".
  - **ESTIMATED**: huecos sin observaciones, ruta A→B; punteado gris con
    etiqueta "Tramo estimado".
- Un tramo reconstruido nunca se pinta como GPS registrado. Si el ruteo no
  responde, ese tramo queda crudo (no se inventa camino).
- Los huecos (más de 10 minutos sin fixes) se marcan como sin señal; el
  reproductor tiene saltos de hueco y no interpola movimiento dentro de uno.
- Las paradas se listan con duración y dirección (cuando el geocodificador la
  conoce). Si el servidor no responde, el Replay las deriva del propio
  recorrido como respaldo.
- Se muestran también los marcadores de jornada (inicio y fin) del rango y un
  resumen del recorrido: distancia, duración, velocidad promedio y máxima,
  batería inicial y final.
- El Replay es de lectura: ver el recorrido no cambia ninguna posición
  registrada.

### Usuarios

- Lista las cuentas del plantel: cuenta, nombre completo, teléfono, puesto,
  grupo(s), equipo(s) y estado (Habilitado / Deshabilitado). Primero las
  habilitadas; las dadas de baja van al final.
- **Agregar cuenta** crea la persona y, en el mismo acto, su equipo de
  rastreo: mismo nombre, identificador igual al usuario en minúsculas, nacen
  habilitados y vinculados, de modo que la persona aparece en En vivo y
  Replay. La cuenta de campo no lleva roles ni permisos especiales.
- **Cambiar los datos** permite editar nombre, teléfono, puesto, grupo(s) y
  contraseña. La contraseña vacía significa "no cambiarla".
- **Ajustes de la aplicación**: los parámetros propios de esa persona (ritmo
  de envío, distancia mínima, precisión, guardado sin conexión, etc.). La app
  los recibe al iniciar sesión; si no define ninguno, usa los valores
  generales.
- **Dar de baja** deshabilita la cuenta (no la borra): se le revocan las
  sesiones y se desactivan sus asignaciones. La reactivación es la vuelta
  atrás.
- Solo administración ve y opera esta pantalla.

### Grupos

- Un grupo es una agrupación de personas con nombre (único) y descripción.
- **Agregar grupo**, **editar**, **eliminar** (las personas no se borran) y
  **marcar quiénes pertenecen**: se guarda la lista completa de integrantes.
- Los grupos no otorgan visibilidad ni permisos: sirven para organizar el
  plantel en el panel (por turno, zona o equipo de trabajo).
- Solo administración ve y opera esta pantalla.

### Reportes

- Tres pestañas por unidad y rango de fechas: **Viajes**, **Paradas** y
  **Resumen**; por defecto, los últimos siete días.
- Un **viaje** es un tramo en movimiento con duración, distancia, velocidad
  promedio y máxima, origen, destino y número de paradas intermedias.
- Una **parada** es una presencia quieta de al menos 3 minutos (menos de
  5 km/h), con dirección cuando existe. El servidor fusiona fragmentos del
  mismo sitio para que una parada real no salga partida en decenas.
- El **Resumen** agrega por equipo: distancia, duración, viajes, paradas,
  total de posiciones y hora de la última; y da los totales del rango.
- Se puede elegir una unidad o "Todas". Solo se listan equipos visibles.
- La lógica de viajes y paradas se calcula del histórico con reglas fijas;
  no se modifica el crudo.

### Sistema

- Muestra la disponibilidad del servicio (proceso API, base de datos y motor
  de seguimiento), con una frase que explica qué implica la caída de cada
  pieza, y se refresca cada 15 segundos.
- Muestra versión de la plataforma, versión de API, versión del esquema,
  commit y hora de la última comprobación. La versión del panel no se
  gestiona aquí: el panel no tiene versión propia.
- Lista las **cuentas de administración** (las que tienen permiso alto) y
  permite agregar una nueva y dar de baja o reactivar las existentes.
- Regla dura: la instancia nunca puede quedarse sin al menos un
  administrador activo. La última cuenta de administración no se puede
  deshabilitar, eliminar, ni degradar.
- Solo administración ve y opera esta pantalla.

### Configuración

- Parámetros de la **aplicación móvil por equipo**: cada cuánto envía la
  posición, espera mínima, distancia y giro para volver a enviar, precisión
  del GPS, guardado sin conexión, tope del búfer, política de descarte,
  tiempo de espera de confirmación, reintentos, etc.
- Cada parámetro se envía solo si cambió; la app los recibe por su canal
  (consulta periódica y también al iniciar sesión). Sin definir, aplica el
  valor general de la plataforma.
- Solo administración edita; el nombre del equipo también se cambia desde
  aquí.
- El equipo dado de baja no se edita ni se reasigna hasta reactivarlo.

### Pantallas de apoyo

- **Detalle (unidad)**: ficha del equipo con su estado, habilitación, batería
  y última posición, accesos a su recorrido y a su historial.
- **Batería**: estado de batería de la flota y serie de muestras por unidad
  en el rango elegido.
- **Auditoría**: jornadas del rango por equipo y expediente del día con
  paradas y recorrido.

---

## 4. Ciclos de vida

### 4.1 Persona y su equipo

**Crear persona (desde Usuarios)**

1. Se escribe el nombre para entrar (el usuario), la contraseña, el nombre
   completo y, si se quiere, teléfono, puesto y grupo(s).
2. En una sola transacción el servidor crea la cuenta **y** su equipo de
   rastreo:
   - cuenta habilitada, sin roles de campo;
   - equipo habilitado con identificador = usuario en minúsculas y nombre =
     nombre de la persona;
   - asignación activa y vigente entre ambos.
3. Si el identificador del equipo ya existe, no se crea nada (ni cuenta ni
   equipo): la operación completa se deshace.
4. La persona queda visible de inmediato en En vivo y en los selectores de
   Replay; sus posiciones empiezan a llegar cuando el teléfono reporte con ese
   identificador. En Replay el recorrido existe recién desde el primer fix.

**Baja de persona**

- Es lógica y reversible: la cuenta queda `habilitado = false`, se revocan
  todas sus sesiones y se desactivan sus asignaciones (con `hasta_en`).
- No se borran ni la persona ni su historial. La persona deja de poder entrar
  (panel y teléfono) y deja de estar vinculada a su equipo.
- La regla del último administrador también protege las bajas: no se puede
  dar de baja a la última cuenta de administración activa, ni eliminarse uno
  mismo.

**Reactivar persona**

- Vuelve a habilitar la cuenta y sus sesiones nuevas ya funcionan.
- Es el camino de vuelta; en el panel dice "Reactivar", no "Dar de alta".

**Baja de equipo**

- Un equipo con `habilitado = false` no aparece en ninguna lista, conteo,
  selector ni mapa del panel (flota, En vivo, Replay disponible, jornadas,
  reportes, batería, salud, equipos por usuario). La ingesta de sus datos en
  el canal móvil se descarta sin guardar (`dead`, o `200` sin guardar en el
  protocolo antiguo).
- La administración sí puede abrir por id el histórico de un equipo dado de
  baja para auditar lo que registró antes (replay, jornadas, posiciones,
  batería); las demás cuentas reciben "no existe".
- El equipo dado de baja no se edita ni se reasigna; se reactiva para
  volver a operarlo.

**Regla de visibilidad (vale para todas las pantallas)**

- Un equipo se ve solo si está habilitado **y** existe una asignación activa
  y vigente para la cuenta (las cuentas de administración ven todos los
  equipos habilitados).
- Las listas y lo vivo respetan esa regla; la lectura directa por id del
  histórico de un equipo dado de baja es exclusiva de administración.

### 4.2 Sesión en el teléfono

- **Iniciar sesión con una cuenta**: la app manda usuario y clave; el
  servidor responde token, nombre y **el equipo de la persona**. La app adopta
  ese identificador y desde ahí su ruta queda bajo su equipo.
- **Sin cuenta o sin equipo vinculado** (flota antigua): la app sigue
  funcionando con la clave compartida y conserva su identificador actual; no
  se bloquea la operación.
- **Cambiar de cuenta**: si hay una sesión de otra persona, primero corre el
  cierre limpio con el equipo anterior:
  1. se congela la captura nueva;
  2. se vacía lo pendiente por lotes (cada lote viaja con el equipo con el
     que se capturaron sus filas, nunca con el equipo nuevo);
  3. se cierra la jornada abierta (si el servidor ya la tenía cerrada, no es
     error);
  4. se limpia la sesión y se abre el login.
  Lo que no se pudo enviar **no se borra**: queda en la cola del teléfono con
  su identidad por fila y drena al volver la red o al entrar de nuevo. El
  cambio de cuenta continúa aunque quede algo pendiente, y se avisa.
- **Rastro nunca mezclado**: cada fix guarda en la cola local el equipo con
  el que se capturó (`device_id` por fila). La subida agrupa por ese equipo y
  manda cada grupo con su propio identificador. Capturar con una cuenta y
  subir con otra no duplica ni mezcla rutas.
- **Sesión vencida o revocada**: ante un 401 con token, la app limpia la
  sesión y pide login de nuevo; mientras tanto sigue operando con la clave
  compartida y la captura local intacta.
- La app móvil usa la misma tabla de sesiones que el panel (`tipo = movil`).
  Al deshabilitar la cuenta, su token deja de valer.

### 4.3 Captura, cola y recuperación (teléfono)

- La captura vive en un servicio en primer plano con notificación mientras
  haya jornada abierta; el arranque inicial nace siempre de una acción visible
  de la persona.
- Una máquina de estados de movimiento decide la cadencia fina o lenta
  combinando velocidad GPS, desplazamiento real, sensores de movimiento e
  historial. Ante la duda, captura fina.
- Filtros de captura descartan ruido (teleport, deriva en parado, fix
  duplicado) sin tocar la cadencia; lo descartado es ruido, no dato perdido.
- La cola de subida es serial: lee, envía, confirma y borra. Clasifica la
  respuesta del servidor: 2xx confirmado; 400/404/413/422 descartado y
  contabilizado; 401 pausa con aviso; 408/429/5xx y errores de red reintentan
  con espera creciente y jitter. Si el servidor aún no tiene el endpoint de
  lotes, cae al envío de a uno (protocolo antiguo) sin cambiar la identidad.
- El búfer local tiene tope (5.000 posiciones, se descartan las más viejas) y
  el descarte ya no es silencioso: se cuenta y se reporta en diagnóstico.
- La recuperación (alarma del sistema cada ~9 minutos, push o arranque)
  despierta la captura, pide un fix fresco y vacía la cola; nunca inventa
  posiciones.

### 4.4 Jornada

- **Abrir jornada**: la persona toca el botón en el teléfono. La app manda
  `start` con un identificador de jornada (la hora de inicio en milisegundos)
  y guarda localmente que quedó abierta. El servidor cierra cualquier jornada
  abierta previa del equipo y abre la nueva, tomando el `usuario_id` de la
  asignación activa.
- **Cerrar jornada**: `stop`. El servidor cierra la jornada abierta con fin,
  duración y batería final; si ya estaba cerrada, no es error.
- **Reconciliación**: al arrancar el teléfono (o el servicio), la app
  pregunta al servidor la verdad de la jornada (`GET /journey`) y decide:
  - si local está abierta, no la pisa;
  - si local está cerrada o vacía y el servidor la tiene abierta, la adopta;
  - si el servidor no responde, conserva lo local.
- **Reconciliación del servidor**: al arrancar (y cada hora) el servidor crea
  la jornada abierta de los equipos que la iniciaron antes de un corte y
  cierra por timeout las que llevan 12 horas sin actividad (posición ni
  conexión). Una jornada nunca queda eternamente abierta.
- La jornada es una ventana de auditoría y orden; el recorrido, en cambio,
  son las posiciones registradas, existan o no jornadas.

### 4.5 OTA (actualización de la app)

- Cada versión publicada de la app mayor a la instalada enciende el **banner
  de actualización** en el teléfono: al abrir la app, al volver a ella y
  mientras esté abierta (con freno anti-spam de un minuto). No existe "ya
  visto": si no se actualizó, el banner vuelve.
- El teléfono consulta el manifiesto (`version`, `versionCode`, `url`,
  `sha256`, notas) y compara códigos de versión: sin downgrade.
- La política de despliegue del servidor decide si este equipo recibe el
  manifiesto: pausa, porcentaje estable por identificador (misma decisión
  siempre para el mismo equipo), versión mínima forzada y lista blanca.
- Si el teléfono no puede alcanzar el canal del servidor, cae al canal de
  respaldo de releases para no quedar a ciegas.
- El panel no tiene versión ni OTA: se actualiza junto con el servidor.

### 4.6 Datos: particiones y retención

- `dmt_posicion` y `dmt_evento` crecen por mes; el servicio precrea el mes
  actual y los dos siguientes y, además, crea la partición al escribir.
- Por defecto se conserva el histórico completo (decisión del dueño); el
  mantenimiento vigila el tamaño por partición.
- Los fixes con fecha absurda se rechazan; los registros del sistema anterior
  se conservan con `id_legado` para trazabilidad.

---

## 5. Qué está decidido en ADR y no se toca

Estas decisiones están cerradas y este documento no las reabre; solo las
resume para que el modelo se lea completo.

- **ADR-001 — Motor de tracking**: el motor oficial de captura es el
  FusedLocationProviderClient (flavor `google`); el flavor `regular` queda
  solo para pruebas locales.
- **ADR-002 — Foreground Service tipo location**: el tracking vive en un
  servicio en primer plano con notificación mientras haya jornada; el arranque
  inicial siempre nace de una acción visible de la persona.
- **ADR-003 — AlarmManager solo para rescate**: una alarma cada ~9 minutos
  despierta para pedir fix fresco y vaciar la cola; no es el reloj del
  tracking y no se promete cadencia de 5 minutos.
- **ADR-004 — Almacén local durable + cola offline**: captura primero en
  disco, subida después; la identidad local gana `boot_id`, secuencia,
  jornada, proveedor y estado; el tope del búfer reporta su desborde.
- **ADR-005 — Ingesta idempotente**: la identidad estable es
  `(dispositivo, boot_id, secuencia)`; reintentar es seguro y la posición
  viva nunca retrocede.
- **ADR-006 — Máquina de estados de movimiento**: la cadencia la decide una
  máquina formal con GPS, desplazamiento, sensores e historial; el
  acelerómetro es un indicio más, nunca el único.
- **ADR-007 — Semántica del Replay**: tres capas rotuladas REAL, MATCHED y
  ESTIMATED; un tramo reconstruido no se presenta jamás como GPS registrado.
- **ADR-008 — GraphHopper**: es el único motor geográfico; `/match` cuando hay
  observaciones, `/route` para huecos sin ellas; sin respuesta el tramo queda
  crudo; el crudo registrado no se altera.
- **ADR-009 — Diagnóstico de salud**: el servidor deriva el estado por equipo
  (`HEALTHY`, `DEGRADED`, `OFFLINE`, `RECOVERING`, `MISCONFIGURED`) y el panel
  lo muestra con causa en español.
- **ADR-010 — Particionado y retención**: PostgreSQL con particiones
  mensuales, precreación proactiva, validación de fechas y retención completa
  por defecto; sin PostGIS.
- **ADR-001 (nomenclatura) — `dmt_*` en español**: la base nueva usa esquemas
  y nombres propios; el contrato con la app no cambia y el legado se conserva
  como respaldo de solo lectura.
- **ADR-002 (API) — `/api/v1` propia**: versionado en la ruta, DTOs en
  español, cookie de sesión `HttpOnly`, errores normalizados, sondeo del panel
  cada ~5 segundos y la Web sin conocimiento de tablas ni reglas de negocio.

---

## 6. Fuentes

- Servicios: `services/api/src/` (cuentas, usuarios, grupos, flota, jornadas,
  replay, posiciones, reportes, segmentos, ruteo, salud, sesiones, permisos,
  esquema), `services/tracking/src/` (movil, osmand, db), `services/recuperacion/src/`.
- Aplicación: `fallback/app/src/main/java/org/traccar/client/` (sesión,
  cierre limpio, cola, captura, jornada, OTA, movimiento, recuperación).
- Panel: `apps/web/src/paginas/` (Inicio, EnVivo, Replay, Historial, Detalle,
  Batería, Reportes, Usuarios, Grupos, Configuración, Sistema).
- Base: `database/schema/` y `database/migrations/`.
- Decisiones: `docs/ADR/` y `docs/architecture/adr/`.
