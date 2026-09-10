# Manual de jornada normal — app DMujeres Tracking

Guía en lenguaje claro para **operadoras y colaboradoras**: qué hacer al
empezar y terminar tu jornada, qué hace la app mientras trabajas, qué ve la
persona del panel y qué hacer si algo falla.

> Idea general: durante tu jornada la app envía tu ubicación al servidor cada
> pocos segundos. La persona del panel te ve en el mapa. Si te quedas sin
> internet, la app **guarda** las ubicaciones y las envía cuando vuelve la
> conexión. No se pierde tu recorrido.

---

## 1. Inicio de jornada

### 1.1. Entra a la app

1. Abre la app **DMujeres Tracking**.
2. Escribe tu **usuario y contraseña** (te los entrega la administradora; ella
   da de alta tu acceso con el panel o con `create-collaborator.sh`).
3. Si es tu primera vez, completa el **onboarding**: la app te pide los
   permisos uno por uno. Acepta todos.

### 1.2. Permisos que debes aceptar

| Permiso | Por qué lo necesita |
|---|---|
| Ubicación precisa (mientras usas la app) | Para registrar tu recorrido |
| Ubicación en segundo plano / siempre | Para seguir registrando con la pantalla apagada |
| Notificaciones | Para mostrarte que la jornada está activa y avisarte de problemas |
| Ignorar optimización de batería | Para que el teléfono no apague la app a mitad de jornada |
| Inicio automático (reboot) | Para retomar la jornada si el teléfono se reinicia |

Sin el permiso de ubicación en segundo plano la jornada **no puede iniciar**:
verás el aviso *"Sin permiso de ubicación en segundo plano"*.

### 1.3. Pulsa INICIAR

Pulsa el botón **Iniciar jornada**. Verás:

- Una notificación permanente *"Jornada iniciada"* (no la quites: es lo que
  mantiene la app viva).
- El estado cambia a *tracking activo*.

### 1.4. Qué manda la app (sin que hagas nada)

- Cada **X segundos** (normalmente 10 s; la administradora puede ajustarlo
  entre 3 y 300 s) la app toma tu posición GPS y la publica por **MQTT** en:
  - Subida: `dmj/v1/devices/{tu-usuario}/telemetry` con calidad **QoS 1**
    (el servidor confirma la recepción).
  - Confirmación: el servidor responde en `dmj/v1/devices/{tu-usuario}/ack`.
    Solo cuando llega esa confirmación la app borra el punto de su cola.
- Cada mensaje lleva un **sobre (envelope)**: número de versión (`schema: 1`),
  tipo (`position` o `presence`), identificador único (`messageId`) y número
  de orden (`sequence`). Sirven para que el servidor **no guarde nada dos
  veces** aunque el mensaje llegue repetido.
- Si estás **quieta** (por ejemplo dentro de un edificio sin GPS), la app no
  inventa posiciones: envía un **heartbeat `presence`** con tu batería, tu tipo
  de red y tu estado. Así el panel sabe que sigues en jornada aunque tu punto
  no se mueva.
- Al iniciar se envía una señal especial `journeyStarted`: el panel te marca
  **EN LINEA** (o **DETENIDO** si aún no te mueves). Al finalizar, el panel te
  marca **DESHABILITADO**.

---

## 2. Durante el trayecto

### 2.1. Qué guarda el servidor

Por cada punto confirmado el servidor guarda **dos cosas ligadas**:

1. La posición en `tc_positions` (coordenadas, hora, velocidad…).
2. El registro del mensaje en `tc_mobile_messages` (estado `accepted` y enlace
   a la posición).

Antes de guardar comprueba el `sequence`/`messageId`: si ese mensaje ya se
guardó (por ejemplo porque se reenvió tras un corte), responde `duplicate` y
**no lo duplica**. El camino interno es:
`app → MQTT → validación → guardado atómico en base de datos → aviso por
WebSocket al panel`.

### 2.2. Si te quedas sin internet (modo offline)

- La app **sigue capturando** y guarda los puntos en una **cola local**
  (buffer) de hasta **5000 puntos** (≈ 14 horas a ritmo de 10 s).
