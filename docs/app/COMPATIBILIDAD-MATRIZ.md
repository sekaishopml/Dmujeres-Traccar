# Matriz de compatibilidad App actual ↔ plataforma nueva

FASE 7 (compatibilidad de la App) del plan maestro (secciones 11 y 24).
Fecha: 2026-09-26. Agente: QA/APP.

## 1. Alcance y método

- **App auditada (solo lectura):** `/DMujeres-Tracking/fallback`, cliente Traccar
  con personalizaciones DMujeres (plan B). Rutas citadas con `archivo:línea`.
- **Plataforma nueva:** `services/tracking` (receptor), `services/api`
  (`/api/v1`), `services/web` + `apps/web` (panel), Nginx `:80`.
- **Contrato congelado de referencia:** `docs/api/COMPATIBILIDAD-APP.md`.
- **Método:** contraste código App ↔ código nuevo ↔ comportamiento del fork en
  producción (lectura), más sondas HTTP en vivo contra `127.0.0.1:6066`
  (receptor nuevo, solo lecturas), smokes de los servicios nuevos y `SELECT`
  de conteos. Producción no se modificó.
- Estados: `cubierto` (contrato y efecto equivalentes), `parcial` (el App
  conecta y funciona, pero falta un efecto o validación), `pendiente` (no
  implementado en la plataforma nueva).

## 2. Resumen

| Área | Estado global |
|---|---|
| OsmAnd `:5055` (query/form-urlencoded) | cubierto |
| OsmAnd formato JSON (`device_id`+`location`) | pendiente |
| `/api/mobile/v1/config` | cubierto |
| `/api/mobile/v1/journey` | parcial |
| `/api/mobile/v1/diagnostics` | parcial |
| `/api/mobile/v1/ota` | parcial |
| `/api/mobile/v1/fcm-token` | parcial |
| `/api/mobile/v1/recovery-ack` | parcial |
| Atributos `mobile.*` de configuración | cubierto |
| Atributos `mobile.*` de jornada / OTA / FCM | cubierto |
| Atributos `mobile.*` de diagnóstico, cola, salud | parcial / pendiente |
| FCM real (envío y recuperación) | pendiente |
| OTA (manifiesto vs rollout) | parcial |
| Credenciales de dispositivo / flota | cubierto (verificado) |
| Derivación del puerto 999 (`:5055`→`:999`) | pendiente de infraestructura de cutover |

## 3. Matriz A. Protocolo OsmAnd en `:5055`

La App envía `POST` con todos los parámetros en la URL
(`ProtocolFormatter.kt:22-47`); el receptor nuevo acepta `GET`/`POST` y
parámetros en la query o en cuerpo `application/x-www-form-urlencoded`
(`services/tracking/src/osmand.js:70-78,93-148`).

| Uso de la App (evidencia) | Equivalente nuevo (evidencia) | Estado | Prueba |
|---|---|---|---|
| `id` con `uniqueId` (`ProtocolFormatter.kt:25`) | `osmand.js:103` (`id` o `deviceid`) | cubierto | smoke tracking (200, fila en `dmt_posicion`) |
| `timestamp` en **segundos** (`ProtocolFormatter.kt:26`) | `osmand.js:36-47` (si `< Integer.MAX_VALUE` multiplica ×1000) | cubierto | smoke tracking |
| `lat`, `lon` (`ProtocolFormatter.kt:27-28`) | `osmand.js:106-111` | cubierto | smoke tracking |
| `speed` en **nudos** (`ProtocolFormatter.kt:32`) | `osmand.js:131-132` (×1.852 → `velocidad_kmh`) | cubierto | smoke: 12.5 kn → 23.15 km/h |
| `bearing` (`ProtocolFormatter.kt:33`) | `osmand.js:123` (`bearing` o `heading`) | cubierto | smoke tracking |
| `altitude` (`ProtocolFormatter.kt:34`) | `osmand.js:130` | cubierto | smoke tracking |
| `accuracy` (`ProtocolFormatter.kt:35`) | `osmand.js:134` | cubierto | smoke tracking |
| `batt` (`ProtocolFormatter.kt:36`) | `osmand.js:124,135-136` (acota 0-100) | cubierto | smoke tracking |
| `charge` solo si es true (`ProtocolFormatter.kt:37-39`) | `osmand.js:82-83,139` (`atributos.charge`) | cubierto | smoke: `charge=true` |
| `mock` solo si es true (`ProtocolFormatter.kt:40-42`) | `osmand.js:84-85,137` (`valida=false`) | cubierto | smoke: mock no valida |
| `alarm` (SOS) (`ProtocolFormatter.kt:43-45`) | `osmand.js:86-89,139` (`atributos.alarm`) | cubierto | smoke: `alarm=humo-smoke` |
| Respuesta que borra el buffer: cualquier 2xx (`TrackingController.kt:352-361`) | 200 sin cuerpo (`osmand.js:15-18,148`) | cubierto | smoke: 200, cuerpo 0 B |
| 4xx descarta el punto (`TrackingController.kt:363-372`) | 400 datos inválidos / 404 desconocido | cubierto | smoke: 400 sin `lon`, 404 desconocido |
| 5xx reintenta con backoff (`TrackingController.kt:373-401`) | 503 si falla el almacenamiento (`osmand.js:116-119,144-147`) | cubierto | código + docs/operations/FASE4-TRACKING.md §6 |

