# Voz y textos del panel — DMujeres Tracking

Guía de redacción para todo texto visible del panel web (`apps/web/src`). El
objetivo es que la interfaz suene a la operación real de DMujeres: clara para
quien trabaja con motos y cuadrillas, sin jerga de sistemas y sin frases de
folleto. Complementa a `docs/BRANDING.md` (identidad visual): aquí solo se
decide **qué se dice**; los colores, tipografías y componentes viven en la otra
guía.

## 1. Principios de voz

1. **Operativa**: se escribe para alguien que está en la calle o en la central a
   mitad de la jornada. Frases cortas, sujeto concreto, sin rodeos.
2. **Inequívoca**: cada texto dice qué es, sobre qué equipo o persona, y qué
   pasa si se toca el botón. Nada de "procesar", "gestionar", "configurar"
   cuando se puede decir "guardar", "cambiar", "dar de baja".
3. **Explicativa con la acción**: el operador debe saber el efecto antes de
   confirmar. "La persona dejará de poder entrar" en vez de "¿Confirmar?".
4. **Sin adornos de IA**: nada de "¡Genial!", "Oops", "¿Listo para…?", signos de
   admiración múltiples, emojis ni ánimo impostado.
5. **Honesta con el dato que falta**: si no hay dato se muestra "—"; si el
   servicio falla, se dice qué dejó de funcionar y qué puede hacer la persona.
   Nunca se inventan valores ni causas.
6. **Un concepto, una palabra**: el glosario de la sección 3 manda. No se
   alternan sinónimos "para variar".

## 2. Tono

