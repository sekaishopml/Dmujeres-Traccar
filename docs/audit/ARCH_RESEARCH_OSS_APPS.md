# ARCH_RESEARCH_OSS_APPS — Cómo sobreviven en segundo plano las apps de rastreo OSS

Fecha: 2026-09-19. Método: tarball de `master`/`main` + `grep -n` sobre código (líneas citadas), issues vía API de GitHub,
conducta OEM vía dontkillmyapp.com. No se copió código; solo se documentan técnicas.

Línea base evaluada (columna "¿Ya?"): **SI** = FGS location, guardián por alarma (inexacta + exacta con exención),
FCM high-priority como despertador, cadenas de intents OEM, outbox HTTP. **PARCIAL**/**NO** = el resto.

## 1. Traccar Client — `traccar/traccar-client-android` (Apache-2.0, `LICENSE.txt`)

- **FGS location**: `TrackingService.onCreate()` llama `startForeground` (`TrackingService.kt#L43-L47`); manifest declara
  `foregroundServiceType="location"` y `FOREGROUND_SERVICE_LOCATION` (`AndroidManifest.xml#L13-L14,L70-L73`).
- **PARTIAL_WAKE_LOCK permanente**: `TrackingService.kt#L53-L57` (`wakeLock?.acquire()` sin timeout, anotado
  `@SuppressLint("WakelockTimeout")` en L43); es activable/desactivable por preferencia (`KEY_WAKELOCK`). No hay release
  por timeout: solo en `onDestroy` (L82-L84).
- **`START_STICKY`** (`TrackingService.kt#L72-L75`) + finalización explícita del wakeful intent.
- **Wakeful broadcast → FGS**: `WakefulBroadcastReceiver.kt#L34-L52` crea un `PARTIAL_WAKE_LOCK` de 60 s,
  `setReferenceCounted(false)`, y lo libera cuando el servicio arranca (`completeWakefulIntent`). Cubre el hueco
  broadcast→primer `startForeground` (donde Doze/OEM puede congelar el proceso).
- **Autoarranque**: `AutostartReceiver.kt#L22-L30` atiende `BOOT_COMPLETED` y `MY_PACKAGE_REPLACED`
  (`AndroidManifest.xml#L75-L84`; receiver `exported=true`, sin permiso — smell de seguridad).
- **Watchdog por alarma repetida**: `MainFragment.kt#L233-L238` programa `setInexactRepeating(ELAPSED_REALTIME_WAKEUP, 15 s)`
  apuntando a `AutostartReceiver` (`ALARM_MANAGER_INTERVAL=15000`, L293). **Solo API < 31** (en Android 12+ no se programa).
- **Sin Google Play Services**: `AndroidPositionProvider.kt#L26-L39` usa `LocationManager` AOSP puro
  (GPS/NETWORK/PASSIVE según preferencia). Funciona en ROMs sin GMS/China.
- Issues: #240 "Won't stay running… Android 6/7", #420 (MIUI mata en background), #422 (permiso background), #390
  (solo funciona con GPS/high accuracy). No hay fixes de OEM; solo permisos/UI.
- Valor neto: wakelock indefinido y alarma de 15 s son **frágiles**: Samsung bloquea wakelocks en FGS desde Android 11
  (ver §7) y la alarma se desactivó en API 31+.

## 2. OwnTracks Android — `owntracks/android` (EPL-1.0)

- **FGS con tipo `connectedDevice`** (no `location`), porque su razón de ser es la conexión MQTT persistente
  (`AndroidManifest.xml#L190-L193`). `START_STICKY` (`BackgroundService.kt#L270-L271`).
- **Reinicio tras swipe (técnica clave)**: `onTaskRemoved()` programa `alarmManager.setAndAllowWhileIdle(
  ELAPSED_REALTIME_WAKEUP, now+delay, PendingIntent.getForegroundService(...))` (`BackgroundService.kt#L277-L312`).
  El comentario explica que es para OEMs que tratan el swipe como force-stop y pierden el restart de `START_STICKY`;
  la alarma vive en el sistema y `setAndAllowWhileIdle` da exención temporal de Doze para poder promover a FGS. El
  intent resultante se maneja en `#L384-L390` (`setupAndStartService()`).
- **Modos de localización**: `setupLocationRequest()` (`BackgroundService.kt#L631-L690`): `Significant` → prioridad
  Balanced (HighAccuracy en Android 16+ con GNSS opcional), `Move` → HighAccuracy, `Manual/Quiet` → LowPower;
  con `fastestInterval` configurable y `smallestDisplacement`.
- **Sensor de movimiento significativo**: `SignificantMotionSensor.kt#L21-L35,L67-L78,L107-L143` registra
  `TYPE_SIGNIFICANT_MOTION` (sensor one-shot wake-up) y al detectar movimiento pide fix GNSS con rate-limit. Despierta
  el chip sin mantener GPS encendido; es un "despertador por movimiento" independiente del OEM.
- **MQTT keep-alive con alarma exacta**: `AlarmPingSender.kt#L40-L55` usa `setExactAndAllowWhileIdle` para el ping
  del broker MQTT (comentario L39: requiere que el usuario haya concedido alarmas exactas).
- **Watchdog de conexión**: `Scheduler.kt#L78-L97` encola `MQTTConnectionWatchdogWorker` periódico de **15 min**
  (mínimo de WorkManager) que *verifica activamente* la conexión y reconecta; `Scheduler.kt#L157-L174` backoff
  exponencial acotado a **10 min** (evita el tope de 5 h de WorkManager y la no-recuperación en Doze).
  Reintento reactivo adicional en `MQTTReconnectWorker`/`scheduleMqttReconnect` (L115-L133).
- **Sin wakelocks**: 0 usos de `newWakeLock` en todo `app/src` (solo el permiso en manifest). No usa `android:process`.
- **Fallback AOSP**: flavor `oss` con `AospLocationProviderClient.kt#L86-L96` (`LocationManagerCompat.requestLocationUpdates`)
  para dispositivos sin GMS; el flavor `gms` usa FusedLocation (`ToGMSLocationRequest.kt`).
- **Notificación ongoing con acciones**: `OngoingNotification.kt#L55-L70` añade acciones "publicar ahora" y "cambiar modo"
  (`PendingIntent.getService`) — recuperación manual sin abrir la app, y visibilidad si el proceso quedó a medias.
- Issues: #700 (OnePlus mata el proceso), #976 (Android 11 background location), #1023 (Samsung: tras reboot no envía
  hasta abrir la app), #656 (outbox HTTP se atasca sin retry: justifica persistencia/reintentos), #86 (primer bug de
  background). El bug #656 enlaza con nuestra outbox.

## 3. Home Assistant Companion — `home-assistant/android` (Apache-2.0)

- **Background Location vía FusedLocation**: petición con `Priority.PRIORITY_BALANCED_POWER_ACCURACY`
  (`LocationSensorManager.kt#L1046-L1050`), updates típicos cada 1-3 min (docs).
- **Single Accurate Location**: `LocationSensorManager.kt#L1205-L1225`: `PRIORITY_HIGH_ACCURACY`, `maxUpdates=5`,
  `minUpdateInterval=5000 ms`, y **PARTIAL_WAKE_LOCK de 10 min** dentro del callback para no perder el fix.
- **Modo alta precisión = FGS `location`**: `HighAccuracyLocationService.kt#L61-L75` arranca FGS con
  `FOREGROUND_SERVICE_TYPE_LOCATION`, intervalo ≥5 s y notificación permanente con coordenadas (docs).
- **Reinicio de FGS autoinducido**: `ForegroundServiceLauncher.kt#L57-L80` reinicia el servicio con alarma
  `RTC_WAKEUP` a +2 s y máquina de estados `isStarting/isRunning/shouldStop/restartInProcess` (evita carreras
  start/stop que dejan el servicio muerto).
- **FCM como disparador**: los comandos `request_location_update` y `high_accuracy_mode` llegan por notificación y
  activan una petición puntual o el FGS. Es el mismo patrón que nuestro "despertador" pero con comando de servidor.
- **Troubleshooting oficial**: pasos genéricos (permiso "all the time", battery optimization off, unrestricted data,
  desactivar capas extra del fabricante) y **historial de ubicación de 48 h in-app**; el FAQ dice explícitamente que
  "sin historial" suele ser acceso limitado a background o el sistema matando la app.
  `https://companion.home-assistant.io/docs/core/location/` y `/docs/troubleshooting/faqs#device-tracker-is-not-updating-in-android-app`
- Issue representativa de OEM: #979 (Xiaomi, procesos en background). El proyecto invierte en observabilidad
  (logs/historial) más que en hacks OEM.

## 4. GPSLogger — `mendhak/gpslogger` (GPL-2.0)

- FGS con tipo `FOREGROUND_SERVICE_TYPE_LOCATION` (`GpsLoggingService.java#L99-L103,L133-L155`); `START_STICKY` (L155).
- **Auto-restart tras kill**: `onDestroy()` detecta `session.isStarted()` y emite broadcast a `RestarterReceiver`
  (`GpsLoggingService.java#L159-L172`), que relanza el FGS con `ContextCompat.startForegroundService`
  (`RestarterReceiver.java#L17-L33`). Es el mismo "reinicio post-mortem" que hacemos con intents, pero **disparado por
  el propio onDestroy**.
- **Heartbeat por alarma exacta para el siguiente punto**: `setAlarmForNextPoint()` (`#L1231-L1248`) usa
  `setExactAndAllowWhileIdle` si `canScheduleExactAlarms`, con fallback `setAndAllowWhileIdle`. Autosend igual
  (`#L344-L349`). `onLowMemory()` reprograma +5 min (`#L173-L181`). Como la alarma despierta el proceso, el pipeline
  se autorepara aunque el FGS haya muerto.
- `StartupReceiver.java#L36-L48` (boot) y `MyPackageUpgradeReceiver` (post-update). Watchdog y alarmas coexisten: la
  alarma es la red de seguridad real.

## 5. Gadgetbridge — `freeyourgadget/Gadgetbridge` (AGPL-3.0) y otros

- `DeviceCommunicationService`: `START_STICKY` (`DeviceCommunicationService.java#L699-L710`), FGS `connectedDevice`,
  `startForeground` en `#L1234-L1243`.
- **Reconexión con backoff por alarma tolerante a Doze**: `AutoConnectIntervalReceiver.java#L101-L115`
  (`setAndAllowWhileIdle`, delay x2 hasta 64 s).
- **Re-schedule tras cambios de hora**: `TimeChangeReceiver.java#L126-L144` usa `setExact`/`setAndAllowWhileIdle` al
  recibir `TIME_SET`/`TIMEZONE_CHANGED` (las alarmas se pierden/desfasan tras Doze y cambios de hora; este receiver
  las rearma).
- Autoarranque: `AutoStartReceiver.java#L31-L46` (`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`), manifest `#L526-L534`.
- `setAlarmClock` solo se usa para la alarma de sueño del usuario (`SleepAlarmWidget.java#L149-L156`), **no** como
  keep-alive. El flavor `banglejs` (Bangle.js) reutiliza el mismo patrón FGS/STICKY (`app/src/banglejs/AndroidManifest.xml`).
- **PhoneTrack** (`gitlab.com/eneiluj/phonetrack-android`, GPLv3): `LoggerService` `START_STICKY`
  (`LoggerService.java#L337`), FGS (L206/L260), `setExactAndAllowWhileIdle` para el siguiente punto con fallback
  `set()` (L1421-L1423), `LocationManager` AOSP con NETWORK+GPS (L581-L593), `BootCompletedReceiver.java#L31-L36`.
- **transistorsoft/flutter_background_geolocation** (wrapper Apache-2.0): el motor es un AAR propietario
  (`com.transistorsoft:tslocationmanager:4.6.+`, `android/build.gradle`), pero soporta **HMS Location (Huawei/Honor)**
  como proveedor alternativo (`DEFAULT_HMS_LOCATION_VERSION`, mismo archivo). No es reimplementable; sí es evidencia
  de que en Honor hay que prever HMS/no-GMS.

## 6. dontkillmyapp.com (metodología y OEMs) y solución real aplicada

- Metodología de score: puntúa si matan FGS por defecto y si el usuario puede configurarlo (`/about_score`).
- **Samsung 5/5**: Android 11 mata apps que mantienen wake lock en FGS (issuetracker.google.com/issues/179644471);
  "Put unused apps to sleep" (≈3 días) y re-añadido tras updates. Dev section: **"No known solution on dev end"**.
  Workarounds de usuario: battery optimization off, "Never sleeping apps", lock en Recents, Good Guardians.
  Implicación: el wakelock permanente de Traccar puede no servir en One UI reciente.
- **Xiaomi 5/5**: sin APIs; "Autostart" (MIUI 14), lock en Recents, MIUI Optimizations, "No restrictions". Único
  check programable: `XomaDev/MIUI-autostart` para leer el estado de autostart.
- **Huawei/EMUI 5/5 (aplica a Honor/MagicOS)**: `PowerGenie`/`HwPFWService` mata todo lo no whitelisted; workaround
  histórico **de código**: usar tag de wakelock `LocationManagerService` en EMUI 4/5/6 para no ser matado; en Honor
  el ajuste es "Battery > App launch > Manage manually" (todos los toggles ON) + Startup manager.
- **Tecno 3/5 (Transsion; Infinix XOS/HiOS es familia similar)**: "Power Saving Management" congela todo (servicios,
  FGS, timers) hasta abrir la app; Dev: sin solución. Ajustes: battery lab, auto-start en Phone Master, lock en Recents,
  desactivar screen-off push/sleep.
- **Honor, ZTE y Infinix no tienen página propia** (`/honor`, `/zte`, `/infinix` = 404); Honor se cubre en Huawei y
  ZTE/Infinix solo en `/general`. No existe API OEM estable: la mitigación es guía de usuario + redundancia de canales.

## 7. Patrones transversales y mitos (¿doble servicio? ¿mediaPlayback?)

- `START_STICKY`: Traccar, OwnTracks, GPSLogger, Gadgetbridge, PhoneTrack. **HA no lo usa** (reenvío por FCM +
  sensor framework). Es barato pero no sobrevive a force-stop/swipe en OEMs agresivos (OwnTracks lo parchea con alarma).
- `PARTIAL_WAKE_LOCK`: Traccar (permanente), HA (10 min en fix), OwnTracks/GPSLogger/PhoneTrack (ninguno).
  Con Android 11+ en Samsung es contraproducente si no hay exclusión de batería.
- `setExactAndAllowWhileIdle`: GPSLogger, OwnTracks (MQTT ping), PhoneTrack, Gadgetbridge (reconnect). Requiere
  `SCHEDULE_EXACT_ALARM`/`canScheduleExactAlarms` (GPSLogger/OwnTracks verifican antes; fallback inexacto).
- `setAlarmClock` visible: solo como alarma de usuario (Gadgetbridge), **ningún tracker lo usa como keep-alive**.
- **Doble servicio en proceso separado**: ninguno (`android:process` ausente en los 6 manifests). No es patrón del ecosistema.
- **FGS tipo `mediaPlayback` como truco**: ninguno. HA solo usa `mediaPlaybackRequiresUserGesture` del WebView
  (`FrontendScreen.kt`), no como tipo de servicio. Es un hack detectable y con riesgo de crash/política; no hay
  precedente OSS que lo valide.
- **Notificación persistente con acciones** (OwnTracks): convierte la notificación en panel de recuperación manual.
- **Observabilidad**: HA (historial 48 h) y OwnTracks (logs exportables) tratan el fallo como algo a diagnosticar,
  no solo a parchear.
- **Proveedor AOSP vs GMS**: Traccar y PhoneTrack son 100% `LocationManager`; OwnTracks ofrece ambos; transistorsoft
  añade HMS. Depender solo de FusedLocation es un punto único de fallo en ROMs chinas/sin GMS.

## 8. Licencias (para reimplementar ideas, no copiar código)

| Proyecto | Licencia | ¿Permite reusar código en app propietaria? |
|---|---|---|
| traccar-client-android | Apache-2.0 | Sí, con aviso de copyright/NOTICE |
| home-assistant/android | Apache-2.0 | Sí, con aviso |
| owntracks/android | EPL-1.0 | Copyleft débil por archivo; evitar copiar |
| gpslogger | GPL-2.0 | No (copyleft fuerte) |
| Gadgetbridge | AGPL-3.0 | No (copyleft fuerte, red) |
| phonetrack-android | GPL-3.0 | No |
| flutter_background_geolocation (wrapper) | Apache-2.0 | Sí el wrapper; el motor `tslocationmanager` es propietario |
| dontkillmyapp.com | Contenido en GitHub `urbandroid-team/dont-kill-my-app` | Técnicas/ideas, citar |

Nota: las **técnicas e ideas** (alarmas, tipos de FGS, sensores) no son objeto de copyright; reimplementación limpia
es viable. Evitar copiar literal de EPL/GPL/AGPL.

## 9. Tabla resumen final

| Técnica | Evidencia (código/issue) | ¿Ya? | ¿Aplica sin hacks? | Riesgo |
|---|---|---|---|---|
| `START_STICKY` en FGS | Traccar TrackingService.kt#L72-L75; GPSLogger GpsLoggingService.java#L155 | SI | Sí | Bajo; no sobrevive swipe en OEM |
| `onTaskRemoved` + `setAndAllowWhileIdle` + `PendingIntent.getForegroundService` | OwnTracks BackgroundService.kt#L277-L312 | **NO** | Sí (API 21+/26+) | Bajo; OEM que borra alarmas lo anula |
| Wakeful broadcast→FGS (wakelock 60 s ref-counted) | Traccar WakefulBroadcastReceiver.kt#L34-L52 | **NO** | Sí | Bajo; wakelock indefinido (Traccar) sí es riesgoso en Samsung 11+ |
| PARTIAL_WAKE_LOCK indefinido en FGS | Traccar TrackingService.kt#L53-L57; dontkillmyapp.com/samsung | PARCIAL | Sí, pero… | Alto en One UI (Samsung mata FGS con wakelock) |
| Alarma exacta recurrente como heartbeat del pipeline | GPSLogger #L1231-L1248; PhoneTrack LoggerService.java#L1421-L1423 | SI (guardián) | Sí | Medio: exención de alarmas exactas revocable; requiere `canScheduleExactAlarms` |
| Watchdog WorkManager 15 min que verifica conexión real + backoff ≤10 min | OwnTracks Scheduler.kt#L78-L97,#L157-L174 | **NO** | Sí | Bajo: granularidad 15 min, Doze la retrasa |
| Sensor `TYPE_SIGNIFICANT_MOTION` → fix GNSS | OwnTracks SignificantMotionSensor.kt#L107-L143 | **NO** | Sí | Bajo: no todos los SoC lo exponen |
| `LocationManager` AOSP (GPS/NETWORK/PASSIVE) sin GMS | Traccar AndroidPositionProvider.kt#L26-L39; PhoneTrack LoggerService.java#L581-L593 | **NO/PARCIAL** | Sí | Bajo; cubre ROMs sin GMS/China |
| Fallback HMS (Huawei/Honor) | transistorsoft android/build.gradle (DEFAULT_HMS_LOCATION_VERSION) | **NO** | Sí | Bajo para Honor; dependencia extra |
| FGS tipo `location` con update interval y notificación con datos | HA HighAccuracyLocationService.kt#L61-L75; docs location | SI | Sí | Bajo en Android 14+ (requiere permiso background) |
| Wake lock temporal (10 min) ligado a la petición de fix | HA LocationSensorManager.kt#L1205-L1225 | PARCIAL | Sí | Bajo: acotado y liberado |
| Reinicio de FGS con alarma +2 s y máquina de estados anti-carrera | HA ForegroundServiceLauncher.kt#L57-L80 | **NO** | Sí | Bajo |
| Auto-repair en `onDestroy` vía broadcast a receiver que relanza FGS | GPSLogger #L159-L172 + RestarterReceiver.java#L17-L33 | PARCIAL (intents) | Sí | Bajo; solo cubre kills que llaman onDestroy |
| Re-schedule de alarmas en `TIME_SET`/`TIMEZONE_CHANGED` | Gadgetbridge TimeChangeReceiver.java#L126-L144 | **NO** | Sí | Bajo |
| Reconnect con backoff por `setAndAllowWhileIdle` | Gadgetbridge AutoConnectIntervalReceiver.java#L101-L115 | PARCIAL (outbox) | Sí | Bajo |
| Notificación ongoing con acciones (publicar/modo) | OwnTracks OngoingNotification.kt#L55-L70 | **NO** | Sí | Nulo |
| Receiver BOOT + `MY_PACKAGE_REPLACED` | Traccar AutostartReceiver.kt#L22-L30; Gadgetbridge AutoStartReceiver.java#L31-L46 | SI (boot) / **NO** (update) | Sí | Bajo |
| Proceso separado / FGS `mediaPlayback` como keep-alive | Ausente en los 6 repos auditados | NO | No recomendado | Alto (política/crash), sin precedente OSS |
| Diálogo in-app de ajustes OEM por fabricante | dontkillmyapp.com/{samsung,xiaomi,huawei,tecno,general} | PARCIAL | Sí | Nulo; no automatizable |
| Historial de ubicación in-app (48 h) para diagnóstico | HA troubleshooting faqs (Location history) | **NO** | Sí | Nulo; clave para soporte en campo |
| Check programable de autostart MIUI | XomaDev/MIUI-autostart (citado en dontkillmyapp.com/xiaomi) | **NO** | Sí (MIUI) | Bajo; solo lectura |

## Anexo — URLs de evidencia (por app; `#Lx-Ly` = líneas)

- Traccar: base `https://github.com/traccar/traccar-client-android/blob/master/app/src/main/java/org/traccar/client/` → `TrackingService.kt#L43-L75`, `WakefulBroadcastReceiver.kt#L34-L52`, `AutostartReceiver.kt#L22-L30`, `MainFragment.kt#L233-L238`, `AndroidPositionProvider.kt#L26-L39`; manifest `.../app/src/main/AndroidManifest.xml#L70-L84`; issues `github.com/traccar/traccar-client-android/issues/{240,420,422,390}`.
- OwnTracks: base `https://github.com/owntracks/android/blob/master/project/app/src/main/java/org/owntracks/android/` → `services/BackgroundService.kt#L277-L312,#L631-L690`, `services/SignificantMotionSensor.kt#L107-L143`, `services/worker/Scheduler.kt#L78-L97,#L157-L174`, `net/mqtt/AlarmPingSender.kt#L40-L55`, `oss/java/.../location/AospLocationProviderClient.kt#L86-L96`; manifest `.../project/app/src/main/AndroidManifest.xml#L190-L213`; issues `github.com/owntracks/android/issues/{700,976,1023,656,86}`.
- Home Assistant: base `https://github.com/home-assistant/android/blob/main/app/src/full/kotlin/io/homeassistant/companion/android/` → `sensors/LocationSensorManager.kt#L1046-L1050,#L1205-L1225`, `location/HighAccuracyLocationService.kt#L61-L75`; `.../app/src/main/kotlin/io/homeassistant/companion/android/util/ForegroundServiceLauncher.kt#L57-L80`; docs `companion.home-assistant.io/docs/core/location/` y `/docs/troubleshooting/faqs`.
- GPSLogger: base `https://github.com/mendhak/gpslogger/blob/master/gpslogger/src/main/java/com/mendhak/gpslogger/` → `GpsLoggingService.java#L159-L181,#L1231-L1248`, `RestarterReceiver.java#L17-L33`, `StartupReceiver.java#L36-L48`.
- Gadgetbridge: base `https://github.com/freeyourgadget/Gadgetbridge/blob/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/` → `service/DeviceCommunicationService.java#L699-L710`, `service/receivers/AutoConnectIntervalReceiver.java#L101-L115`, `externalevents/TimeChangeReceiver.java#L126-L144`, `externalevents/AutoStartReceiver.java#L31-L46`.
- PhoneTrack: `https://gitlab.com/eneiluj/phonetrack-android/-/blob/master/app/src/main/java/net/eneiluj/nextcloud/phonetrack/service/` → `LoggerService.java#L337,#L1421-L1423,#L581-L593`, `BootCompletedReceiver.java#L31-L36`.
- transistorsoft: `github.com/transistorsoft/flutter_background_geolocation/blob/master/android/build.gradle` (HMS/AAR propietario).
- OEM: `dontkillmyapp.com/{samsung,xiaomi,huawei,tecno,general,about_score}`; Samsung wake lock `issuetracker.google.com/issues/179644471`; MIUI autostart `github.com/XomaDev/MIUI-autostart`.

## 10. Conclusión: el límite de arquitectura real

1. No existe API OSS ni OEM que garantice resurrección tras **force-stop** (swipe en OEMs agresivos / "Power Saving
   Management" de Transsion): incluso OwnTracks lo documenta como "best-effort" (`BackgroundService.kt#L288-L293`).
2. Las tres redes que sí funcionan y son independientes entre sí: (a) FGS+`START_STICKY` para kills normales,
   (b) **alarma exacta/FCM** para despertar tras Doze/congelación, (c) **watchdog que verifica el pipeline de punta a
   punta** (no solo que el proceso viva). Solo (c) detecta "proceso vivo pero conexión/outbox muertos" (#656).
3. La robustez multi-fabricante viene de **redundancia + AOSP-first + guía de usuario**, no de un truco único. Samsung
   y Huawei/Tecno explícitamente dicen que no hay solución del lado dev; Xiaomi/Honor exigen toggles manuales.
4. Para "cualquier teléfono": evitar lock-in a GMS (usar `LocationManager` AOSP y HMS cuando exista) y no abusar de
   wakelocks indefinidos (Samsung 11+ los mata). El código de todos los proyectos es evidencia de que la batería se
   gestiona con *alarmas/sensores*, no con wakelocks largos.