Diferencias con el fork de producción (solo lectura, evidencia de código):

| Comportamiento producción (`server/.../OsmAndProtocolDecoder.java`) | Receptor nuevo | Estado | Impacto |
|---|---|---|---|
| Dispositivo desconocido en query → **400** (`OsmAndProtocolDecoder.java:98-101`) | **404** (`osmand.js:120`, decisión documentada en `FASE4-TRACKING.md` §6.1) | parcial | Nulo para el App: ambos 4xx descartan el punto (`TrackingController.kt:363`) |
| Formato JSON `{"device_id","location"}` → 404 desconocido / 200 aceptado (`OsmAndProtocolDecoder.java:234-236`) | no implementado: sonda `POST /` JSON → **400** | pendiente | El fallback no lo usa; sí clientes de terceros o la App nativa si lo adoptara |
| Parámetros extra `valid`, `location`, `cell`, `wifi`, `hdop`, `driverUniqueId` (`OsmAndProtocolDecoder.java:73-189`) | ignorados | pendiente | El fallback no los envía |
| `notificationToken` actualiza el token FCM (`OsmAndProtocolDecoder.java:104-108`) | ignorado | pendiente | El fallback registra el token por `/api/mobile/v1/fcm-token` (`DmujeresApi.kt:191-198`) |

Sondas reales contra `127.0.0.1:6066` (2026-09-26, solo lectura):

```
POST / (JSON device_id/location, dispositivo inexistente) -> 400
GET  /?id=no-existe-json&lat=1&lon=1&timestamp=1758850000 -> 404
GET  /api/mobile/v1/health -> 404
POST /api/mobile/v1/positions -> 404
```

## 4. Matriz B. Canal `/api/mobile/v1/*`

La App deriva la base del canal de `:5055` a `:999`
(`DmujeresApi.kt:51-56`) y autentica con `X-Api-Key`
(`DmujeresApi.kt:47-49`) + `X-Device-Id`.

