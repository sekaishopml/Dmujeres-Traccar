# ARCH — Límites reales de la plataforma Android 13–16 para tracking sideload

> Investigación para `com.dmujeres.traccar` (FGS `location`, minSdk 26 / target 35, sideload, sin root/Accessibility/Shizuku/appops/Play).
> Fecha: 2026-09-19. Cada afirmación lleva URL. Alcance: Android 13 (33) → 16 (36), con avisos de Android 17 (37) donde aplica.

## 1. Wakelock parcial, cached-app freezer y CPU en FGS
- **FGS NO garantiza CPU despierta.** Un FGS sube la importancia del proceso (visible) y evita que el proceso entre en `cached`, pero no impide que la CPU entre en suspend: "you can use wake locks to keep the device from going to sleep... you may need to keep the device awake even when your app is in the background" — https://developer.android.com/develop/background-work/background-tasks/awake/wakelock ; confirmado en SO: https://stackoverflow.com/questions/52002533/does-service-startforeground-imply-wakelock
- **El freezer ataca solo procesos `cached`.** Android 14+ congela 10 s tras pasar a cached y despierta en cualquier lifecycle event (intent, job service, activity); un FGS activo hace que el proceso NO sea cached. Exenciones (file locks, `BIND_WAIVE_PRIORITY`) son "implementation details": https://source.android.com/docs/core/perf/cached-apps-freezer y https://source.android.com/docs/core/architecture/ipc/binder-freezer
- **Contra freezers OEM el wakelock no basta**: Huawei `HwPFWService/PowerGenie` mata por política (incluso registra "wakelock > 60 mins... force stop abnormal wakelock app"): https://dontkillmyapp.com/huawei
- **Coste**: "Creating and holding wake locks can have a dramatic impact on the device's battery life"; Google mide abuso de wakelocks en Android vitals (blame energético): https://android-developers.googleblog.com/2025/09/guide-to-excessive-wake-lock-usage.html y https://developer.android.com/topic/performance/vitals/excessive-battery-usage
- **Doze ignora wakelocks** salvo exención de batería (app "partially exempt" puede red y wakelocks parciales en Doze): https://developer.android.com/training/monitoring-device-state/doze-standby
- **Veredicto**: usar `PARTIAL_WAKE_LOCK` con timeout SOLO durante la ventana de fix/envío, y asumir que con Doze sin whitelist el wakelock no protege. No es estrategia antikill OEM.

