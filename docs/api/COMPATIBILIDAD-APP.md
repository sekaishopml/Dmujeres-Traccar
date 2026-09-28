# Compatibilidad con la App actual (contrato CONGELADO)

Este documento fija el contrato que la **App en operación** usa hoy contra el
servidor. Es intocable durante la migración: no se renombran rutas,
parámetros, atributos ni códigos. Cualquier mejora que necesite cambiar algo
de lo aquí descrito **exige una capa de compatibilidad** que atienda el
contrato viejo y el nuevo a la vez (ver §6).

Base: instalación actual `/DMujeres-Tracking` (fork Traccar 6.14.5) en
producción. La nueva plataforma (`/home/DMujeres-Tracking`) debe conservar
estos nombres, identificadores y credenciales para que la App reconecte sin
una versión nueva.

## 1. Protocolo OsmAnd por HTTP en `:5055`

Transporte de posiciones de la App de respaldo (`fallback/`) y de la App
nativa como canal alterno. `POST` HTTP con parámetros en la query (o en el
cuerpo `application/x-www-form-urlencoded`); respuesta **200** con cuerpo
vacío cuando la posición se acepta.

| Parámetro | Significado | Unidades / formato |
|---|---|---|
| `id` | Identificador único del dispositivo | texto (`uniqueId`, p. ej. `macias`) |
| `timestamp` | Hora del fix | epoch ms (si es menor a `Integer.MAX_VALUE` se interpreta en segundos) |
| `lat` | Latitud | grados decimales |
| `lon` | Longitud | grados decimales |
| `speed` | Velocidad | **nudos** (el servidor convierte a km/h) |
| `bearing` | Rumbo | grados (alias aceptado: `heading`) |
| `altitude` | Altitud | metros |
| `accuracy` | Precisión | metros |
| `batt` | Batería | porcentaje |
| `charge` | Cargando | `true`/`false` |
| `mock` | Ubicación simulada | `true`/`false` (se guarda en atributos de la posición) |
| `alarm` | Alarma del fix | texto (se guarda en atributos de la posición) |

Comportamiento congelado:

- Posición aceptada: **HTTP 200** (puede incluir cuerpo si hay un comando en
  cola; la App solo exige el 200). El cliente borra el punto del buffer con
  cualquier 2xx.
- `id` desconocido o sin sesión de dispositivo: HTTP 400 y el punto no se
  guarda.
- Formato JSON de OsmAnd (`device_id` + `location`): dispositivo desconocido
  responde 404; aceptado responde 200.
- El canal se sirve en el **mismo host/puerto público** que la App ya tiene
  configurado; el cambio de directorio del VPS no puede obligar a
  reconfigurar la App.

## 2. Atributos `mobile.*` (telemetría y estado)

La App nativa escribe estos atributos en el dispositivo; el panel y los
reportes los leen. **No renombrar**: son parte del contrato.

| Grupo | Atributos |
|---|---|
| Jornada | `mobile.journeyId`, `mobile.client`, `mobile.journeyEndedAt`, `mobile.lastEndedJourneyId` |
| Cola / entrega | `mobile.pending`, `mobile.ackTotal`, `mobile.retryTotal`, `mobile.quarantinedTotal`, `mobile.fixReceived`, `mobile.fixRejected`, `mobile.fixEnqueued` |
| Versión / OTA | `mobile.appVersion`, `mobile.lastOtaResult`, `mobile.lastOtaStage`, `mobile.lastOtaHttpCode`, `mobile.lastOtaAt`, `mobile.lastOtaError`, `mobile.lastOtaCheckAt`, `mobile.lastOtaUpdate`, `mobile.lastOtaVersionCode`, `mobile.lastOtaUa`, `mobile.otaManualPressedAt` |
| GPS / ubicación | `mobile.gps`, `mobile.gpsAt`, `mobile.gpsState`, `mobile.gpsEnabled`, `mobile.fusedFailures`, `mobile.mockLocation`, `mobile.lastFixTime`, `mobile.gnssUsed`, `mobile.gnssTotal`, `mobile.speedSource`, `mobile.motionState` |
| Red | `mobile.network`, `mobile.netState`, `mobile.netCause`, `mobile.netConf`, `mobile.service`, `mobile.signal`, `mobile.rttMs`, `mobile.wifiEnabled`, `mobile.airplane`, `mobile.dataEnabled`, `mobile.simPresent`, `mobile.mqttState` |
| Batería / energía | `mobile.battery`, `mobile.batteryHistory`, `mobile.batteryExempt`, `mobile.batteryOptimized` |
| Presencia / salud | `mobile.presenceState`, `mobile.presenceAt`, `mobile.presenceReason`, `mobile.lastPresenceAt`, `mobile.lastPositionAt`, `mobile.healthState`, `mobile.healthAt`, `mobile.healthOutbox`, `mobile.outboxState`, `mobile.degraded`, `mobile.validated`, `mobile.recoveryState`, `mobile.continuityState`, `mobile.continuityCause`, `mobile.readinessVerdict`, `mobile.causeAt` |
| Permisos / dispositivo | `mobile.permFine`, `mobile.permBackground`, `mobile.backgroundRestricted`, `mobile.standbyBucket`, `mobile.androidVersion`, `mobile.androidSdk`, `mobile.rom`, `mobile.vendor`, `mobile.model`, `mobile.bootId`, `mobile.sessionId`, `mobile.pollActive`, `mobile.cadenceMovingMs`, `mobile.rejectBreakdown`, `mobile.lastCrashAt` |
| FCM | `mobile.fcmTokenRegistered`, `mobile.fcmTokenPrefix`, `mobile.fcmUpdatedAt` |
| Configuración remota | `mobile.intervalSeconds`, `mobile.bufferMax`, `mobile.bufferPolicy`, `mobile.ackTimeoutSeconds`, `mobile.maxRetries`, `mobile.distanceMeters`, `mobile.angleDegrees`, `mobile.accuracy`, `mobile.bufferEnabled`, `mobile.l1PendingIntentEnabled`, `mobile.storeAllEnabled`, `mobile.l1MaxUpdateDelayMs`, `mobile.minIntervalSeconds` |

