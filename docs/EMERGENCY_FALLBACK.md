# Plan de emergencia — reemplazo de la app nativa por la app de respaldo

> **Qué es este documento.** Procedimiento operativo para sustituir, en un
> teléfono concreto, la app nativa (`mobile/`, paquete
> `com.dmujeres.traccar`, 1.1.22 / versionCode 132) por la **app de respaldo**
> basada en el cliente oficial de Traccar (fork en `fallback/`, commit base
> `190ad19`, Apache 2.0). No se toca código: solo instalación, configuración,
> verificación y rollback.
>
> **Decisión del dueño:** el reemplazo es total en el teléfono afectado, no
> convivencia. Mismo `applicationId com.dmujeres.traccar`, misma keystore
> (`mobile/keystore/debug.keystore` vía `mobile/keystore.properties`),
> `versionCode 200` / `versionName 2.0.0`, y `uniqueId macias` para que el
> historial del dispositivo continúe sin cambios en el panel.
>
> **Auditoría gráfica de la app de respaldo:** `docs/architecture/FALLBACK_APP.md`.

---

## 1. Estado actual (contexto verificado)

| Pieza | Estado |
|---|---|
| App nativa | `mobile/`, `com.dmujeres.traccar`, 1.1.22 (versionCode 132). Propia, no deriva del cliente oficial |
| Servidor | Fork de Traccar 6.14.5 (`server/`), despliegue systemd (`dmj-traccar.service`) + watchdog |
| App de respaldo | `fallback/`, clon del cliente oficial (commit `190ad19`), flavor `google` (Fused + Firebase). Capa DMujeres: branding, defaults, botones de jornada, FCM y OTA |
| Transporte de la respaldo | OsmAnd por HTTP a `http://68.168.20.219:5055` (`id/timestamp/lat/lon/speed/bearing/altitude/accuracy/batt`) |
| Endpoint de jornada | `POST /api/mobile/v1/journey` (`X-Api-Key`), implementado y probado en `server/src/main/java/org/traccar/api/resource/MobileJourneyResource.java` |
| Eventos del panel | `mobileJourneyStarted` / `mobileJourneyEnded` (los empareja `JourneysReportProvider`) |
| Registro de jornada activa | `MobileJourneyRegistry` (memoria del servidor) |
| OTA | `GET /api/mobile/v1/ota`, gobernado por `dashboard/public/rollout.json` (hoy `allow: ["macias"]`) |
| Métrica de verificación | `infrastructure/sql/cobertura30_diaria.sql` (mediana/p95, huecos > 120 s) y `v_device_funnel_daily` (solo app nativa) |
| Respaldo/recuperación | `backup.sh` (cron 03:00), `verify-backup.sh` (semanal), `restore.sh`, watchdog `dmj-traccar-watchdog.timer` |

---

## 2. Cuándo activar (criterios medibles)

Se activa **con uno solo** de estos criterios, sobre un equipo concreto
(nunca sobre toda la flota a la vez). Toda decisión se registra con fecha,
equipo, evidencia y responsable.

| # | Criterio | Umbral y ventana | Cómo se mide | Evidencia a archivar |
|---|---|---|---|---|
| C1 | Cobertura30 baja | **< 50 % en 2 días seguidos**, mismo equipo | Cociente de la métrica: segundos con `dt <= 30 s` en movimiento / `moving_seconds` de `v_device_funnel_daily` (SQL en `docs/audit/COBERTURA30_METRIC.md`). Apoyo: `infrastructure/sql/cobertura30_diaria.sql` (mediana y p95 del intervalo en movimiento, huecos > 120 s) | Captura de las 2 consultas con fecha y equipo |
| C2 | Jornada muda | **> 2 h sin ningún dato** con jornada activa y equipo encendido | Panel de alertas (`GET /api/admin/alerts`: "en jornada sin reportar") y última posición/salud en BD. Si la app no está reportando ni presencia, la alerta puede escalar antes de las 2 h | Hora del último dato, estado de batería, si el equipo estaba encendido |
| C3 | Crash-loop | **3 o más cierres anómalos en 1 h**, o el servicio en primer plano no sobrevive 10 min tras 2 intentos de reanudación | Crashlytics del fork / Sentry de la nativa y bitácora del teléfono | Captura del crash y versión instalada |
| C4 | Freeze OEM crónico | Equipo con `OEM_LIMITATION` documentada (ver `docs/audit/OEM_BACKGROUND_AUDIT.md`) y C1 o C2 repetidos 3 días | Igual que C1/C2 | Nota del responsable técnico; **requiere confirmación del dueño** |