- Cuando vuelve la conexión, la app **reenvía (replay)** todo lo guardado en
  orden, primero por MQTT y, si hace falta, por internet directo (HTTP).
- Si la cola se llena, según lo configurado: se **borra lo más antiguo**
  (`drop_oldest`, lo habitual) o se **pausa la captura** (`stop_capture`, la
  app te avisa).

Tú no tienes que hacer nada: solo vuelve a una zona con cobertura.

### 2.2b. Si la jornada no registra puntos (desde 1.0.58)

Si tu jornada está activa pero el panel no muestra tu recorrido:

1. Revisa que la **ubicación esté activada** y con permiso "Permitir siempre".
2. Revisa que la **batería esté en "Sin restricciones"** para esta app.
3. Sal al aire libre unos minutos (bajo techo el GPS tarda en afinar; la
   app igual registra esos puntos marcados como aproximados).
4. No borres los datos de la app ni la desinstales a mitad de jornada:
   la cola de puntos vive en el teléfono.
5. Si prendes el teléfono apagado, la jornada **se retoma sola** (desde
   1.0.59 es prioridad de arranque). En Diagnóstico verás cuántos
   satélites te ven ("GPS: 7 satélites en uso de 12"); si dice "bajo
   techo o sin vista al cielo", muévete al aire libre.

El panel ahora distingue solo la causa: si ves muchos recibidos pero
cero guardados, es el filtro; si ves cero recibidos, es el GPS o el
permiso (pregunta en Diagnóstico).

### 2.2b. La app te dice POR QUÉ no hay conexión

Desde la versión 1.0.54 la app distingue la causa y te la muestra en
lenguaje simple (no dice solo "sin conexión"):

| Lo que ves | Qué pasó | Qué hacer |
|---|---|---|
| Apagaste el WiFi | Lo desactivaste tú en Ajustes o acceso rápido | Toca "Abrir WiFi" y actívalo |
| Se perdió la señal WiFi | Te alejaste del router o falló | Acércate al router |
| Modo avión activado | Está activado | Desactívalo para seguir enviando |
| Sin cobertura | Túnel, edificio, carretera sin señal | Espera: guardamos tu recorrido y se envía solo |
| WiFi sin acceso / Sin internet | Conectada pero sin salida (portal cautivo, router sin internet) | Abre el navegador e inicia sesión si te lo pide |
| Revisa tus datos | Posible datos apagados o sin cobertura | Revisa que tus datos estén encendidos |

Importante: la app **nunca te acusa sin certeza** — cuando no puede saberlo
dice "posiblemente" o "revisa". Y aunque no haya internet, **todo se guarda**
y se envía solo al volver la señal.

### 2.2c. Confirmado vs posible (anti-trampas, desde 1.0.55)

En la campanita de Eventos cada causa sale marcada:

- **confirmado** = la app lo verificó (WiFi apagado por ti, modo avión,
  datos apagados con el permiso extra, sin chip).
- **posible** = deducción honesta sin certeza total (sin cobertura en
  carretera, datos apagados sin el permiso extra).

Para la certeza total en datos/chip/cobertura, en la app ve a
**Diagnóstico → Mejorar diagnóstico** y acepta el permiso de teléfono
(solo se usa para eso; no lee tu número ni IMEI). Sin ese permiso, la app
sigue funcionando igual pero marca "posible".

### 2.3. Qué ve la persona del panel

El estado de cada persona viene de su **app** (jornada activa o no), no de un
horario: si finalizaste la jornada, el panel lo sabe aunque el teléfono siga
con internet.

| Estado del chip/marcador | Color | Cuándo |
|---|---|---|
| **DESHABILITADO** | gris | Jornada finalizada o no iniciada (aunque haya internet) |
| **SIN SEÑAL** | naranja | En jornada pero mala conexión: túnel, edificio, ping alto (> 2 s), sin datos > 2 min, GPS impreciso (> 80 m) o red caída |
| **DETENIDO** | verde | En jornada, con buena señal pero sin moverse |
| **EN LINEA** | verde | En jornada, con buena señal y en movimiento |

- En tu fila además se ve: hace cuánto llegó tu último dato, puntos
  **pendientes de enviar** (aviso si hay más de 50, rojo si más de 100) y tu
  **batería** (solo si estás en jornada; se oculta si estás sin señal o
  deshabilitada).