`mobile.batteryHistory` es una cadena (serie compacta), no un arreglo JSON
anidado; `mobile.journeyId` vale `0` cuando no hay jornada activa.

## 3. Canal móvil `/api/mobile/v1/*`

Autenticación por cabeceras, común a todo el canal:

- `X-Api-Key`: clave de flota (se acepta la clave actual y, durante una
  rotación, la anterior). Comparación en tiempo constante.
- `X-Device-Id`: `uniqueId` del dispositivo (`mobile/v1/config` y
  `mobile/v1/diagnostics` también aceptan `?deviceId=` como respaldo; nunca se
  toma la identidad del cuerpo).

| Método | Ruta | Cuerpo / consulta | Respuesta |
|---|---|---|---|
| GET | `/api/mobile/v1/config` | `X-Device-Id` o `?deviceId=` | 200 con `intervalSeconds`, `bufferMax`, `bufferPolicy`, `ackTimeoutSeconds`, `maxRetries`, filtros de captura y switches L1; 400 sin dispositivo; 401 clave inválida; 404 dispositivo desconocido; **503 canal apagado** (`mobile.http.enable=false`) |
| POST | `/api/mobile/v1/journey` | `{"deviceId":"macias","action":"start"\|"stop","journeyId":opcional,"client":opcional}` | 200 `{"ok":true}`; 400 acción/cuerpo inválido; 401; 404 canal apagado o dispositivo desconocido |
| POST | `/api/mobile/v1/diagnostics` | reporte JSON del cliente | 204 cualquier reporte aceptado o ignorado por rate-limit; 400 JSON inválido; 401; 403 `deviceId` del cuerpo distinto al autenticado; 404 canal apagado o dispositivo desconocido; 413 cuerpo mayor a 10 000 bytes |
| GET | `/api/mobile/v1/ota` | `X-Device-Id`, `versionCode` | 200 `{"update":false}` o el artefacto publicado; 400; 401; 404 canal apagado o dispositivo sin artefacto |
| POST | `/api/mobile/v1/fcm-token` | `{"fcmToken","appVersion","platform","manufacturer","model","androidVersion"}` | 200 `{"success":true,"status":"registered"}`; 400 token inválido; 401; 403 dispositivo desconocido |
| POST | `/api/mobile/v1/recovery-ack` | `{"recoveryAttemptId","stage","priority","reason"}`, etapas `RECEIVED`, `STARTED`, `FGS_ACTIVE`, `TRACKING_ACTIVE`, `GPS_CONFIRMED` | 200 `{"success":true,"status":"accepted"\|"rejected"}`; 400; 401; 403 dispositivo desconocido |

Notas congeladas:

- Las respuestas exitosas de `journey`, `fcm-token` y `recovery-ack` son JSON;
  `diagnostics` responde 204 sin cuerpo.
- El canal solo está activo con `mobile.http.enable=true`. El 404 vs 503 de
  `config` es intencional: 404 = dispositivo desconocido, 503 = servicio no
  disponible.
- Efectos de jornada: fija `mobile.journeyId` y `mobile.client`, registra la
  jornada y publica los eventos `mobileJourneyStarted`/`mobileJourneyEnded`.
- Efectos de OTA: fija `mobile.lastOta*` para diagnóstico y respeta la
  política de versión (sin downgrade).

## 4. FCM (recuperación)

- Push de datos de alta prioridad, TTL 120 s, payload mínimo:
  `{"type":"TRACKING_RECOVERY_PROBE","recoveryAttemptId":"fcm-<uuid>","deviceId":"<id>","issuedAt":"<epoch ms>"}`.
- Registro/rotación del token con `POST /api/mobile/v1/fcm-token`; el token
  completo nunca se loguea (solo prefijo hash).
- ACK por etapas a `POST /api/mobile/v1/recovery-ack`; el **servidor** declara
  `SERVER_ACK`/`SUCCESS` solo cuando llega una posición real.
- La cuenta de servicio FCM vive solo en el servidor
  (`GOOGLE_APPLICATION_CREDENTIALS` o `notificator.firebase.serviceAccount`);
  nunca en el APK.

## 5. Qué NO puede cambiar

- Host público, puerto `:5055` y rutas del protocolo OsmAnd.
- Nombres de parámetros OsmAnd y de los atributos `mobile.*`.
- Rutas, cabeceras, cuerpos y códigos del canal `/api/mobile/v1/*`.
- Identificadores de dispositivo (`uniqueId`) y credenciales de flota
  (`X-Api-Key`) vigentes.
- Payload y etapas de FCM.
- Respuestas necesarias para que el cliente borre su buffer (2xx) y para que
  distinga error recuperable (503) de error de datos (400/404).

## 6. Regla de cambio

> Cualquier cambio en lo anterior **exige una capa de compatibilidad**: la
> versión nueva se expone en paralelo (por ejemplo `/api/mobile/v2/*` o un
> nuevo parámetro) y el contrato viejo se sigue atendiendo sin cambios hasta
> que toda la flota haya migrado. Nunca se rompe el contrato en caliente.

La plataforma nueva implementa los **adaptadores** que traducen este contrato
al modelo `dmt_*` (`docs/architecture/ARQUITECTURA.md`); el motor interno puede
cambiar, el contrato de la App no.