**No** justifican el reemplazo por sí solos: un hueco aislado de minutos, una
tarde sin cobertura, batería baja, o un equipo que se apagó (eso se corrige con
la persona, no con la app). El objetivo del reemplazo es la continuidad del
registro, no "probar la app nueva".

---

## 3. Precondiciones antes de tocar un teléfono

Ninguna instalación empieza si estas verificaciones no están en verde.

| # | Verificación | Cómo | Resultado esperado |
|---|---|---|---|
| P1 | Puerto OsmAnd alcanzable desde datos móviles | Desde el teléfono (o una red externa): `curl -s -o /dev/null -w "%{http_code}" http://68.168.20.219:5055/` | Cualquier código HTTP (400/404/200) = alcanzable. Timeout = **bloqueado**: abrir el puerto en firewall **antes** de reemplazar |
| P2 | Endpoint de jornada vivo | `curl -s -o /dev/null -w "%{http_code}" -X POST http://68.168.20.219:999/api/mobile/v1/journey -H 'X-Api-Key: clave-mala' -H 'Content-Type: application/json' -d '{"deviceId":"macias","action":"start"}'` | `401` = vivo y con llave. `404` = canal apagado (`mobile.http.enable=false`): activarlo y reiniciar |
| P3 | Dispositivo existente | `SELECT id, name, uniqueid FROM tc_devices WHERE uniqueid = 'macias';` | Exactamente 1 fila. Si no existe, crearlo **antes** (el OsmAnd de un `id` desconocido responde 400 y no guarda nada) |
| P4 | APK correcto | `aapt dump badging <apk>` y `apksigner verify --print-certs <apk>` | `package: com.dmujeres.traccar`, `versionCode 200`, `versionName 2.0.0`, firmado con la misma clave que la flota (`mobile/keystore/debug.keystore`) |
| P5 | Publicación OTA | `cat dashboard/public/latest.json` y `cat dashboard/public/rollout.json` | `versionCode` de la respaldo (200) y `allow` con `macias` |
| P6 | Respaldo reciente | `ls -lh /var/backups/dmj/` y `verify-backup.sh` | Dump de menos de 26 h, verificable |

> Nota de red: la configuración de producción hoy documenta solo los puertos
> **999** (web/API/OTA) y **8883** (MQTT+TLS) expuestos. El puerto **5055**
> (OsmAnd) es requisito nuevo de la app de respaldo: verificar P1 y, si está
> cerrado, abrirlo solo para ese protocolo antes del reemplazo.

---

## 4. Procedimiento de reemplazo en un teléfono

### Paso 0 — Drenar la cola de la app nativa (obligatorio)

La app nativa guarda puntos pendientes en su cola local. Al reemplazarla, esa
cola se pierde si no se drena.

1. Pedir a la persona que abra la app nativa y **no** inicie una jornada nueva.
2. Esperar a que el panel muestre **0 pendientes** para `macias` (o que la
   jornada anterior haya cerrado y su resumen diga "confirmados = registrados").
3. Si hay pendientes que no bajan, **no continuar**: escalar (puede ser red o
   servidor, ver §7).

### Paso 1 — Confirmar que no hay jornada activa

- Verificar en el panel que `macias` no está EN LINEA / DETENIDO en jornada.
- Avisar a la persona: durante 10 minutos no habrá registro (el reemplazo es
  rápido, pero es honesto decirlo).