- Todo se actualiza **en vivo**, sin recargar la página.

### 2.3b. Rutas limpias sin saltos (desde 1.0.56)

Bajo techo el GPS "miente" (deriva decenas de metros con accuracy que
parece buena). Para que la ruta se revise bien:

- La app no guarda puntos a menos de **15 m** del anterior y exige calidad
  mínima al primer punto; lo dudoso se **marca**, no se pierde.
- El servidor marca lo dudoso (`valid=false`) en vez de tirarlo.
- El panel **oculta por defecto** lo impreciso (accuracy > 80 m),
  colapsa las paradas en un punto y aplana los saltos de ida y vuelta;
  la insignia dice `mostrados / total · K ocultos`.
- En Preferencias → Mapa se puede cambiar el umbral o desactivar el filtro
  (mínimo 30 m: un umbral menor escondería ruta real y dibujaría zigzag).
- Las rutas viejas se marcaron igual (`valid=false` donde accuracy > 80),
  con respaldo previo de la tabla.

### 2.3c. Cómo se limpia la ruta en el panel (desde 1.0.69)

La ruta cruda trae ruido (rebotes GPS, duplicados, paradas). El panel la
limpia SOLO para dibujar, sin borrar nada:

- Quita duplicados exactos y puntos imprecisos, aplana picos imposibles.
- Las paradas largas se ven como un punto; el rodaje lento NO se esconde.
- Las rectas que saltan sobre datos ocultos **se cortan** (no se dibuja
  carretera donde no hay datos); las flechas siguen el rumbo del trazo.
- La insignia dice `mostrados / total · K ocultos` (pasa el mouse para el
  detalle). El trazo aplica un suavizado leve en esquinas cerradas
  (desvío máximo ~15 m, dentro del error GPS) para leerse como en el
  Traccar original, sin inventar calles.

---

## 3. Notificaciones y eventos (avisos automáticos)

El servidor vigila tu jornada y genera avisos que llegan al panel en vivo:

| Aviso | Cuándo salta | Qué significa |
|---|---|---|
| `mobileNetworkLost` | Llevas **2 min sin transmitir** y tu última red era wifi o datos | Perdiste conexión (túnel, zona sin cobertura…) |
| `mobilePossiblePowerOff` | Llevas 2 min sin transmitir y tu última red era *"sin conexión"* o no hay datos | Posible teléfono apagado o sin batería |
| `mobileGpsDisabled` / `mobileGpsReenabled` | Apagas / vuelves a encender el GPS | El panel sabe que fue una acción manual, no un fallo |
| `mobileBatteryCritical` | Tu batería baja del **10 %** | Pon a cargar el teléfono cuanto antes |
| `mobileWifiLost` / `mobileNetworkRestored` | Cambias de wifi a datos o recuperas conexión | Informativo |
| `mobileJourneyStarted` / `mobileJourneyEnded` | Inicias / terminas jornada | Informativo; al terminar, el panel te pasa a DESHABILITADO |

Notas:

- El control de silencio revisa cada 30 s y no repite el mismo aviso antes de
  15 min.
- Los avisos de batería y red también se actualizan con cada punto y cada
  heartbeat, aunque el punto se filtre por estar quieta.
- El servidor guarda en tu ficha (base de datos) el estado de jornada y la
  calidad de conexión (`mobile.journeyId`, `mobile.rttMs`, `mobile.signal`,
  `mobile.degraded`, `mobile.journeyEndedAt`, `mobile.netCause`,
  `mobile.validated`, `mobile.causeAt`): por eso el panel sabe si estás
  deshabilitada o sin señal incluso tras reiniciar el servidor.

---

## 4. Fin de jornada

1. Pulsa el botón **FINALIZAR / STOP** en la app.
2. La app hace el **cierre** sola (tarda unos segundos):
   - Reenvía por internet directo todo lo pendiente (hasta **90 segundos**
     intentándolo).
   - Envía la señal `presence` con `journeyEnded`: el panel te marca
     **fuera de jornada / offline** y deja de esperar tus puntos.
   - Desconecta y muestra la notificación de cierre.