| Endpoint / uso de la App | Equivalente nuevo | Estado | Evidencia |
|---|---|---|---|
| `GET /config` en login, home, latido y refresco (`DmujeresApi.kt:245-267,299-321`; `RemoteConfig.kt:34-59`) | `movil.js:187-211`; 503 canal apagado, 401 clave, 400 sin id, 404 desconocido | cubierto | Sonda en vivo: 200 con los 13 campos; 401 con clave inválida; 404 desconocido. Defaults idénticos a `MobileConfigResource.java:78-92` |
| `POST /journey` `start`/`stop` con `deviceId`, `journeyId`, `client` (`DmujeresApi.kt:152-234`) | `movil.js:218-274`; abre/cierra `operations.dmt_jornada` y fija `mobile.journeyId/client/lastEndedJourneyId/journeyEndedAt` (`db.js:195-259`) | parcial | Smoke tracking (200 `{ok:true}`, fila abierta/cerrada, atributos). Falta evento `mobileJourneyStarted/Ended` con `journeyId`, que el fork sí emite (`MobileJourneyResource.java:140,164`) |
| `POST /diagnostics` con `report.app/gps/buffer/power/cadence/perms/journey/serverUrl` (`ServiceHeartbeat.kt:85-139`) y crash (`CrashReporter.kt:46`) | `movil.js:280-355`; 204, 400, 403, 413 y antirrebote 20 s; `lastDiagnostics`, `lastDiagnosticsAt`, `mobile.permBackground`, `mobile.batteryExempt`, `mobile.mockLocation`, `mobile.gps/gpsAt`, `mobile.lastCrashAt`; inserta en `telemetry.dmt_bateria` (`db.js:261-287`) | parcial | Smoke tracking: 204, 413, 403, 400, muestra de batería. Faltan atajos que el fork sí escribe: `mobile.cadenceMovingMs` y `mobile.lastOtaResult/Stage/HttpCode/At/Error` (`MobileDiagnosticsService.java:463-495`) |
| `POST /diagnostics` con `report.ota` (`DmujeresApi.kt:331-350`) | el reporte completo queda en `lastDiagnostics`, pero no se proyecta a `mobile.lastOta*` | parcial | Igual que la fila anterior |
| `GET /ota?deviceId=&versionCode=` (`DmujeresApi.kt:381-439`) | `movil.js:376-406`; manifiesto si `latest.versionCode > versionCode`, si no `{"update":false}`; auditoría `mobile.lastOta*` 1/min | parcial | Existe `latest.json` (v2.1.73/283) con `version/versionCode/url/notes/sha256`; el receptor no aplica `rollout.json` (`percent/paused/allow`) ni `minVersionCode` que sí usa el fork (`OtaResource.java:119-140`) |
| OTA por GitHub si el puerto 999 no responde (`DmujeresApi.kt:395-405,442-470`) | no aplica a la plataforma (fallback de cliente) | cubierto | Código del App |
| Instalación: descarga, `sha256`, etapas `download/sha/install` (`UpdateActivity.kt:106-205`) | manifiesto con `sha256` servido por el receptor | parcial | Requiere republicar el APK en la URL del manifiesto (hoy `http://68.168.20.219:999/...`) en el cutover |
| `POST /fcm-token` (`DmujeresApi.kt:191-198`) | `movil.js:412-444`; guarda `mobile.fcmTokenRegistered/fcmTokenPrefix/fcmUpdatedAt/appVersion` y jamás el token completo | parcial | Contrato cubierto (sonda FASE 4: 200 `{"success":true,"status":"registered"}`); sin token completo no hay envío FCM real (FASE 4b) |
| `POST /recovery-ack` etapas `RECOVERY_RECEIVED`/`RECOVERY_STARTED` (`DmujeresMessagingService.kt:30-49`) | `movil.js:450-472`; registra y responde 200 `accepted` siempre | parcial | El fork normaliza `RECOVERY_*` y valida orden, rechazando con `success:false` (`FcmRecoveryService.java:113-141`); la máquina de estados queda para 4b |
| `GET /config` como sonda de servidor (`DmujeresApi.kt:299-321`) | mismos códigos 200/401/403/404/503 | cubierto | Sonda en vivo 200/401/404 |

## 5. Matriz C. Atributos `mobile.*`

El App no lee atributos directamente: el servidor los escribe y el panel los
lee. "App" aquí significa "efectos del contrato que el App provoca".

| Grupo / atributo | Producción (lectura) | Plataforma nueva | Estado |
|---|---|---|---|
| Configuración: `intervalSeconds`, `minIntervalSeconds`, `distanceMeters`, `angleDegrees`, `accuracy`, `bufferEnabled`, `bufferMax`, `bufferPolicy`, `ackTimeoutSeconds`, `maxRetries` | `MobileConfigResource.java:78-92` | `movil.js:197-211` (los 13 campos, incluidos `l1_*` y `store_all_enabled`) | cubierto |
| Jornada: `mobile.journeyId`, `mobile.client`, `mobile.lastEndedJourneyId`, `mobile.journeyEndedAt` | `MobileJourneyResource.java:130-164` | `movil.js:240-267` | cubierto |
| Jornada: eventos `mobileJourneyStarted/Ended` con `journeyId` | `MobileJourneyResource.java:140,164` | no se emiten | pendiente |
| OTA: `mobile.lastOtaCheckAt/lastOtaVersionCode/lastOtaUpdate/lastOtaUa` | `OtaResource.java:146-166` | `movil.js:361-374` | cubierto |
| OTA: `mobile.lastOtaResult/lastOtaStage/lastOtaHttpCode/lastOtaAt/lastOtaError`, `mobile.otaManualPressedAt` | `MobileDiagnosticsService.java:482-495` | no se escriben | pendiente |
| FCM: `mobile.fcmTokenRegistered/fcmTokenPrefix/fcmUpdatedAt` | `MobileRecoveryResource.java:98-103` | `movil.js:428-440` | cubierto |
| GPS / permisos: `mobile.gps`, `mobile.gpsAt`, `mobile.mockLocation`, `mobile.permBackground`, `mobile.batteryExempt`, `mobile.lastCrashAt` | `MobileDiagnosticsService.java:463-481` | `movil.js:303-328` | cubierto |
| GPS / permisos: `mobile.permFine`, `mobile.gpsEnabled`, `mobile.gnssUsed/Total`, `mobile.fusedFailures`, `mobile.cadenceMovingMs`, `mobile.motionState`, `mobile.speedSource` | `MobileTelemetryApplier.java:154-282`, `MobileDiagnosticsService.java:475-478` | no se escriben | pendiente |
| Cola / entrega: `mobile.pending`, `mobile.fixReceived/Rejected/Enqueued`, `mobile.retryTotal`, `mobile.quarantinedTotal` | `MobileTelemetryApplier.java:86,257-263` (MQTT) | no se escriben; el panel nuevo ya lee `mobile.pending` (`services/api/src/flota.js:24`) | pendiente |
| Red / presencia / salud: `mobile.network/netState/netCause/netConf/service/signal/rttMs/...`, `mobile.presence*`, `mobile.health*`, `mobile.recoveryState`, `mobile.continuity*`, `mobile.readinessVerdict` | `MobileTelemetryApplier.java`, `MobileHealthService.java:102-161` (MQTT/nativo) | no se escriben | pendiente |
| Batería: `mobile.battery`, `mobile.batteryHistory` | `MobileTelemetryApplier.java:87-116` | muestra puntual en `telemetry.dmt_bateria` (`movil.js:330-348`); sin serie histórica | parcial |
| Diagnóstico: `lastDiagnostics`, `lastDiagnosticsAt` | `MobileDiagnosticsService.java:459-460` | `movil.js:303-306` | cubierto |