### Paso 2 — Instalar el APK de la app de respaldo

1. Obtener el APK del fork (canal OTA o copia local).
2. Instalarlo **encima** de la app nativa: misma firma y `versionCode` mayor
   (200 > 132) permiten la actualización sin desinstalar.
3. Si Android responde "no se instaló la app debido a un conflicto con un
   paquete", **detenerse**: significa que la firma o el `applicationId` no
   coinciden. No desinstalar a ciegas (se pierde la cola); escalar.

### Paso 3 — Configuración cero (viene preconfigurada)

Abrir la app y verificar que ya están puestos (no hay que digitar nada):

| Ajuste | Valor esperado |
|---|---|
| Identificador (`id`) | `macias` |
| URL del servidor | `http://68.168.20.219:5055` |
| Intervalo | 30 s |
| Distancia | 50 m |
| Ángulo | 15 grados |
| Precisión | Alta |
| Buffer offline | Activado |
| Wake lock | Activado |

### Paso 4 — Permisos y batería

1. Ubicación: "Permitir siempre" (incluye segundo plano).
2. Notificaciones: permitir (la notificación permanente mantiene el servicio vivo).
3. Batería: **sin restricción** para la app (guía por fabricante en
   `docs/OEM_COMPATIBILITY.md`).
4. Autoarranque: activarlo (OEM que lo pida).

### Paso 5 — Iniciar jornada y verificar identidad en el panel

1. Pulsar **Iniciar jornada** en la app.
2. En el panel, comprobar que **sigue siendo el mismo dispositivo**: mismo
   `id`/`uniqueid` (`macias`), mismo historial, y que aparece el evento
   `mobileJourneyStarted`.
3. Confirmar la primera posición en el mapa en **menos de 5 minutos**.

### Paso 6 — Bitácora

Anotar: fecha/hora, equipo, persona, versión instalada (2.0.0 / 200), quién
instaló, resultado de la verificación de §9 y si hubo incidentes.

---

## 5. Configuración manual de referencia (cliente oficial sin fork)

Solo como emergencia extrema (por ejemplo, reinstalar el cliente oficial desde
Play Store). No es el camino recomendado: el paquete del cliente oficial es
`org.traccar.client`, así que **no actualiza encima** de la app nativa y exige
desinstalar (se pierde la cola local); además no tiene botones de jornada ni
OTA propio.

| Ajuste | Valor | Por qué |
|---|---|---|
| URL del servidor | `http://68.168.20.219:5055` | Es el puerto OsmAnd del servidor; **no** es el 999 (API/panel) |
| Identificador | `macias` | Debe coincidir con `tc_devices.uniqueid`; si no, el servidor responde 400 |
| Intervalo | `30` segundos | Cadencia de la jornada |
| Distancia | `50` metros | Evita puntos redundantes en quieto |
| Ángulo | `15` grados | Registra giros relevantes |
| Precisión | Alta | Mejor calidad de trazo |
| Buffer offline | Activado | Guarda y reintenta sin red |
| Wake lock | Activado | Evita que el CPU duerma con pantalla apagada |
| Batería | Sin restricción | OEM |
| Autoarranque | Activado | Tras reinicio del teléfono |

Sin el fork, la jornada **no** se registra por el endpoint: habrá posiciones en
el mapa, pero no eventos `mobileJourneyStarted/Ended` ni marcado de jornada en
el panel.

---

## 6. Rollback del reemplazo

### 6.1 Pausar el OTA

Editar **los dos** archivos (el servidor los lee en caliente):

- `dashboard/public/rollout.json`
- `dashboard/build/rollout.json`

```json
{
  "percent": 100,
  "paused": true,
  "allow": []
}
```

Reglas de la política (`OtaRolloutPolicy`, verificada):

- Si `allow` **no está vacía**, es decisiva: solo esos equipos reciben el
  manifiesto, y `paused` **no** los detiene. Por eso, para pausar de verdad hay
  que **vaciar `allow`** y poner `paused: true`.