3. Verás el **resumen diario**: duración, kilómetros, puntos registrados y
   puntos confirmados por el servidor. Ejemplo: *"Jornada finalizada: 6 h
   20 min, 12,4 km, 2100 puntos (2098 confirmados)"*.
4. Si la app se cerró sola justo al pulsar finalizar, el cierre **queda
   pendiente y se completa al reabrir**: tu recorrido no se pierde.

> Importante: no basta con cerrar la app con el botón del teléfono: hay que
> pulsar FINALIZAR para que el panel registre el fin de jornada.

---

## 5. API clave y códigos (para soporte/administración)

### Tópicos MQTT

- `dmj/v1/devices/{id}/telemetry` — app → servidor (QoS 1).
- `dmj/v1/devices/{id}/ack` — servidor → app.

### HTTP (mismo sobre, misma anti-duplicación)

- `POST /api/mobile/provision` — **solo admin**. Da de alta a una
  colaboradora: crea el dispositivo (`uniqueId = usuario`) y su usuario MQTT.
  Ejemplo:
  `{"username":"ana.perez","password":"…","intervalSeconds":10,"bufferMax":5000,
  "bufferPolicy":"drop_oldest","ackTimeoutSeconds":15,"maxRetries":30}`.
- `POST /api/mobile/v1/positions` — fallback de la app. Lote JSON de sobres,
  cabecera `X-Api-Key`. Responde por punto. Si el servidor está saturado
  devuelve `503 + Retry-After`: la app espera y reintenta (no es un error
  tuyo). Detalle técnico en `server/openapi.yaml`.

### Códigos de confirmación (ACK)

| Código | Significado | Qué hace la app |
|---|---|---|
| `accepted` | Punto guardado | Lo borra de la cola, suma 1 confirmado ✅ |
| `duplicate` | Ya estaba guardado | Lo borra (no duplica) ✅ |
| `rejected` / `invalid` / `expired` | Rechazado definitivo (dispositivo desconocido, datos inválidos, muy antiguo) | Lo borra, no reintenta ⚠️ (avisar a soporte) |
| `pending` / `throttled` / `error` | Aún no procesado o servidor saturado | Lo **reintenta** más tarde 🔁 |

---

## 6. Troubleshooting (qué hacer si…)

| Problema | Qué ves | Qué hacer |
|---|---|---|
| **Sin internet** | Suben los "pendientes", luego se vacían solos | Nada: sigue tu ruta; al volver la cobertura se reenvía todo. Si no se vacían en 10 min con cobertura, abre la app y espera 1 min |
| **Batería baja** | Aviso de batería crítica en el panel; la app puede cerrarse | Carga el teléfono; no actives el "ahorro extremo"; verifica que la app esté excluida de optimización de batería |
| **GPS apagado** | Punto congelado + aviso `mobileGpsDisabled`; heartbeat sigue llegando | Enciende Ubicación en ajustes; vuelve a la app y espera un fix (30–60 s al aire libre) |
| **"Sin permiso de ubicación"** | La jornada no inicia | Ajustes → Apps → DMujeres → Permisos → Ubicación → **Permitir siempre**; reintenta Iniciar |
| **La app se cerró** | Notificación de jornada desaparecida | Reabre la app: la jornada se **retoma sola** (mismo `journeyId`); comprueba que la notificación volvió |
| **Tras reiniciar el teléfono** | Sin notificación | Abre la app una vez; el arranque automático retoma la jornada |
| **Punto "saltando" por la ciudad** | Recorrido con picos raros | Es el filtro anti-GPS-loco: el servidor descarta saltos imposibles (>162 km/h aprox.). Si es constante, avisa a soporte |
| **FINALIZAR no termina** | Sigue "finalizando" | Espera hasta ~2 min (está reenviando hasta 90 s + señal de cierre). No fuerces el cierre; si se queda colgado, reabre y vuelve a pulsar FINALIZAR |

¿Sigue fallando? Anota **hora, qué hiciste y qué mensaje viste** (o captura de
pantalla de la app / pantalla de Diagnóstico) y pásalo a soporte con tu
usuario. Con esos tres datos se localiza tu jornada en el servidor en minutos.