- Trato directo y neutro ("Entra con tu correo y tu clave", "Revisa las
  fechas"), sin tuteo efusivo ni usted acartonado.
- Los mensajes del sistema hablan de hechos, no de sentimientos ("No se pudo
  cargar la información", no "Algo salió mal").
- Las confirmaciones celebran lo justo: informan el resultado y su efecto. El
  trabajo ya está hecho; no hay que felicitar a nadie.
- Sin promesas comerciales dentro del panel. La marca ("DMujeres Tracking",
  "Operación de motos y cuadrillas") se presenta en el acceso, no se repite en
  cada pantalla.

## 3. Glosario unificado (léxico canónico)

| Concepto | Palabra única en la UI | Se evita en textos visibles | Por qué |
| --- | --- | --- | --- |
| Teléfono con la app de rastreo | **equipo** | "dispositivo", "unidad", "teléfono" | Es la palabra del dueño; "unidad" era herencia del panel viejo y "dispositivo" es de sistemas. |
| Quien hace ruta | **persona** | "usuario", "recurso", "operador" (como sujeto genérico) | Las personas tienen nombre; "usuario" solo vive en el nombre de la página de administración. |
| Credencial de acceso | **cuenta** | "usuario" en frases, "login" | La cuenta se crea, se da de baja y se reactiva; la persona existe aunque la cuenta esté dada de baja. |
| Conjunto de equipos | **flota** (colectivo) | "unidades", "dispositivos" | "Flota" agrupa; para uno solo, "equipo". |
| Turno de trabajo | **jornada** | "turno" (salvo nombre propio de grupo), "sesión", "ciclo" | Es la palabra del dueño y la que usa la app móvil. |
| Trayecto registrado | **recorrido** | "ruta", "trayecto", "traza", "track" | "Ruta" queda reservada a nombres de zona ("Zona norte"), no al trazo GPS. |
| Parte del recorrido | **tramo** | "sección", "segmento" | Término del modelo de operación (`dmt_jornada_tramo`). |
| Punto GPS y su envío | **posición** / **reporte** | "fix", "ping", "paquete" | "Fix" era jerga interna y aparecía en la UI. |
| Estado del equipo | **En línea, Detenido, Sin señal, Señal débil, Fuera de jornada** | "OK", "degradado", "habilitado/deshabilitado" como estado | Se dice la situación operativa, no la etiqueta técnica del API. |
| Estado de la cuenta | **Activa / Dada de baja** | "habilitada/deshabilitada", "borrada" | La baja es reversible: siempre se puede reactivar. |
| Baja y retorno | **dar de baja**, **reactivar** | "desactivar", "eliminar", "dar de alta otra vez" | Vocabulario del dueño; "eliminar" se guarda para datos que sí se borran (grupos). |
| Ver el presente | **En vivo** | "tiempo real", "live", "monitoreo" | Nombre de la página. |
| Ver el pasado | **Replay** | "repetición", "reproducción histórica" | Nombre de la página. |
| Consulta histórica | **Historial** | "auditoría", "auditar", "expediente" | Nombre de la página; "auditar" sonaba a oficina legal. |
| Parada del recorrido | **parada** | "detención" | Una sola palabra para el mismo hecho. |
| Ventana de fechas | **rango** (Desde / Hasta; "Hoy", "Ayer", "Hoy y ayer") | "período" mezclado con "rango" | Un solo término para el mismo control. |

## 4. Cómo escribir errores

Fórmula: **qué pasó + qué puede hacer la persona**. Sin códigos HTTP, sin
nombres de servicios internos, sin culpar al operador.

- Si es un fallo de red del panel:
  `No hubo respuesta del servidor. Inténtalo de nuevo en unos momentos.`
- Si el dato no llegó:
  `No se pudo cargar la información. Inténtalo de nuevo.`
- Si una dependencia no responde (página Sistema):
  `El servicio no responde: el panel no puede cargar ni guardar datos.`
- Si la consulta se cancela:
  `Se canceló la consulta.`
- Los mensajes que ya redacta el servidor viajan tal cual (`ApiError.message`):
  el panel solo escribe el respaldo, nunca reescribe el texto recibido.

### Nunca

- Números de error solos ("Error 500") ni rutas internas.
- "Algo salió mal", "Oops", "Ups".
- "Inténtalo más tarde" sin decir qué quedó a medias.
- Culpar ("Escribiste mal…"): se describe el requisito
  ("La contraseña debe tener al menos 8 caracteres.").

## 5. Cómo escribir confirmaciones

Fórmula: **qué quedó hecho + efecto visible** (si lo hay). Sin exclamaciones.

- `Cuenta y equipo creados. “María P.” ya aparece en En vivo y Replay.`
- `Cuenta dada de baja. Puedes reactivarla cuando la necesites.`
- `Grupo eliminado. Las personas no se borran.`
- `Cambios guardados.`

Las acciones destructivas o de baja se explican en el diálogo **antes** de
confirmar, con la misma fórmula: `¿Dar de baja a María P. (maria.p)? La persona
dejará de poder entrar; sus datos y su equipo se conservan, y puedes reactivar
la cuenta cuando la necesites.`

## 6. Vacíos, ayudas y botones

- **Vacíos**: describen la ausencia y, si aporta, la causa.
  `Ningún equipo abrió jornada hoy.` / `No hay viajes en el rango.` /
  `No hay equipos asignados a esta cuenta.`
- **Ayudas**: explican la consecuencia, no el mecanismo.
  `Solo se guardan los campos que cambies; un campo vacío queda sin definir.`
- **Placeholders**: ejemplo real y breve.
  `nombre@dmujeres.local`, `Por ejemplo: maria.p`, `Por ejemplo: Zona norte`.
- **Botones**: verbo en infinitivo o imperativo directo, sin "OK".
  `Entrar`, `Guardar cambios`, `Dar de baja`, `Reactivar`, `Ver jornada`,
  `Ir a Replay`, `Centrar flota`, `Descargar el recorrido (CSV)`.
- **Chips y etiquetas**: sustantivo o participio corto, en frase normal
  (`En línea`, `Sin señal`, `Dada de baja`), nunca en mayúsculas completas
  dentro del texto.
- **Sin dato**: siempre `—`; jamás 0, "N/D" ni un texto inventado.

## 7. Accesibilidad

- Los `aria-label` se escriben como la acción, no como el icono:
  `Dar de baja a María P.`, `Tramo sin datos anterior`, `Buscar equipo`.
- Se conservan los roles (`role="alert"`, `role="status"`) y el texto visible
  es el mismo que anuncia el lector de pantalla; no se duplica información.
- Las unidades abreviadas (`h`, `min`, `km/h`, `%`) se mantienen; el nombre
  accesible va en el texto completo cuando aporta (`Velocidad promedio`).

## 8. Decisiones del test de coherencia

Cambios hechos para unificar conceptos que aparecían con varios nombres:

1. `dispositivo` / `unidad` / `equipo` → **equipo** en toda la UI.
2. `ruta` / `trayecto` / `recorrido` → **recorrido** (y "ruta" solo en
   nombres de zona, p. ej. `Zona norte`, para no confundir con el trazo GPS).
3. `fix` / `GPS` como sujeto → **posición** / **reporte** ("Última posición",
   "Batería en la última posición").
4. `detenciones` → **paradas**.
5. `Auditoría` / `Expediente` → **Historial** y **jornada**
   (`Jornadas del día`, `Abrir el detalle de la jornada`, `Ver jornada`).
6. `Deshabilitado` (equipo) → **Fuera de jornada**; `Habilitado/Deshabilitado`
   (cuenta) → **Activa / Dada de baja**.
7. `usuario` en frases → **cuenta** (credencial) y **persona** (ser humano); la
   página de administración conserva su nombre "Usuarios".
8. `SIN CONEXIÓN` (estado) → **Sin señal**, igual que el resto del panel.
9. `Carga` (columna de Batería) → **Cargando**, que es lo que responde la
   columna (Sí/No).
10. `OSM` → **OpenStreetMap** (sin siglas de sistemas en botones visibles).
11. `API`, `commit`, `tracking`, `degradado` (página Sistema) → **servicio**,
    **Código publicado**, **motor de seguimiento**, **Servicio con problemas**.

## 9. Antes y después (ejemplos representativos)

| Antes | Después |
| --- | --- |
| `Cola de auditoría del día` (Inicio) | `Resumen de la operación de hoy` |
| `No hay unidades visibles para esta cuenta.` | `No hay equipos asignados a esta cuenta.` |
| `Deshabilitado` (filtro de En vivo) | `Fuera de jornada` |
| `Último fix hace 8 min` | `Última posición hace 8 min` |
| `Auditoría` (título de página) | `Historial` |
| `Expedientes del día` | `Jornadas del día` |
| `GPS a pie (<8 km/h)` (leyenda de Replay) | `Recorrido a pie (hasta 8 km/h)` |
| `El rango de fechas no es válido.` | `Revisa las fechas: el inicio no puede ser posterior al fin.` |
| `No se pudo conectar con el servidor.` | `No hubo respuesta del servidor. Inténtalo de nuevo en unos momentos.` |
| `Disponibilidad degradada` (Sistema) | `Servicio con problemas` |
| `mobile.intervalSeconds ya está definido y la API no permite eliminarlo` | `mobile.intervalSeconds ya tiene valor y no se puede quitar; escribe uno nuevo.` |
| `Auditoría de consumo: nivel de la flota y serie por unidad…` (Batería) | `Consumo de batería: nivel de la flota y detalle por equipo…` |
| `Habilitado / Deshabilitado` (chip de cuenta) | `Activa / Dada de baja` |

## 10. Textos que no se tocan

Quedan intactos por contrato, por venir de otro servicio o por no ser texto de
operador:

- **Claves de configuración** de Configuración (`mobile.intervalSeconds`,
  `mobile.accuracy`, etc.): son la etiqueta exacta del parámetro que viaja al
  servicio; cambiarlas confundiría al administrador. La ayuda de la página
  aclara que son los nombres que usa la aplicación móvil. Si el dueño pide
  nombres humanos, se agrega una capa de etiquetas sin tocar la clave.
- **Causas de salud del equipo** que envía el servicio (`Sin fixes
  registrados`, `Último GPS hace…`, `Recuperando continuidad (N pendientes)`,
  `Hueco de captura de…`): el panel las muestra tal cual desde `GET
  /api/v1/salud`; su redacción se corrige en `services/api`, no aquí. Es la
  única jerga técnica visible que queda y está registrada como pendiente.
- **Mensajes de error del API**: se muestran sin reescribir.
- **Respaldo del cliente**: cuando la API no manda mensaje, el panel responde
  `No se pudo completar la operación (código 500).` El código se conserva para
  soporte y no se compara por texto en ninguna parte.
- **Marcas**: "DMujeres Tracking" y la etiqueta corta `admin` del marco.
- **Rutas y parámetros internos** (`/unidad/:id`, `dispositivo=`, `desde=`,
  `hasta=`): no son visibles.
- **Error de desarrollo** de `ReproductorReplay` ("Los bloques de Replay van
  dentro de ReproductorReplay."): no es UI.

## 11. Checklist antes de fusionar un cambio de textos

1. ¿Usa el glosario de la sección 3 y un solo nombre por concepto?
2. ¿Dice qué pasará al tocar el botón (errores, confirmaciones, bajas)?
3. ¿Evita jerga técnica (`endpoint`, `token`, `payload`, `sync`, `fix`, `API`)
   y promesas de folleto?
4. ¿Está sin emojis, sin "¡…!" y sin ánimo impostado?
5. ¿Los vacíos dicen qué falta y los errores qué hacer?
6. ¿El `aria-label` coincide con la acción y el texto visible?
7. ¿`npm run build` (tsc + vite) pasa sin errores?