- Si `installedCode < minVersionCode`, la decisión es FORCED y **manda sobre la
  pausa**. No publicar `minVersionCode` mientras se depura.
- Ausente o ilegible = `percent 100, paused false` (fail-open): no confiar en
  "borrar el archivo" para pausar.

### 6.2 Publicar la build corregida

```bash
cd /DMujeres-Tracking
OTA_PUBLIC_BASE_URL=http://68.168.20.219:999 OTA_ALLOW_HTTP=1 \
  ./infrastructure/scripts/publish-ota.sh <ruta-apk> <version> "<notas>"
```

- `versionCode` **siempre mayor** (201, 202, ...). Android no permite
  instalar una versión menor sobre una mayor (`INSTALL_FAILED_VERSION_DOWNGRADE`)
  y la app nunca propone downgrade (`OtaVersionPolicy`).
- Al terminar la corrección, restaurar el piloto:
  `{"percent": 100, "paused": false, "allow": ["macias"]}`.

### 6.3 Punto de no retorno (leer antes de instalar)

Una vez instalada la respaldo (versionCode 200), **volver a la app nativa
1.1.22 (versionCode 132) no es posible como actualización**: Android lo
bloquea por downgrade. Las opciones son:

1. Recompilar la app nativa con `versionCode` mayor que 200 (por ejemplo 201)
   y firmarla con la misma keystore, o
2. Desinstalar la respaldo e instalar la nativa (se pierde la cola local de la
   respaldo; no se pierde el historial del servidor).

Decidir esto **antes** de reemplazar, no después.

---

## 7. Servidor caído, MQTT caído y broker

### 7.1 Qué sigue funcionando

| Escenario | App de respaldo (fork, OsmAnd HTTP) | App nativa (MQTT + HTTP) |
|---|---|---|
| Sin internet en el teléfono | Sigue capturando; guarda en el buffer SQLite y reintenta cada 30 s | Sigue capturando; cola local con retención 100 000 puntos / 7 días |
| MQTT/broker caído | **No le afecta**: no usa MQTT; las posiciones van por HTTP 5055 | MQTT es el transporte primario; cae al HTTP de `/api/mobile/v1/positions` |
| Servidor web (999) caído | Posiciones al 5055 pueden seguir; se pierden jornada, OTA y FCM | Se pierden jornada, salud, presencia, OTA y FCM; posiciones por MQTT pueden seguir |
| Servidor completo caído | Nada llega al panel; el teléfono **acumula** en su buffer | Nada llega; el teléfono acumula en su cola |

### 7.2 Límite honesto del buffer del fork

El buffer del cliente oficial es FIFO en SQLite (`traccar.db`, tabla
`position`) **sin tope ni purga por antigüedad**. Si el servidor está caído
muchos días, el teléfono llena almacenamiento. Vigilar espacio en el equipo
cuando la caída supere 24 h. (La app nativa sí tiene tope: 100 000 puntos / 7
días.)

### 7.3 Restaurar

1. Estado del servicio: `systemctl status dmj-traccar` y
   `tail -50 /var/log/dmj/watchdog.log`. El watchdog corre cada 5 min y reinicia
   el servicio si no responde; systemd lo relanza si muere.
2. Si el problema es la base o la configuración:
   `./infrastructure/scripts/restore.sh <dump>` y luego `verify-backup.sh`.
3. Runbooks: `docs/RUNBOOK-SANTIAGO.md` (canal HTTP/EMQX) y
   `docs/PRODUCTION_RUNBOOK.md`.
4. Tras restaurar: verificar que las posiciones acumuladas en los teléfonos
   entran solas (reintento del fork; replay de la nativa) y que no hay
   duplicados (`messageId`/`sequence` en la nativa; posiciones repetidas del
   fork se descartan por tiempo/identidad del dispositivo).

---

## 8. Comunicación al trabajador

Mensaje corto (adaptar nombre y hora):