## 2. Trampa FGS `mediaPlayback`
- **Sigue siendo técnicamente posible en 14/15/16** si declaras `android:foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (permiso normal, auto-concedido, no revocable): https://developer.android.com/develop/background-work/services/fgs/service-types y https://developer.android.com/about/versions/14/changes/fgs-types-required
- **Sin timeout de tipo** (los timeouts de 6 h son `dataSync`/`mediaProcessing`; `location` y `mediaPlayback` no tienen): https://developer.android.com/develop/background-work/services/fgs/timeout
- **Límite 15+**: no puedes lanzar un FGS `mediaPlayback` desde `BOOT_COMPLETED` (`ForegroundServiceStartNotAllowedException`): https://developer.android.com/about/versions/15/behavior-changes-15
- **OSS que lo explota**: librería keep-alive china que puntúa `mediaPlayback` y `MediaSession` como estrategias "5/5" (junto a daemons nativos, fork, VPN): https://github.com/Pangu-Immortal/KeepLiveService ; casos reales de `MediaSessionService` que mantiene el proceso vivo tras swipe en 14: https://github.com/androidx/media/issues/1269
- **Riesgos**: (a) política Play lo prohíbe si no hay reproducción real (irrelevante en sideload): https://support.google.com/googleplay/android-developer/answer/16965181 ; (b) notificación/controles de media visibles y confusos para el usuario; (c) OEMs lo matan igual; (d) **Android 17 endurece audio en background**: si no hay reproducción real y la app no está visible/FGS con WIU, las llamadas de audio fallan en silencio y el audio iniciado desde BOOT_COMPLETED se suprime: https://developer.android.com/about/versions/17/changes/bg-audio
- **Veredicto**: descartar como "truco de supervivencia" (no evita killers OEM, rompe la confianza de la usuaria y muere en Android 17). Si se usa, debe ser audio real de alarma/aviso.

## 3. `setAlarmClock()` visible
- Es la alarma de mayor garantía: dispara en Doze y "the system exits Doze shortly before those alarms fire": https://developer.android.com/training/monitoring-device-state/doze-standby y https://source.android.com/docs/core/power/platform_mgmt
- AOSP: "Alarms scheduled via this API will be allowed to start a foreground service even if the app is in the background": https://developer.android.com/reference/android/app/AlarmManager#setAlarmClock(android.app.AlarmManager.AlarmClockInfo,%20android.app.PendingIntent)
- **Muestra el ícono de reloj en status bar** (el sistema informa la próxima alarma al usuario): https://developer.android.com/reference/android/app/AlarmManager y https://proandroiddev.com/beyond-doze-building-reliable-background-execution-on-modern-android-including-oem-realities-5fa0a6e05672
- **No es gratis**: requiere `SCHEDULE_EXACT_ALARM` (denegado por defecto en 14+; `canScheduleExactAlarms()` puede ser false) o ser app de reloj/calendario con `USE_EXACT_ALARM` (auto-concedida al instalar; prohibida en Play fuera de ese caso): https://developer.android.com/about/versions/14/changes/schedule-exact-alarms y https://support.google.com/googleplay/android-developer/answer/16558241
- El usuario y el sistema pueden revocar el permiso; sobreexplotarlo es señal de abuso para reviews/usuarios: https://developer.android.com/develop/background-work/services/alarms
- **Recomendación tracking legítimo**: encadenar `setExactAndAllowWhileIdle` cada 15 min (no `setAlarmClock` cada minuto); reservar `setAlarmClock` para "modo emergencia/última hora conocida" activado por la usuaria y con texto que explique el ícono de reloj. En sideload `USE_EXACT_ALARM` evita el diálogo de permiso, pero es igualmente visible.

## 4. `SYSTEM_ALERT_WINDOW` (SAW) como exención para arrancar FGS
- Sigue siendo exención, **pero Android 15+ (target 35) exige overlay `TYPE_APPLICATION_OVERLAY` visible antes de `startForegroundService()`**; si no, `ForegroundServiceStartNotAllowedException`: https://developer.android.com/about/versions/15/behavior-changes-15 y https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- **Sideload**: SAW ("Display over other apps") está en la lista CDD de *restricted settings* de Android 15; hay que habilitar "Allow restricted settings" en App info: https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions y https://support.google.com/android/answer/12623953
- Impacto real ya reportado: Home Assistant hubo que crear/depurar overlay previo por API 35: https://github.com/home-assistant/android/issues/5335
- **Veredicto**: técnicamente usable como "boot receiver → overlay 1px → FGS", pero frágil, invisible-pero-detectable, gasta batería, y en ROMs restrictivas el propio toggle está restringido. Usar solo como capa extra documentada, nunca como pilar.

## 5. `START_STICKY` / `onTaskRemoved` / restart de FGS
- `START_STICKY` es best-effort: el sistema "tries to recreate" y entrega `onStartCommand` con `Intent null` (crash clásico si no se maneja: https://issuetracker.google.com/issues/307329994). No hay garantía de tiempo: https://developer.android.com/guide/components/services
- Swipe en Recents en AOSP **no** mata el FGS; OEM sí. Xiaomi aplica backoff creciente 1s→4s→16s→64s a los restarts: https://stackoverflow.com/questions/57976116/xiaomi-devices-are-stopping-foreground-services
- Android 13+ Task Manager permite a la usuaria detener el FGS desde la notificación (equivale a force-stop de la app): https://developer.android.com/about/versions/13/behavior-changes-all
- Tras force-stop, la app queda en estado `stopped`: no recibe broadcasts implícitos (BOOT_COMPLETED incluido) hasta que la usuaria la abra: https://developer.android.com/about/versions/14/behavior-changes-all (contexto de `killBackgroundProcesses`/procesos)
- Importancia de proceso: FGS es "visible", pero puede ser degradado/matado bajo presión: https://developer.android.com/guide/components/activities/process-lifecycle
- **Veredicto**: `START_STICKY` + `onTaskRemoved` (re-armar alarmas) + guardar estado en disco es obligatorio, pero es recuperación, no supervivencia.

## 6. Proceso separado `:remote` — mayormente mito
- Android mata **procesos**, no componentes sueltos, y decide por importancia de cada proceso: https://developer.android.com/guide/components/activities/process-lifecycle ; SO clásico: https://stackoverflow.com/questions/24883738/does-android-kill-each-service-or-the-whole-process
- Un segundo proceso sobrevive a la muerte del UI (útil contra OOM del proceso visible), pero **no** contra killers OEM que matan el paquete completo ni contra force-stop del usuario, y consume el doble de RAM (más probabilidad de ser candidato a LMK): https://stackoverflow.com/questions/49637967/minimal-android-foreground-service-killed-on-high-end-phone ("would probably help, but doesn't seem like a guarantee")
- El viejo parche de Android 6.0 (servicio en proceso aislado sin otros componentes) ya no aplica: https://dontkillmyapp.com/stock_android
- **Veredicto**: descartar como estrategia de supervivencia; solo tiene sentido si necesitas aislamiento de crash, y cada proceso necesita su propia notificación FGS.

## 7. WorkManager y JobScheduler: cuotas 14/15/16
- Cuotas por bucket: Active 20 min/h (jobs regulares), Working set 10 min/4 h, Frequent 10 min/12 h, Rare 10 min/24 h, Restricted 10 min/día; expedited con cuotas propias: https://developer.android.com/topic/performance/power/power-details
- **Android 16 (aplica a toda app, sin importar target)**: jobs que corren junto a un FGS **ya no son inmunes a la cuota**; jobs lanzados en top state también se contabilizan; el bucket Active pasa a tener cuota: https://developer.android.com/about/versions/16/behavior-changes-all y https://developer.android.com/develop/background-work/services/fgs/changes
- WorkManager con worker "long-running" (FGS interno) puede agotar la cuota de jobs de la app: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- `setImportantWhileForeground()` quedó sin efecto en 16; nuevo stop reason `STOP_REASON_TIMEOUT_ABANDONED`: https://developer.android.com/about/versions/16/behavior-changes-all
- Los **user-initiated data transfer jobs** (UIDT) están exentos de cuotas y son la vía recomendada para subidas en 16: https://developer.android.com/develop/background-work/background-tasks/uidt
- Excepción útil: tras un FCM high-priority hay "brief exemption" para expedited jobs: https://firebase.google.com/docs/cloud-messaging/android-message-priority
- **Veredicto**: WorkManager sirve como red de seguridad de baja frecuencia (flush de outbox), nunca como keep-alive; en 16 asume cuota incluso con FGS corriendo.

## 8. Exact alarms con exención de batería
- `canScheduleExactAlarms()` es true si tienes `SCHEDULE_EXACT_ALARM` **o estás en la lista de exención de power-save**: https://developer.android.com/reference/androidx/core/app/AlarmManagerCompat y https://developer.android.com/reference/android/app/AlarmManager
- `setExactAndAllowWhileIdle` dispara en Doze. Cuota documentada: **1 cada 9 min por app** (training) y AOSP indica **1 cada 15 min en Doze**; el reference dice "no más de ~1/min normal; en idle puede ser bastante más largo, como 15 min": https://developer.android.com/training/monitoring-device-state/doze-standby , https://source.android.com/docs/core/power/platform_mgmt , https://developer.android.com/reference/android/app/AlarmManager
- Con whitelist de batería, los wakelocks y la red no son ignorados, pero la cuota de `allowWhileIdle` sigue existiendo: https://developer.android.com/training/monitoring-device-state/doze-standby
- **Recomendación**: cadena de una sola alarma (single-shot) re-armada al disparar, con `PendingIntent` único (`FLAG_UPDATE_CURRENT | IMMUTABLE`), intervalo 15 min; `setAlarmClock` solo en modo emergencia. Verificar con `canScheduleExactAlarms()` y pedir `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` si falta.

## 9. Timeouts de FGS (Android 15+) y `specialUse`
- Timeout 6 h/24 h **solo** `dataSync` y `mediaProcessing`; `location` NO tiene timeout. Al agotarse: `Service.onTimeout()` → `stopSelf()` o crash `RemoteServiceException`: https://developer.android.com/develop/background-work/services/fgs/timeout
- `shortService`: ~3 min, sin START_STICKY, no puede lanzar otros FGS: https://developer.android.com/develop/background-work/services/fgs/service-types
- `specialUse`: sin timeout, permiso `FOREGROUND_SERVICE_SPECIAL_USE` (normal, auto-concedido); exige `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` y justificación en Play Console (para sideload no hay revisión): https://developer.android.com/develop/background-work/services/fgs/service-types y https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/pm/ServiceInfo.java
- En Android 15+ no se puede lanzar `dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone` desde `BOOT_COMPLETED`; `location` y `specialUse` sí están permitidos (caso real: `shortService` sí es rechazado pese a no figurar en la lista): https://developer.android.com/about/versions/15/behavior-changes-15 y https://stackoverflow.com/questions/79010058/getting-foregroundservicestartnotallowedexception-when-starting-a-foreground-ser
- **Veredicto**: para tracking, `location` es el tipo correcto y sin timeout; `specialUse` no aporta nada extra y añade riesgo de política si algún día se publica. No usar `dataSync` como "paraguas".

## 10. Android 16: cambios que impactan tracking
- **JobScheduler quotas** (regular/expedited) con FGS concurrente, top-state y bucket Active → la red de seguridad de jobs pierde inmunidad: https://developer.android.com/about/versions/16/behavior-changes-all
- `setImportantWhileForeground()` deprecado sin efecto; `STOP_REASON_TIMEOUT_ABANDONED` para jobs abandonados (puede reducir frecuencia si ocurre seguido): https://developer.android.com/about/versions/16/behavior-changes-all
- Broadcast priorities ya no globales entre procesos (no afecta si solo usas receivers propios): https://developer.android.com/about/versions/16/behavior-changes-all
- Páginas de 16 KB: recompilar/alinear el APK para evitar el diálogo de compatibility mode: https://developer.android.com/about/versions/16/behavior-changes-all
- Sin cambios 16 en: timeouts de FGS location, Doze, cuota de exact alarms ni SAW (siguen reglas de 15): https://developer.android.com/about/versions/16/summary
- **Android 17 (aviso)**: background audio hardening rompe la trampa de audio silencioso (ver §2): https://developer.android.com/about/versions/17/changes/bg-audio

## 11. Mitos que NO funcionan
- `killBackgroundProcesses()` de otras apps: desde Android 14 solo mata procesos propios; para terceros no tiene efecto: https://developer.android.com/about/versions/14/behavior-changes-all y https://9to5google.com/2023/03/13/android-14-background-processes
- Apps "battery doctor/task killer": Google dice que es imposible que una app de terceros mejore memoria/energía/térmica; Android 14 las neutraliza: mismo enlace anterior.
- `restartService` cada N segundos (AlarmManager): muerto con las restricciones de background start (Android 12+) y el sistema ya reinicia servicios por sí mismo; SO clásico: "You don't. A foreground service is the best that you can get": https://stackoverflow.com/questions/43230387/how-to-prevent-the-system-kill-my-foreground-service
- Daemons de doble proceso / fork nativo estilo MarsDaemon/Leoric: sin efecto en Android 8+ y el ping por binder a un proceso congelado puede **matarlo** ("sending a synchronous binder transaction to a frozen process kills the remote process"): https://source.android.com/docs/core/architecture/ipc/binder-freezer
- "Ocultar la notificación" para que la usuaria no cierre: Android 13 Task Manager la muestra igual y permite detener la app: https://developer.android.com/about/versions/13/behavior-changes-all
- Pedir "deshabilitar Doze" por API: no existe; solo whitelist con `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` y acción manual de la usuaria: https://developer.android.com/training/monitoring-device-state/doze-standby
- ActivityManager/am hacks desde la app: requieren shell/root; appops/Shizuku quedaron fuera de alcance por diseño.

## 12. Historia SO/Reddit (2024–2026) sobre OEMs chinos
- Consenso: FGS + batería sin restricciones + autostart/protected + "lock" en recientes; sin root no hay automatización silenciosa de esos toggles: https://dontkillmyapp.com/xiaomi , https://dontkillmyapp.com/huawei , https://github.com/LIN4CRE/dkma-monster ("Anyone promising a stock, no-root, zero-tap fix... is lying")
- Código clásico de deep-links a Autostart por OEM (Xiaomi/Oppo/Vivo) sigue siendo lo que se recomienda desde SO: https://stackoverflow.com/questions/55652443/foreground-service-killed-in-doze-mode-for-some-devices-like-oppo-vivo-mi-etc ; librería MIUI-autostart: https://github.com/PraDevel/MIUI-autostart
- MIUI backoff progresivo al reintentar el servicio: https://stackoverflow.com/questions/57976116/xiaomi-devices-are-stopping-foreground-services ; "lock" en Recents: https://www.reddit.com/r/android_devs/comments/hmev62/keep_a_foreground_service_running_in_background
- Arquitectura recomendada 2026: capas redundantes (FGS + alarm `setExactAndAllowWhileIdle` autocadenada + watchdog FCM + reintentos en `onDestroy`/`onTaskRemoved` + persistencia), sabiendo que ninguna garantiza: https://dev.to/stoyan_minchev/what-android-oems-do-to-background-apps-and-the-11-layers-i-built-to-survive-it-28bb y https://proandroiddev.com/beyond-doze-building-reliable-background-execution-on-modern-android-including-oem-realities-5fa0a6e05672
- FCM high-priority como watchdog tiene cuota/servidor: Android 13 degrada a normal si el mensaje no genera notificación visible: https://developer.android.com/about/versions/13/behavior-changes-all
- Nota realista: Honor/Huawei puede matar GPS a bajo % de batería sin error (síntoma reportado por devs): https://dev.to/stoyan_minchev/what-android-oems-do-to-background-apps-and-the-11-layers-i-built-to-survive-it-28bb

## Tabla final
| Técnica | Límite real (13–16) | Coste / riesgo | Veredicto |
|---|---|---|---|
| FGS `location` + notificación | Sin timeout; mata en OEM/restricted; start desde bg prohibido salvo exención | Notificación permanente, batería GPS | **USAR** (pilar) |
| Wakelock parcial | No frena Doze ni OEM; ignorado en Doze; exención de batería lo habilita | Drenaje "dramático" (Google), vitals | **USAR acotado** (solo ventana activa) |
| Exención batería (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Habilita wakelocks/red en Doze y `canScheduleExactAlarms()` | Visible al usuario; requiere acción manual | **USAR** (onboarding) |
| FCM high-priority watchdog | Cuota degradable; solo notificaciones tiempo-real | Depende de red/servidor Google | **USAR** como red de seguridad |
| `setExactAndAllowWhileIdle` autocadenada 15 min | 9–15 min por app en Doze | Despertadores periódicos, batería | **USAR** (anti-kill principal) |
| `setAlarmClock()` | Escapa Doze y puede arrancar FGS en bg; ícono de reloj | Percepción de abuso; Play lo restringe | **USAR con moderación** (modo emergencia) |
| `USE_EXACT_ALARM` en sideload | Auto-concedida al instalar; prohibida en Play fuera de reloj/calendario | Sin costo técnico; riesgo si se publica | **USAR si se acepta visible** (alternativa a pedir permiso) |
| SAW + overlay visible (15+) | Exención solo con overlay visible; restricted settings en sideload | Overlay/batería/complejidad; frágil | **DESCARTAR** como pilar |
| `mediaPlayback` FGS trick | Funciona 14–16 sin timeout; no desde BOOT_COMPLETED en 15+; muere en 17 | Notificación/media, engaño, política | **DESCARTAR** |
| `specialUse` FGS | Sin timeout; permiso normal; property obligatoria | Revisión Play si se publica; sin beneficios vs `location` | **DESCARTAR** (usar `location`) |
| `START_STICKY` + `onTaskRemoved` | Best-effort; OEM backoff; null intent crash | Complejidad mínima | **USAR** (recuperación) |
| Proceso `:remote` | No protege del kill por paquete/OEM; +RAM | Doble notificación/estado | **DESCARTAR** |
| JobScheduler/WorkManager | Cuotas por bucket (Active 20 min/h); Android 16 quita inmunidad con FGS | Bajo coste, baja frecuencia | **USAR** solo para outbox/flush |
| UIDT jobs (16) | Exentos de cuota de jobs | API 34+/16, para subidas iniciadas por usuario | **EVALUAR** |
| `killBackgroundProcesses`/daemons/task-killers | Sin efecto desde 14 / muertos desde 8+ | Riesgo de binder-kill | **DESCARTAR** |

## Lo que NO se puede aunque quisiéramos (sin root/Accessibility/Shizuku/appops/Play)
1. Sobrevivir garantizado a un "force stop" de la usuaria o al Task Manager de Android 13+: el estado `stopped` solo se limpia abriendo la app (docs Android 13/14).
2. Automatizar toggles OEM (Autostart MIUI, Protected apps Huawei, whitelists Oppo/Vivo): no hay API; solo deep-link + acción manual (dontkillmyapp, DKMA).
3. Evitar que el sistema ignore wakelocks en Doze sin whitelist de batería.
4. Garantizar restart tras kill OEM: `START_STICKY` es best-effort y Xiaomi aplica backoff creciente (SO 57976116).
5. Arrancar FGS con permiso "while-in-use" (location) desde background sin exención (ACCESS_BACKGROUND_LOCATION + whitelist/BOOT_COMPLETED, etc.) — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
6. Usar exact alarms libremente: en 14+ `SCHEDULE_EXACT_ALARM` está denegada por defecto y puede ser revocada; `USE_EXACT_ALARM` es solo para reloj/calendario (y visible).
7. Fingir reproducción de media a largo plazo: Android 17 suprime la reproducción/audio sin lifecycle válido.
8. Impedir que la usuaria desinstale/revoke permisos: cualquier onboarding es revocable.
9. Pedir "ignorar optimizaciones" sin diálogo del sistema (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` siempre muestra confirmación).
10. Tener garantía de CPU/red/GPS en Doze sin whitelist, ni con `mediaPlayback`, ni con `:remote`.

---
**Estrategia derivada**: `location` FGS + exención de batería + alarma exacta autocadenada (15 min) con `setExactAndAllowWhileIdle` + `setAlarmClock` solo emergencia + FCM high-priority watchdog + `START_STICKY`/`onTaskRemoved` + WorkManager para outbox + deep-links OEM guiados. Todo lo demás son mitos o trampas con fecha de caducidad.