## 6. Matriz D. FCM (token y recuperación)

| Pieza | Producción | Plataforma nueva | Estado |
|---|---|---|---|
| Registro/rotación de token | `MobileRecoveryResource.java:79-114` + `FcmTokenStore` (guarda token completo) | `movil.js:412-444` (solo prefijo SHA-256) | parcial |
| Payload de recuperación `{"type":"TRACKING_RECOVERY_PROBE",...}` | `FcmRecoveryService`/`FcmSender` | no se emite | pendiente |
| ACK por etapas + orden + `SERVER_ACK`/`SUCCESS` al recibir posición real | `FcmRecoveryService.java:113-141`, `FcmRecoveryPolicy.java:24-105` | acepta cualquier etapa, sin estado | parcial (FASE 4b) |
| Cuenta de servicio fuera del APK | `GOOGLE_APPLICATION_CREDENTIALS` | no aplica aún | pendiente |
| El App atiende el push y enciende captura (`DmujeresMessagingService.kt:24-49`) | contrato vigente | no hay push que atender | pendiente de 4b |

## 7. Matriz E. OTA (manifiesto y rollout)

| Pieza | Producción | Plataforma nueva | Estado |
|---|---|---|---|
| Manifiesto `latest.json` (`version`, `versionCode`, `url`, `notes`, `sha256`) | `OtaResource.java:125-137` | `movil.js:390-405` | cubierto |
| `minVersionCode` | `OtaResource.java:126-129` | ignorado | pendiente |
| `rollout.json` (`percent`, `paused`, `allow[]`) | `OtaResource.java:185-206` | ignorado: sirve el manifiesto a cualquier equipo con versión menor | pendiente (riesgo alto) |
| Sin downgrade | decisión por `latest > versionCode` | igual | cubierto |
| Auditoría `mobile.lastOtaCheckAt/VersionCode/Update/Ua` (1/min) | `OtaResource.java:146-166` | `movil.js:361-374` | cubierto |
| Carpeta del manifiesto | `DMJ_OTA_DIR` / `web.path` | `DMJ_OTA_DIR` (hoy apunta a `dashboard/build` de producción, solo lectura) | transitorio |
| URL del APK | `http://68.168.20.219:999/...` | mismo manifiesto | pendiente de cutover |

## 8. Matriz F. Credenciales, identidad y puertos

| Punto | Verificación real (2026-09-26) | Estado |
|---|---|---|
| Identificadores `uniqueId` | `tracking.dmt_dispositivo.identificador` conserva `macias`, `pilay`, `kevin`, `jeremy`, `joseph`, `david`, `manzaba`, `fernando`, `qa-f0` (SELECT en `dmt-db` vs `tc_devices` en `dmj-db`) | cubierto |
| Clave de flota actual `X-Api-Key` | `DMJ_CLAVE_MOVIL` y `MOBILE_HTTP_API_KEY` comparadas por SHA-256 (sin imprimir): **iguales** | cubierto |
| Clave anterior (rotación) | `DMJ_CLAVE_MOVIL_ANTERIOR` = `MOBILE_HTTP_API_KEY_PREVIOUS`: **iguales**; el receptor acepta ambas (`entorno.js:54-56`) | cubierto |
| Credencial del dispositivo | El App usa `Prefs.DEVICE` (uniqueId) y la contraseña de flota como `X-Api-Key` (`DmujeresApi.kt:40-49`, `OnboardingActivity.kt:162-170`) | cubierto |
| Derivación `:5055`→`:999` | `DmujeresApi.kt:55` hace `url.replace(":5055", ":999")` para **todo** el canal móvil; hoy `:999` es producción y `:5055` es `dmj-traccar`. La plataforma nueva escucha en `127.0.0.1:6066` y publica el panel en `:80`/`:25565` (`FASE8-INFRA.md:17-20,237-240`) | pendiente crítico (cutover) |
| Host público por defecto | `values.xml:4`: `http://68.168.20.219:5055` | conservar en cutover |