> Hola [nombre]. Vamos a actualizar la aplicación del teléfono para que el
> registro sea más estable. Tarda unos 10 minutos y durante ese rato no se
> registrará tu recorrido. Cuando terminemos: abre la app, revisa que arriba
> diga "Iniciar jornada", pulsa ese botón y sigue trabajando normal. Si algo no
> se ve bien, avísame de inmediato.

---

## 9. Verificación post-instalación

| # | Qué | Dónde | Criterio de éxito |
|---|---|---|---|
| V1 | El dispositivo es el mismo | Panel | `macias` con su historial; sin dispositivo nuevo ni duplicado |
| V2 | Primera posición | Mapa | En **menos de 5 min** desde "Iniciar jornada" |
| V3 | Evento de jornada | Panel / reporte de jornadas | `mobileJourneyStarted` con hora correcta |
| V4 | Datos completos | Detalle de posición | lat/lon, hora, velocidad, rumbo, altitud, precisión y batería |
| V5 | Continuidad | `SELECT count(*) FROM tc_positions WHERE deviceid = <id de macias> AND fixtime > now() - interval '1 hour';` | Conteo creciente |
| V6 | Cierre de jornada | Panel | Al pulsar "Detener": `mobileJourneyEnded` y estado DESHABILITADO |
| V7 | Sin duplicados | Reporte de jornadas | Una jornada por inicio/fin; sin solapamientos |

> Nota de métrica: la **Cobertura30 porcentual** usa `v_device_funnel_daily`,
> que solo recibe datos de la app nativa. Para `macias` con la respaldo, la
> verificación diaria es `infrastructure/sql/cobertura30_diaria.sql`
> (mediana/p95 del intervalo en movimiento y huecos > 120 s) más el reporte de
> jornadas. No comparar peras con manzanas ni reportar un porcentaje que la
> respaldo no puede alimentar.

---

## 10. Responsables

| Rol | Quién | Responsabilidad |
|---|---|---|
| Dueño / decisión | [nombre] | Autoriza el reemplazo (C1-C4), el rollback y la publicación OTA |
| Responsable técnico | [nombre] | Verifica P1-P6, instala, verifica §9, documenta incidentes |
| Responsable de servidor | [nombre] | Puerto 5055, endpoint de jornada, backups, watchdog, restauración |
| Operación de campo | [nombre] | Avisa a la persona, confirma que el teléfono queda en jornada |
| Soporte a la persona | [nombre] | Mensaje de §8 y primera respuesta si algo falla |

---

## 11. Checklist imprimible

**Antes**

- [ ] Criterio de activación identificado (C1 / C2 / C3 / C4) y evidencia archivada
- [ ] Autorización del dueño
- [ ] P1 puerto 5055 alcanzable
- [ ] P2 endpoint `/api/mobile/v1/journey` responde 401 con clave mala
- [ ] P3 dispositivo `macias` existe en `tc_devices`
- [ ] P4 APK: `com.dmujeres.traccar`, 200 / 2.0.0, misma firma
- [ ] P5 OTA publicado y `allow` con `macias`
- [ ] P6 backup reciente verificado
- [ ] Cola de la app nativa drenada (0 pendientes)
- [ ] Jornada activa cerrada y persona avisada

**Durante**

- [ ] APK instalado encima sin desinstalar
- [ ] `id = macias` y URL `http://68.168.20.219:5055` preconfigurados
- [ ] Permisos: ubicación siempre, notificaciones, batería sin restricción, autoarranque
- [ ] "Iniciar jornada" pulsado
- [ ] Evento `mobileJourneyStarted` visible en el panel

**Después**

- [ ] V1 mismo dispositivo en el panel
- [ ] V2 posición en el mapa en menos de 5 min
- [ ] V3-V7 verificaciones de §9 en verde
- [ ] Bitácora escrita (fecha, equipo, versión, responsable)
- [ ] Rollback preparado: `rollout.json` a mano y decisión del "punto de no retorno" (§6.3) registrada
- [ ] Seguimiento 24 h: intervalo mediano/p95 y huecos > 120 s de `cobertura30_diaria.sql`