## 9. Huecos priorizados

| # | Hueco | Impacto | Fase propuesta |
|---|---|---|---|
| 1 | `:999` (canal móvil) y `:5055` (OsmAnd) no están publicados por la infraestructura nueva; el App no reconecta solo con el cambio de directorio | Crítico: sin esto la App queda en producción | FASE 8/cutover (ADR de puertos) |
| 2 | `rollout.json` (`paused`, `allow`, `percent`) y `minVersionCode` ignorados | Alto: una publicación OTA llega a toda la flota sin control | 4b (antes del cutover) |
| 3 | Diagnósticos no proyectan `report.ota` ni `report.cadence` a `mobile.lastOta*`/`mobile.cadenceMovingMs` | Medio: el panel pierde evidencia de OTA y cadencia del fallback | 4b |
| 4 | `mobile.pending` y métricas de cola sin escritor HTTP/MQTT | Medio: el panel nuevo muestra pendientes vacío | 4b/6 |
| 5 | Eventos `mobileJourneyStarted/Ended` no emitidos | Medio: reportes/timeline de jornadas no ven inicio/fin | 6 |
| 6 | FCM real (token completo, envío, máquina de estados, `SERVER_ACK`) | Alto operativo: recuperación por push no funciona | 4b |
| 7 | OsmAnd formato JSON (`device_id`+`location`) no implementado | Bajo para el fallback; afecta clientes de terceros | 4b/6 |
| 8 | Dispositivo desconocido responde 404 vs 400 del fork | Bajo: el App descarta igual; decisión documentada | cutover (o ajuste de 1 línea) |
| 9 | `notificationToken` en OsmAnd ignorado | Bajo: el fallback usa `/fcm-token` | 4b |
| 10 | URL del APK OTA apunta al host/puerto de producción | Medio: tras el corte el App descarga del host viejo | cutover |
| 11 | `/api/mobile/v1/positions` y `/health` (App nativa) no existen en el receptor nuevo | Medio si la App nativa entra en la flota | 6/8 |
| 12 | Telemetría MQTT `mobile.*` sin consumidor | Medio: panel sin salud/red/presencia | 4b/6 |

## 10. Evidencia de la auditoría

- Sondas al receptor nuevo (`127.0.0.1:6066`), 2026-09-26:
  `config` 200 con los 13 campos y defaults del contrato; clave inválida 401;
  dispositivo desconocido 404; JSON OsmAnd 400; `/health` y `/positions` 404.
- Smokes: `services/tracking` PASS (200/400/404, filas y limpieza) y
  `services/api` 49 PASS (login, permisos, fleet, replay, reportes, batería,
  usuarios, auditoría).
- Configuración remota en vivo (qa-f0):
  `{"intervalSeconds":10,"bufferMax":5000,"bufferPolicy":"drop_oldest",...}`.
- Conteos: producción `tc_positions=27135` (crece por tráfico real) vs base
  nueva `tracking.dmt_posicion=80809` (SELECT de solo lectura).
- Credenciales e identificadores: comparación por hash/consulta (sin exponer
  valores) descrita en la sección 8.
- Corrida E2E completa: ver `docs/operations/FASE9-QA.md`.

## 11. Dudas

1. ¿Se acepta la divergencia 404 vs 400 en OsmAnd desconocido o se alinea al
   contrato (400)? Es un cambio de una línea en `osmand.js:120`.
2. ¿La App nativa (`/DMujeres-Tracking/mobile`, endpoints `/positions`,
   `/health`, MQTT) entra en el alcance de compatibilidad del cutover? No está
   cubierta por esta matriz, que siguió el alcance pedido (`fallback/`).
3. ¿El cutover publicará `:999` hacia la plataforma nueva (panel + canal móvil)
   y redirigirá `:5055` al receptor, o se mantendrá `dmj-traccar` en esos
   puertos? De esto depende el hueco #1.
